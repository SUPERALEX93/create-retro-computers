package io.github.retrocomputers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Small FUSE service shipped separately from the immutable compiler bundle. */
final class QuotaRuntime {
    static void prepare(Path folder) throws IOException {
        Files.createDirectories(folder.resolve("etc"));
        try (var listing = QuotaRuntime.class.getResourceAsStream("/retrocomputers/native/quota/files.txt")) {
            if (listing == null) throw new IOException("Quota filesystem is missing");
            for (String name : new String(listing.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                if (name.isBlank() || name.contains("/") || name.contains("..")) continue;
                try (var resource = QuotaRuntime.class.getResourceAsStream("/retrocomputers/native/quota/" + name)) {
                    if (resource == null) throw new IOException("Quota filesystem is missing: " + name);
                    Path target = folder.resolve(name);
                    Files.copy(resource, target, StandardCopyOption.REPLACE_EXISTING);
                    target.toFile().setExecutable(true, true);
                }
            }
        }
        Files.writeString(folder.resolve("etc/passwd"), "root:x:0:0:Retro computer:/tmp:/bin/sh\n");
        Files.writeString(folder.resolve("etc/group"), "root:x:0:\n");
    }

    static void limit(Path control, int capacity) throws IOException {
        Path temporary = control.resolve("limit.tmp");
        Files.writeString(temporary, Integer.toString(Math.max(0, capacity)));
        Files.move(temporary, control.resolve("limit"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
