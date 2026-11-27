#define FUSE_USE_VERSION 31
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <fuse3/fuse.h>
#include <limits.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

/* A private, write-through FUSE volume. The real folder exists only in the
 * outer namespace; applications run in a second clean namespace and cannot
 * bypass it. */
static const char *source, *limit_file;
static int ready_fd;
struct handle {
  int fd;
  char *name;
  struct handle *next;
};
static struct handle *handles;
struct usage {
  uint64_t bytes;
  unsigned entries;
};

static uint64_t capacity(void) {
  unsigned long long value = 0;
  FILE *file = fopen(limit_file, "r");
  if (file) {
    if (fscanf(file, "%llu", &value) != 1)
      value = 0;
    fclose(file);
  }
  return value;
}
static int fullpath(const char *name, char path[PATH_MAX]) {
  if (!name)
    return -ENOENT;
  unsigned depth = 0;
  for (const char *p = name; *p; p++)
    if (*p == '/')
      depth++;
  if (depth > 16)
    return -ENAMETOOLONG;
  if (strlen(name) > 256 || strstr(name, "/../") ||
      strstr(name, "/.computer-id") || !strcmp(name, "/.."))
    return -EPERM;
  if (snprintf(path, PATH_MAX, "%s%s", source, name) >= PATH_MAX)
    return -ENAMETOOLONG;
  /* Neither symbolic links nor special files may escape the managed tree. */
  char check[PATH_MAX];
  strcpy(check, path);
  for (char *p = check + strlen(source) + 1;; p++) {
    if (*p != '/' && *p != 0)
      continue;
    char saved = *p;
    *p = 0;
    struct stat st;
    if (!lstat(check, &st) && S_ISLNK(st.st_mode))
      return -EPERM;
    *p = saved;
    if (!saved)
      break;
  }
  return 0;
}
static void walk(const char *relative, struct usage *u) {
  char path[PATH_MAX];
  if (snprintf(path, sizeof path, "%s%s", source, relative) >= sizeof path)
    return;
  DIR *dir = opendir(path);
  if (!dir)
    return;
  struct dirent *entry;
  while ((entry = readdir(dir))) {
    if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..") ||
        !strcmp(entry->d_name, ".computer-id"))
      continue;
    char name[PATH_MAX], child[PATH_MAX];
    if (snprintf(name, sizeof name, "%s/%s", relative, entry->d_name) >=
            sizeof name ||
        snprintf(child, sizeof child, "%s%s", source, name) >= sizeof child)
      continue;
    struct stat st;
    if (lstat(child, &st))
      continue;
    u->entries++;
    u->bytes += strlen(name);
    if (S_ISDIR(st.st_mode))
      walk(name, u);
    else if (S_ISREG(st.st_mode))
      u->bytes += st.st_size;
  }
  closedir(dir);
}
static struct usage used(void) {
  struct usage u = {0};
  walk("", &u);
  /* Unlinked but still-open files retain their space until the last close. */
  for (struct handle *h = handles; h; h = h->next) {
    struct stat st;
    if (fstat(h->fd, &st) || st.st_nlink)
      continue;
    int duplicate = 0;
    for (struct handle *p = handles; p != h; p = p->next) {
      struct stat other;
      if (!fstat(p->fd, &other) && other.st_dev == st.st_dev &&
          other.st_ino == st.st_ino) {
        duplicate = 1;
        break;
      }
    }
    if (!duplicate) {
      u.bytes += st.st_size + strlen(h->name);
      u.entries++;
    }
  }
  return u;
}
static int grow(uint64_t bytes) {
  struct usage u = used();
  return bytes && (bytes > capacity() || u.bytes > capacity() - bytes) ? -ENOSPC
                                                                       : 0;
}
static int new_entry(const char *name) {
  struct usage u = used();
  return u.entries >= 512 || grow(strlen(name)) ? -ENOSPC : 0;
}
static int case_collision(const char *name, const char *except) {
  char path[PATH_MAX];
  int e = fullpath(name, path);
  if (e)
    return e;
  char *base = strrchr(path, '/');
  if (!base)
    return -EINVAL;
  *base++ = 0;
  DIR *dir = opendir(path);
  if (!dir)
    return -errno;
  struct dirent *entry;
  while ((entry = readdir(dir))) {
    if (strcmp(base, entry->d_name) && !strcasecmp(base, entry->d_name)) {
      char existing[PATH_MAX];
      int length =
          snprintf(existing, sizeof existing, "%s/%s", path, entry->d_name);
      if (length < 0 || (size_t)length >= sizeof existing) {
        closedir(dir);
        return -ENAMETOOLONG;
      }
      const char *relative = existing + strlen(source);
      if (!except || strcmp(relative, except)) {
        closedir(dir);
        return -EEXIST;
      }
    }
  }
  closedir(dir);
  return 0;
}
static int remember(int fd, const char *name, struct fuse_file_info *fi) {
  struct handle *h = calloc(1, sizeof *h);
  if (!h) {
    close(fd);
    return -ENOMEM;
  }
  h->fd = fd;
  h->name = strdup(name);
  h->next = handles;
  handles = h;
  fi->fh = (uint64_t)(uintptr_t)h;
  return 0;
}
static struct handle *handle(struct fuse_file_info *fi) {
  return (struct handle *)(uintptr_t)fi->fh;
}
static int q_getattr(const char *name, struct stat *st,
                     struct fuse_file_info *fi) {
  char path[PATH_MAX];
  int e = 0;
  if (!fi || !fi->fh) {
    e = fullpath(name, path);
    if (e)
      return e;
  }
  if (fi && fi->fh)
    e = fstat(handle(fi)->fd, st);
  else
    e = lstat(path, st);
  if (e)
    return -errno;
  if (!S_ISDIR(st->st_mode) && !S_ISREG(st->st_mode))
    return -EPERM;
  return 0;
}
static int q_readdir(const char *name, void *buf, fuse_fill_dir_t fill,
                     off_t off, struct fuse_file_info *fi,
                     enum fuse_readdir_flags flags) {
  (void)off;
  (void)fi;
  (void)flags;
  char path[PATH_MAX];
  int e = fullpath(name, path);
  if (e)
    return e;
  DIR *dir = opendir(path);
  if (!dir)
    return -errno;
  struct dirent *item;
  while ((item = readdir(dir))) {
    if (!strcmp(item->d_name, ".computer-id"))
      continue;
    if (fill(buf, item->d_name, NULL, 0, 0))
      break;
  }
  closedir(dir);
  return 0;
}
static int q_open(const char *name, struct fuse_file_info *fi) {
  char path[PATH_MAX];
  int e = fullpath(name, path);
  if (e)
    return e;
  int fd = open(path, fi->flags & ~O_APPEND);
  if (fd < 0)
    return -errno;
  return remember(fd, name, fi);
}
static int q_create(const char *name, mode_t mode, struct fuse_file_info *fi) {
  (void)mode;
  char path[PATH_MAX];
  int e = fullpath(name, path);
  if (e)
    return e;
  struct stat st;
  if (lstat(path, &st) && errno == ENOENT) {
    e = case_collision(name, NULL);
    if (e)
      return e;
    e = new_entry(name);
    if (e)
      return e;
  }
  int fd = open(path, (fi->flags & ~O_APPEND) | O_CREAT, 0644);
  if (fd < 0)
    return -errno;
  return remember(fd, name, fi);
}
static int q_read(const char *name, char *buf, size_t size, off_t off,
                  struct fuse_file_info *fi) {
  (void)name;
  ssize_t n = pread(handle(fi)->fd, buf, size, off);
  return n < 0 ? -errno : (int)n;
}
static int q_write(const char *name, const char *buf, size_t size, off_t off,
                   struct fuse_file_info *fi) {
  (void)name;
  struct stat st;
  if (fstat(handle(fi)->fd, &st))
    return -errno;
  if (off < 0 || (uint64_t)off + size > INT64_MAX)
    return -EFBIG;
  uint64_t end = (uint64_t)off + size;
  if (end > (uint64_t)st.st_size) {
    int e = grow(end - st.st_size);
    if (e)
      return e;
  }
  ssize_t n = pwrite(handle(fi)->fd, buf, size, off);
  return n < 0 ? -errno : (int)n;
}
static int q_truncate(const char *name, off_t size, struct fuse_file_info *fi) {
  if (size < 0)
    return -EINVAL;
  char path[PATH_MAX];
  int e = 0;
  if (!fi || !fi->fh) {
    e = fullpath(name, path);
    if (e)
      return e;
  }
  struct stat st;
  if (fi && fi->fh)
    e = fstat(handle(fi)->fd, &st);
  else
    e = stat(path, &st);
  if (e)
    return -errno;
  if (size > st.st_size) {
    e = grow((uint64_t)(size - st.st_size));
    if (e)
      return e;
  }
  e = fi && fi->fh ? ftruncate(handle(fi)->fd, size) : truncate(path, size);
  return e ? -errno : 0;
}
static int q_release(const char *name, struct fuse_file_info *fi) {
  (void)name;
  struct handle *h = handle(fi), **p = &handles;
  while (*p && *p != h)
    p = &(*p)->next;
  if (*p)
    *p = h->next;
  close(h->fd);
  free(h->name);
  free(h);
  return 0;
}
static int q_fsync(const char *name, int data, struct fuse_file_info *fi) {
  (void)name;
  int r = data ? fdatasync(handle(fi)->fd) : fsync(handle(fi)->fd);
  return r ? -errno : 0;
}
static int q_mkdir(const char *name, mode_t mode) {
  (void)mode;
  char path[PATH_MAX];
  int e = fullpath(name, path);
  if (e)
    return e;
  if ((e = case_collision(name, NULL)))
    return e;
  if ((e = new_entry(name)))
    return e;
  return mkdir(path, 0755) ? -errno : 0;
}
static int q_unlink(const char *name) {
  char path[PATH_MAX];
  int e = fullpath(name, path);
  if (e)
    return e;
  return unlink(path) ? -errno : 0;
}
static int q_rmdir(const char *name) {
  char path[PATH_MAX];
  int e = fullpath(name, path);
  if (e)
    return e;
  return rmdir(path) ? -errno : 0;
}
static int q_rename(const char *from, const char *to, unsigned flags) {
  if (flags & ~1U)
    return -EINVAL;
  char a[PATH_MAX], b[PATH_MAX];
  int e = fullpath(from, a);
  if (e)
    return e;
  if ((e = fullpath(to, b)))
    return e;
  if ((e = case_collision(to, from)))
    return e;
  struct stat st;
  if ((flags & 1U) && !lstat(b, &st))
    return -EEXIST;
  struct usage old = used();
  uint64_t cost = 0;
  if (lstat(a, &st))
    return -errno;
  if (S_ISDIR(st.st_mode))
    return -ENOTSUP; /* No unchecked subtree path growth. */
  int64_t delta = (int64_t)strlen(to) - (int64_t)strlen(from);
  if (!lstat(b, &st)) {
    int open_target = 0;
    for (struct handle *h = handles; h; h = h->next) {
      struct stat other;
      if (!fstat(h->fd, &other) && other.st_ino == st.st_ino &&
          other.st_dev == st.st_dev) {
        open_target = 1;
        break;
      }
    }
    if (!open_target)
      delta -= strlen(to) + (S_ISREG(st.st_mode) ? st.st_size : 0);
  }
  if (delta > 0 && (cost = delta) && old.bytes + cost > capacity())
    return -ENOSPC;
  if (rename(a, b))
    return -errno;
  for (struct handle *h = handles; h; h = h->next)
    if (!strcmp(h->name, from)) {
      free(h->name);
      h->name = strdup(to);
    }
  return 0;
}
static int q_utimens(const char *name, const struct timespec times[2],
                     struct fuse_file_info *fi) {
  if (fi && fi->fh)
    return futimens(handle(fi)->fd, times) ? -errno : 0;
  char path[PATH_MAX];
  int e = fullpath(name, path);
  if (e)
    return e;
  return utimensat(AT_FDCWD, path, times, AT_SYMLINK_NOFOLLOW) ? -errno : 0;
}
static int q_statfs(const char *name, struct statvfs *s) {
  (void)name;
  struct usage u = used();
  memset(s, 0, sizeof *s);
  s->f_bsize = s->f_frsize = 1;
  s->f_blocks = capacity();
  s->f_bfree = s->f_bavail = u.bytes < s->f_blocks ? s->f_blocks - u.bytes : 0;
  s->f_files = 512;
  s->f_ffree = s->f_favail = u.entries < 512 ? 512 - u.entries : 0;
  s->f_namemax = 255;
  return 0;
}
static void *q_init(struct fuse_conn_info *conn, struct fuse_config *cfg) {
  (void)conn;
  cfg->entry_timeout = cfg->attr_timeout = cfg->negative_timeout = 0;
  cfg->kernel_cache = 0;
  cfg->hard_remove = 1;
  cfg->nullpath_ok = 1;
  if (write(ready_fd, "1", 1) < 0) {
  }
  close(ready_fd);
  return NULL;
}
static struct fuse_operations ops = {.init = q_init,
                                     .getattr = q_getattr,
                                     .readdir = q_readdir,
                                     .open = q_open,
                                     .create = q_create,
                                     .read = q_read,
                                     .write = q_write,
                                     .truncate = q_truncate,
                                     .release = q_release,
                                     .fsync = q_fsync,
                                     .mkdir = q_mkdir,
                                     .unlink = q_unlink,
                                     .rmdir = q_rmdir,
                                     .rename = q_rename,
                                     .utimens = q_utimens,
                                     .statfs = q_statfs};

