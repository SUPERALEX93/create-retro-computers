package io.github.retrocomputers;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.Locale;
import java.util.StringJoiner;

/** Filesystem operations shared by terminal commands, the editor and Python, on any Java platform. */
final class PortableFiles {
    private PortableFiles() {}

    static String key(String name) {
        return Normalizer.normalize(Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT),
            Normalizer.Form.NFC);
    }

    static boolean validComponent(String name) {
        if (name.isEmpty() || name.endsWith(".") || name.endsWith(" ")) return false;
        if (!name.codePoints().allMatch(c -> Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.')) return false;
        String stem = key(name).split("\\.", 2)[0];
        // Win32 device names remain reserved even with an extension (e.g. NUL.txt).
        return !stem.matches("con|prn|aux|nul|com[1-9¹²³]|lpt[1-9¹²³]");
    }

    static String relative(Path root, Path file) {
        StringJoiner path = new StringJoiner("/", "/", "");
        for (Path segment : root.relativize(file)) path.add(segment.toString());
        return path.toString();
    }

    static Path checked(Path root, Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw new IOException("Path is outside the computer folder");
        Path relative = root.relativize(normalized);
        if (relative.getNameCount() == 1 && key(relative.toString()).equals(".computer-id"))
            throw new IOException("Computer identity is not a user file");
        if (Files.isSymbolicLink(root)) throw new IOException("Symbolic links are not supported");
        Path current = root;
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic links are not supported");
            // Windows junctions are not necessarily reported as symbolic links.
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && !current.toRealPath().startsWith(root))
                throw new IOException("Path is outside the computer folder");
        }
        return normalized;
    }

    static void replace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException unsupported) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void rename(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, destination);
        }
    }
}
