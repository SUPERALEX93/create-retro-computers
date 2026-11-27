package io.github.retrocomputers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipInputStream;

/** Offline, per-instance installation. Never invokes a host package manager or compiler. */
final class NativeToolchains {
    private static final String RESOURCE = "/retrocomputers/native/runtime/";
    private static Path base = Path.of(System.getProperty("retrocomputers.toolchainDir", "mods/retrocomputers/toolchains")).toAbsolutePath().normalize();
    private static JsonObject metadata;
    private static CompletableFuture<Void> installation;
    private static volatile int progress;
    private static volatile String failure = "";

    static synchronized void configure(Path directory) {
        Path configured = Path.of(System.getProperty("retrocomputers.toolchainDir", directory.toString())).toAbsolutePath().normalize();
        if (!base.equals(configured)) { base = configured; installation = null; progress = 0; failure = ""; }
    }

    private static synchronized JsonObject metadata() throws IOException {
        if (metadata != null) return metadata;
        try (var stream = NativeToolchains.class.getResourceAsStream(RESOURCE + "bundle.json")) {
            if (stream == null) throw new IOException("Bundled compiler archive is missing");
            metadata = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            return metadata;
        }
    }

    static Path root() throws IOException { return base.resolve(metadata().get("id").getAsString()).resolve("root"); }
    static Path path(String absolute) throws IOException { return root().resolve(absolute.replaceFirst("^/", "")); }
    static String inside(Path path) throws IOException { return "/" + root().relativize(path).toString().replace(java.io.File.separatorChar, '/'); }

    static boolean ready() {
        if (!supported()) return false;
        try {
            return Files.isRegularFile(root().getParent().resolve(".complete"), LinkOption.NOFOLLOW_LINKS)
                && Files.isExecutable(path("/usr/bin/bwrap")) && Files.isExecutable(path("/jdk/bin/javac"))
                && Files.isExecutable(path("/rust/bin/rustc"));
        } catch (IOException error) { return false; }
    }

    static String status() {
        if (!supported()) return "Встроенный набор доступен для Linux x86_64";
        if (!failure.isEmpty()) return failure;
        return ready() ? "" : "Распаковка встроенных языков: " + progress + "%";
    }

    static boolean supported() {
        String arch = System.getProperty("os.arch");
        return System.getProperty("os.name").equals("Linux") && (arch.equals("amd64") || arch.equals("x86_64"));
    }

    static synchronized CompletableFuture<Void> start() {
        if (!supported()) return CompletableFuture.completedFuture(null);
        if (installation == null) installation = CompletableFuture.runAsync(() -> {
            try { install(); }
            catch (Exception error) {
                failure = String.valueOf(error.getMessage());
                System.getLogger("RetroComputersLanguages").log(System.Logger.Level.ERROR, "Cannot unpack bundled languages", error);
                throw new java.util.concurrent.CompletionException(error);
            }
        });
        return installation;
    }

    static void await(BooleanSupplier cancelled) throws IOException {
        if (!supported()) throw new IOException(status());
        var future = start();
        while (true) {
            if (cancelled.getAsBoolean()) throw new IOException("Program stopped");
            try { future.get(200, TimeUnit.MILLISECONDS); return; }
            catch (java.util.concurrent.TimeoutException waiting) { }
            catch (Exception error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                throw new IOException(cause.getMessage(), cause);
            }
        }
    }

    private static void install() throws IOException {
        if (!supported()) throw new IOException(status());
        if (ready()) { progress = 100; return; }
        JsonObject info = metadata();
        Files.createDirectories(base);
        if (Files.isSymbolicLink(base)) throw new IOException("Invalid toolchain folder");
        long bytes = info.get("bytes").getAsLong();
        if (Files.getFileStore(base).getUsableSpace() < bytes + 64 * 1024 * 1024L)
            throw new IOException("Для распаковки языков нужно свободное место: " + (bytes / 1024 / 1024 + 64) + " МиБ");
        Path destination = root().getParent();
        Path staging = base.resolve(".install-" + UUID.randomUUID());
        Path root = staging.resolve("root");
        Files.createDirectories(root);
        int count = 0;
        long extracted = 0;
        try {
            try (var resource = NativeToolchains.class.getResourceAsStream(RESOURCE + "rootfs.zip")) {
                if (resource == null) throw new IOException("Bundled compiler archive is missing");
                try (var zip = new ZipInputStream(resource)) {
                    java.util.zip.ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        Path target = checked(root, entry.getName());
                        if (entry.isDirectory()) { Files.createDirectories(target); continue; }
                        Files.createDirectories(target.getParent());
                        try (var out = Files.newOutputStream(target)) {
                            byte[] buffer = new byte[65536];
                            int size;
                            while ((size = zip.read(buffer)) >= 0) {
                                extracted += size;
                                if (extracted > bytes) throw new IOException("Invalid bundled compiler size");
                                out.write(buffer, 0, size);
                            }
                        }
                        count++;
                        progress = (int) Math.min(99, extracted * 100 / Math.max(1, bytes));
                    }
                }
            }
            if (count != info.get("files").getAsInt() || extracted != bytes) throw new IOException("Incomplete compiler archive");
            for (var directory : info.getAsJsonArray("directories")) Files.createDirectories(checked(root, directory.getAsString()));
            for (var executable : info.getAsJsonArray("executables")) {
                if (!checked(root, executable.getAsString()).toFile().setExecutable(true, false)) throw new IOException("Cannot set executable permission");
            }
            for (var entry : info.getAsJsonObject("links").entrySet()) {
                Path link = checked(root, entry.getKey());
                Path value = Path.of(entry.getValue().getAsString());
                // An absolute guest path must stay relative to the extracted root on the host.
                if (value.isAbsolute()) value = link.getParent().relativize(checked(root, value.toString().substring(1)));
                if (!link.getParent().resolve(value).normalize().startsWith(root)) throw new IOException("Invalid compiler link");
                Files.createDirectories(link.getParent());
                Files.createSymbolicLink(link, value);
            }
            Files.writeString(staging.resolve(".complete"), info.get("id").getAsString(), StandardCharsets.UTF_8);
            if (Files.exists(destination)) remove(destination);
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
            progress = 100;
            System.getLogger("RetroComputersLanguages").log(System.Logger.Level.INFO, "Bundled languages installed: " + destination);
        } finally { if (Files.exists(staging)) remove(staging); }
    }

    private static Path checked(Path root, String entry) throws IOException {
        Path target = root.resolve(entry).normalize();
        if (!target.startsWith(root) || target.equals(root)) throw new IOException("Invalid compiler archive path");
        return target;
    }

    private static void remove(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
