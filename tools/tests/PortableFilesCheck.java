package io.github.retrocomputers;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

/** Runs directly with Java 21, without Minecraft or native Linux tools. */
public final class PortableFilesCheck {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void denied(Path root, Path path) throws IOException {
        try { PortableFiles.checked(root, path); }
        catch (IOException expected) { return; }
        throw new AssertionError("Path should be rejected: " + path);
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("retro-portability-").toRealPath();
        try {
            Path root = Files.createDirectory(temporary.resolve("computer"));
            Files.writeString(root.resolve(".computer-id"), "identity");
            Path notes = Files.createDirectory(root.resolve("notes"));
            Path file = notes.resolve("résumé.txt");
            Files.writeString(file, "old");
            Path update = Files.createTempFile(notes, ".writing-", ".tmp");
            Files.writeString(update, "Привет\nsecond line\n");
            PortableFiles.replace(update, file);
            check(Files.readString(file).equals("Привет\nsecond line\n"), "UTF-8/line endings changed");
            check(!Files.exists(update), "Temporary file leaked");
            check(PortableFiles.relative(root, file).equals("/notes/résumé.txt"), "Host separator leaked into virtual path");
            check(PortableFiles.key("RE\u0301SUME\u0301.TXT").equals("résumé.txt"), "Unicode/case mismatch");
            check(PortableFiles.checked(root, file).equals(file), "Valid path rejected");
            denied(root, root.resolve("../outside.txt"));
            denied(root, root.resolve(".COMPUTER-ID"));
            denied(root, root.resolve(".computer-id"));
            for (String name : new String[]{"CON", "nul.txt", "Aux.PY", "PRN", "COM1", "lpt9.log", "file.", "bad ", "C:drive", "a/b", "a\\b"})
                check(!PortableFiles.validComponent(name), "Non-portable name accepted: " + name);
            for (String name : new String[]{"comp1", "console", "com10.txt", "program.py", "заметки", "résumé.txt"})
                check(PortableFiles.validComponent(name), "Portable name rejected: " + name);

            try {
                Files.createSymbolicLink(root.resolve("escape"), temporary);
                denied(root, root.resolve("escape/outside.txt"));
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                System.out.println("Symlink creation unavailable on this host: " + unavailable.getClass().getSimpleName());
            }

            Path renamed = temporary.resolve("renamed");
            PortableFiles.rename(root, renamed);
            check(Files.readString(renamed.resolve(".computer-id")).equals("identity"), "Computer identity lost on rename");
            check(Files.readString(renamed.resolve("notes/résumé.txt")).startsWith("Привет"), "Computer files lost on rename");

            // The ZIP provider forces a real cross-provider ATOMIC_MOVE fallback.
            Path archive = temporary.resolve("fallback.zip");
            try (var zip = FileSystems.newFileSystem(URI.create("jar:" + archive.toUri()), Map.of("create", "true"))) {
                Path source = temporary.resolve("fallback.txt");
                Files.writeString(source, "replacement");
                Path destination = zip.getPath("/saved.txt");
                Files.writeString(destination, "original");
                PortableFiles.replace(source, destination);
                check(Files.readString(destination).equals("replacement") && !Files.exists(source), "Atomic replacement fallback failed");
                Path directory = Files.createDirectory(temporary.resolve("fallback-directory"));
                PortableFiles.rename(directory, zip.getPath("/new-directory"));
                check(Files.isDirectory(zip.getPath("/new-directory")) && !Files.exists(directory), "Directory rename fallback failed");
            }
            System.out.println("PASS: " + System.getProperty("os.name") + " — UTF-8, portable names, paths, disk writes, identity, rename, atomic-move fallback");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
