package io.github.retrocomputers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;
import net.neoforged.fml.loading.FMLPaths;

final class ComputerStorage {
    static Path base() {
        return FMLPaths.GAMEDIR.get().resolve("mods").resolve("retrocomputers").resolve("computers");
    }

    static String validName(String value) {
        String name = PortableFiles.key(value.strip());
        if (name.isEmpty() || name.length() > 32 || !PortableFiles.validComponent(name) || !name.codePoints().allMatch(c ->
                Character.isLetterOrDigit(c) || c == '_' || c == '-')) {
            throw new IllegalArgumentException("Use 1–32 letters, digits, _ or -; Windows device names are reserved");
        }
        return name;
    }

    static Path claim(String name, UUID identity) throws IOException {
        Path base = base().toAbsolutePath().normalize();
        Files.createDirectories(base);
        Path root = base.resolve(validName(name));
        if (Files.isSymbolicLink(root)) throw new IOException("Computer folder is a symbolic link");
        Files.createDirectories(root);
        Path owner = root.resolve(".computer-id");
        if (Files.isSymbolicLink(owner)) throw new IOException("Computer identity is a symbolic link");
        if (Files.exists(owner, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.readString(owner).strip().equals(identity.toString())) throw new IOException("Name belongs to another computer");
        } else {
            // Existing folders are never adopted silently.
            try (var entries = Files.list(root)) {
                if (entries.findAny().isPresent()) throw new IOException("Folder already contains files");
            }
            Files.writeString(owner, identity.toString(), java.nio.file.StandardOpenOption.CREATE_NEW);
        }
        return root.toRealPath();
    }

    static Path rename(Path previous, String name, UUID identity) throws IOException {
        Path next = base().toAbsolutePath().normalize().resolve(validName(name));
        if (previous.equals(next)) return previous;
        if (Files.exists(next, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Name already exists");
        if (!Files.readString(previous.resolve(".computer-id")).strip().equals(identity.toString()))
            throw new IOException("Computer identity mismatch");
        PortableFiles.rename(previous, next);
        return next.toRealPath();
    }
}