int main(int argc, char **argv) {
  if (argc < 6) {
    fprintf(stderr, "quota-runner source limit mount -- command...\n");
    return 2;
  }
  source = argv[1];
  limit_file = argv[2];
  const char *mountpoint = argv[3];
  int pipefd[2];
  if (pipe(pipefd))
    return 1;
  pid_t daemon = fork();
  if (daemon < 0)
    return 1;
  if (!daemon) {
    close(pipefd[0]);
    ready_fd = pipefd[1];
    char *args[] = {"retro-disk", (char *)mountpoint,
                    "-f",         "-s",
                    "-o",         "default_permissions,fsname=retrocomputers",
                    NULL};
    int r = fuse_main(6, args, &ops, NULL);
    close(ready_fd);
    _exit(r);
  }
  close(pipefd[1]);
  char ready;
  ssize_t n = read(pipefd[0], &ready, 1);
  close(pipefd[0]);
  if (n != 1) {
    fprintf(stderr, "Unable to mount the computer disk\n");
    waitpid(daemon, NULL, 0);
    return 1;
  }
  pid_t child = fork();
  if (child < 0) {
    kill(daemon, SIGKILL);
    return 1;
  }
  if (!child) {
    execvp(argv[5], argv + 5);
    perror("computer process");
    _exit(127);
  }
  int status;
  while (waitpid(child, &status, 0) < 0 && errno == EINTR) {
  }
  umount2(mountpoint, MNT_DETACH);
  kill(daemon, SIGTERM);
  for (int i = 0; i < 50; i++) {
    if (waitpid(daemon, NULL, WNOHANG) == daemon)
      break;
    usleep(20000);
    if (i == 49) {
      kill(daemon, SIGKILL);
      waitpid(daemon, NULL, 0);
    }
  }
  return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}
