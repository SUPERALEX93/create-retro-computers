package io.github.retrocomputers;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

final class ComputerFiles {
    private static final int MAX_ENTRIES = 512;
    private final NavigableMap<String, Entry> entries = new TreeMap<>();
    private String directory = "/";
    private Path root;
    private final Map<String, Path> physical = new TreeMap<>();

    Path root() { return root; }

    void mount(Path folder) throws IOException {
        root = folder;
        // Migrate saves from versions that kept files in block entity NBT.
        for (var entry : entries.entrySet()) {
            Path destination = checked(folder.resolve(entry.getKey().substring(1)));
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) continue;
            if (entry.getValue().folder()) Files.createDirectories(destination);
            else {
                Files.createDirectories(destination.getParent());
                Files.writeString(destination, entry.getValue().text(), StandardOpenOption.CREATE_NEW);
            }
        }
        refreshDisk();
    }

    private void refreshDisk() throws IOException {
        if (root == null) return;
        NavigableMap<String, Entry> fresh = new TreeMap<>();
        Map<String, Path> paths = new TreeMap<>();
        try (var walk = Files.walk(root, 17)) {
            for (Path file : walk.filter(path -> !path.equals(root)).toList()) {
                if (file.getFileName().toString().equals(".computer-id")) continue;
                checked(file);
                String path = "/" + root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                String key = path.toLowerCase(Locale.ROOT);
                if (fresh.containsKey(key)) throw new IOException("File names differ only by case: " + path);
                boolean folder = Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS);
                long size = folder ? 0 : Files.size(file);
                boolean binary = !folder && (size > 1024 * 1024 || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS));
                String text = "";
                if (!folder && !binary) {
                    try { text = Files.readString(file); }
                    catch (java.nio.charset.CharacterCodingException notText) { binary = true; }
                }
                fresh.put(key, new Entry(folder, text, size, binary));
                paths.put(key, file);
                if (fresh.size() > 4096) throw new IOException("Too many external files");
            }
        }
        entries.clear();
        entries.putAll(fresh);
        physical.clear();
        physical.putAll(paths);
        if (!folder(directory)) directory = "/";
    }

    private Path checked(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root.resolve(".computer-id")))
            throw new IOException("Path is outside the computer folder");
        Path current = root;
        if (Files.isSymbolicLink(current)) throw new IOException("Symbolic links are not supported");
        for (Path part : root.relativize(normalized)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic links are not supported");
        }
        return normalized;
    }

    private Path diskPath(String path) throws IOException {
        Path existing = physical.get(path);
        if (existing != null) return checked(existing);
        Path parent = physical.getOrDefault(parent(path), root);
        return checked(parent.resolve(path.substring(path.lastIndexOf('/') + 1)));
    }

    String readText(String path) throws IOException {
        refreshDisk();
        String key = resolve(path);
        if (!exists(key) || folder(key)) throw new IOException("File not found: " + path);
        if (entries.get(key).binary()) throw new IOException(message("binary").getString());
        return entries.get(key).text();
    }

    Path runtimeSource(String path) throws IOException {
        refreshDisk();
        String key = resolve(path);
        if (!exists(key) || folder(key)) throw new IOException("File not found: " + path);
        return diskPath(key);
    }

    void writeText(String path, String text, int capacity) throws IOException {
        refreshDisk();
        try { put(resolve(path), new Entry(false, text), capacity); }
        catch (FileError error) { throw new IOException(message(error.key).getString()); }
    }

    boolean fileExists(String path) throws IOException {
        refreshDisk();
        return exists(resolve(path));
    }

    List<String> names(String path) throws IOException {
        refreshDisk();
        String key = resolve(path);
        if (!folder(key)) throw new IOException("Directory not found");
        return entries.keySet().stream().filter(entry -> parent(entry).equals(key))
            .map(entry -> entry.substring(entry.lastIndexOf('/') + 1)).toList();
    }

    void mkdir(String path, int capacity) throws IOException {
        refreshDisk();
        try {
            String key = resolve(path);
            require(!exists(key), "exists");
            put(key, new Entry(true, ""), capacity);
        } catch (FileError error) { throw new IOException(message(error.key).getString()); }
    }

    private record Entry(boolean folder, String text, long size, boolean binary) {
        Entry(boolean folder, String text) { this(folder, text, folder ? 0 : bytes(text), false); }
    }
    private static final class FileError extends RuntimeException {
        final String key;
        FileError(String key) { this.key = key; }
    }

    String directory() { return directory; }

    long used() {
        return entries.entrySet().stream().mapToLong(entry -> pathSize(entry.getKey()) + entry.getValue().size()).sum();
    }

    private int pathSize(String path) {
        Path actual = physical.get(path);
        return actual == null || root == null ? bytes(path) : bytes("/" + root.relativize(actual).toString().replace(java.io.File.separatorChar, '/'));
    }

    Map<String, Object> statistics(int capacity) throws IOException {
        refreshDisk();
        long used = used();
        return Map.of("capacity", capacity, "used", used, "free", Math.max(0, capacity - used),
            "entries", entries.size(), "entry_limit", MAX_ENTRIES);
    }

    List<Component> execute(String command, String arguments, int capacity) {
        try {
            refreshDisk();
            String[] parts = arguments.isBlank() ? new String[0] : arguments.stripLeading().split("\s+", 2);
            String path = parts.length == 0 ? directory : resolve(parts[0]);
            return switch (command) {
                case "PWD" -> { require(arguments.isBlank(), "usage_pwd"); yield List.of(Component.literal(directory)); }
                case "CD" -> {
                    require(parts.length <= 1, "usage_cd");
                    require(folder(path), "not_directory");
                    directory = path;
                    yield List.of(Component.literal(directory));
                }
                case "DIR", "LS" -> { require(parts.length <= 1, "usage_dir"); yield listing(path); }
                case "TREE" -> { require(parts.length <= 1, "usage_tree"); yield tree(path); }
                case "MKDIR" -> {
                    require(parts.length == 1, "usage_mkdir");
                    require(!exists(path), "exists");
                    put(path, new Entry(true, ""), capacity);
                    yield ok();
                }
                case "TOUCH" -> {
                    require(parts.length == 1, "usage_touch");
                    if (!exists(path)) put(path, new Entry(false, ""), capacity);
                    else require(!folder(path), "is_directory");
                    yield ok();
                }
                case "WRITE", "APPEND" -> {
                    require(parts.length > 0, command.equals("WRITE") ? "usage_write" : "usage_append");
                    require(!folder(path), "is_directory");
                    String text = parts.length == 2 ? unescape(parts[1]) : "";
                    if (command.equals("APPEND") && exists(path)) {
                        require(!entries.get(path).binary(), "binary");
                        text = entries.get(path).text() + text;
                    }
                    put(path, new Entry(false, text), capacity);
                    yield List.of(message("written", bytes(text)));
                }
                case "CAT", "TYPE" -> {
                    require(parts.length == 1, "usage_cat");
                    require(exists(path), "missing");
                    require(!folder(path), "is_directory");
                    require(!entries.get(path).binary(), "binary");
                    yield entries.get(path).text().lines().map(Component::literal).map(Component.class::cast).toList();
                }
                case "RM", "DEL" -> {
                    require(parts.length == 1, "usage_rm");
                    require(exists(path), "missing");
                    require(!folder(path), "is_directory");
                    if (root != null) Files.delete(diskPath(path));
                    entries.remove(path);
                    yield ok();
                }
                case "RMDIR" -> {
                    require(parts.length == 1, "usage_rmdir");
                    require(!path.equals("/"), "root");
                    require(folder(path), "not_directory");
                    require(entries.keySet().stream().noneMatch(key -> key.startsWith(path + "/")), "not_empty");
                    require(!directory.equals(path) && !directory.startsWith(path + "/"), "current_directory");
                    if (root != null) Files.delete(diskPath(path));
                    entries.remove(path);
                    yield ok();
                }
                case "DF" -> {
                    require(arguments.isBlank(), "usage_df");
                    long used = used();
                    yield List.of(message("capacity", capacity, capacity / 1024), message("used", used),
                        message("free", Math.max(0, capacity - used)), message("entries", entries.size(), MAX_ENTRIES),
                        used > capacity ? message("over_capacity", used - capacity) : message("quota"));
                }
                default -> List.of(TerminalOutput.message("unknown"));
            };
        } catch (FileError error) {
            return List.of(message(error.key));
        } catch (IOException error) {
            return List.of(message("io_error", error.getMessage()));
        }
    }

    private List<Component> listing(String path) {
        require(folder(path), "not_directory");
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(path));
        for (var entry : entries.entrySet()) {
            if (!parent(entry.getKey()).equals(path)) continue;
            String name = entry.getKey().substring(entry.getKey().lastIndexOf('/') + 1);
            lines.add(entry.getValue().folder() ? message("directory", name)
                : message("file", name, entry.getValue().size()));
        }
        if (lines.size() == 1) lines.add(message("empty"));
        return lines;
    }

    private List<Component> tree(String path) {
        require(folder(path), "not_directory");
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(path));
        appendTree(path, 0, lines);
        return lines;
    }

    private void appendTree(String directory, int depth, List<Component> lines) {
        for (var entry : entries.entrySet()) {
            if (!parent(entry.getKey()).equals(directory)) continue;
            String name = entry.getKey().substring(entry.getKey().lastIndexOf('/') + 1);
            lines.add(Component.literal("  ".repeat(depth) + "+- " + name + (entry.getValue().folder() ? "/" : "")));
            if (entry.getValue().folder()) appendTree(entry.getKey(), depth + 1, lines);
        }
    }

    private String resolve(String argument) {
        require(!argument.isEmpty(), "bad_path");
        List<String> segments = new ArrayList<>();
        String combined = argument.startsWith("/") ? argument : directory + "/" + argument;
        for (String segment : combined.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                require(!segments.isEmpty(), "root");
                segments.removeLast();
            } else {
                require(segment.length() <= 48 && segment.codePoints().allMatch(c ->
                    Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.'), "bad_path");
                segments.add(segment.toLowerCase(Locale.ROOT));
            }
        }
        String path = "/" + String.join("/", segments);
        require(segments.size() <= 16 && path.length() <= 256, "bad_path");
        return path;
    }

    private void put(String path, Entry entry, int capacity) throws IOException {
        require(!path.equals("/"), "root");
        require(folder(parent(path)), "parent_missing");
        Entry old = entries.get(path);
        require(old == null || !old.folder(), "is_directory");
        require(old != null || entries.size() < MAX_ENTRIES, "entry_limit");
        int nameSize = root == null ? bytes(path) : bytes("/" + root.relativize(diskPath(path)).toString().replace(java.io.File.separatorChar, '/'));
        long total = used() + (old == null ? nameSize : -old.size()) + entry.size();
        require(total <= capacity || old != null && total <= used(), "full");
        if (root != null) {
            Path destination = diskPath(path);
            if (entry.folder()) Files.createDirectory(destination);
            else {
                Path temporary = Files.createTempFile(destination.getParent(), ".writing-", ".tmp");
                try {
                    Files.writeString(temporary, entry.text());
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(temporary); }
            }
            physical.put(path, destination);
        }
        entries.put(path, entry);
    }

    private boolean exists(String path) { return path.equals("/") || entries.containsKey(path); }
    private boolean folder(String path) { return path.equals("/") || entries.containsKey(path) && entries.get(path).folder(); }
    private static String parent(String path) {
        int split = path.lastIndexOf('/');
        return split <= 0 ? "/" : path.substring(0, split);
    }
    private static int bytes(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
    private static void require(boolean condition, String error) { if (!condition) throw new FileError(error); }
    private static Component message(String key, Object... args) {
        return Component.translatable("retrocomputers.files." + key, args);
    }
    private static List<Component> ok() { return List.of(message("ok")); }

    private static String unescape(String text) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(++i);
                if (next == 'n') result.append('\n');
                else if (next == 't') result.append('\t');
                else if (next == '\\') result.append('\\');
                else result.append('\\').append(next);
            } else result.append(c);
        }
        return result.toString();
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Directory", directory);
        if (root != null) {
            tag.putBoolean("External", true);
            return tag;
        }
        ListTag data = new ListTag();
        entries.forEach((path, entry) -> {
            CompoundTag item = new CompoundTag();
            item.putString("Path", path);
            item.putBoolean("Folder", entry.folder());
            if (!entry.folder()) item.putString("Text", entry.text());
            data.add(item);
        });
        tag.put("Entries", data);
        return tag;
    }

    void load(CompoundTag tag) {
        root = null;
        physical.clear();
        entries.clear();
        directory = "/";
        ListTag data = tag.getList("Entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < data.size(); i++) {
            CompoundTag item = data.getCompound(i);
            entries.put(item.getString("Path"), new Entry(item.getBoolean("Folder"), item.getString("Text")));
        }
        String savedDirectory = tag.getString("Directory");
        if (!savedDirectory.isEmpty() && (tag.getBoolean("External") || folder(savedDirectory))) directory = savedDirectory;
    }
}
