package io.github.retrocomputers;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Bundled toolchains in a private filesystem/network namespace, outside the game tick thread. */
final class NativePrograms {
    interface Host {
        boolean cancelled();
        void process(Process process, boolean interactive) throws Exception;
        void output(String text) throws Exception;
        default String api(String operation, List<String> arguments) throws Exception { throw new IOException("Computer API unavailable"); }
        default int capacity() { return 16384; }
    }

    static String language(String file) {
        String name = file.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return switch (dot < 0 ? "" : name.substring(dot + 1)) {
            case "lua" -> "Lua";
            case "c" -> "C";
            case "cpp", "cc", "cxx" -> "C++";
            case "cs" -> "C#";
            case "java" -> "Java";
            case "rs" -> "Rust";
            default -> "";
        };
    }

    static String availability(String language) {
        try {
            if (!NativeToolchains.ready()) { NativeToolchains.start(); return NativeToolchains.status(); }
            if (!Files.exists(Path.of("/dev/fuse"))) return "Linux /dev/fuse";
            switch (language) {
                case "Lua" -> require(NativeToolchains.path("/usr/bin/lua"));
                case "C" -> require(NativeToolchains.path("/usr/bin/gcc"));
                case "C++" -> require(NativeToolchains.path("/usr/bin/g++"));
                case "Java" -> require(NativeToolchains.path("/jdk/bin/javac"));
                case "Rust" -> rustHome();
                case "C#" -> { dotnetSdk(); dotnetReferences(); }
                default -> throw new IOException("Unknown language");
            }
            return "";
        } catch (IOException error) { return error.getMessage(); }
    }

    static void run(String language, Path root, Path source, String text, String directory, Host host) throws Exception {
        if (!NativeToolchains.ready()) host.output("Подготовка встроенных языков...\n");
        NativeToolchains.await(host::cancelled);
        if (!Files.exists(Path.of("/dev/fuse"))) throw new IOException("Для диска ПК требуется устройство Linux /dev/fuse");
        String missing = availability(language);
        if (!missing.isEmpty()) throw new IOException(language + ": " + missing);
        Path build = Files.createTempDirectory("retro-program-");
        Path control = Files.createTempDirectory("retro-disk-control-");
        Path trusted = Files.createTempDirectory("retro-quota-runtime-");
        var bridgeClosed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.CompletableFuture<Void> bridge = null;
        try {
            QuotaRuntime.prepare(trusted);
            Files.createDirectories(build.resolve(".base"));
            Files.createDirectories(build.resolve(".quota"));
            QuotaRuntime.limit(control, host.capacity());
            try (var script = NativePrograms.class.getResourceAsStream("/retrocomputers/native/computer")) {
                if (script == null) throw new IOException("Native computer bridge missing");
                Files.copy(script, build.resolve("computer"));
                build.resolve("computer").toFile().setExecutable(true, true);
            }
            bridge = java.util.concurrent.CompletableFuture.runAsync(() -> bridge(build, control, host, bridgeClosed));
            String file = "/work/" + root.relativize(source).toString();
            List<String> compile = null;
            List<String> execute;
            switch (language) {
                case "Lua" -> execute = List.of("/usr/bin/lua", "-e", "io.stdout:setvbuf('no')", file);
                case "C", "C++" -> {
                    compile = List.of(language.equals("C") ? "/usr/bin/gcc" : "/usr/bin/g++",
                        language.equals("C") ? "-std=c17" : "-std=c++20", "-O0", "-I", "/work" + directory,
                        file, "-lm", "-o", "/build/program");
                    execute = List.of("/usr/bin/stdbuf", "-o0", "-e0", "/build/program");
                }
                case "Java" -> {
                    var type = Pattern.compile("\\b(?:class|record|enum)\\s+([A-Za-z_$][\\w$]*)").matcher(text);
                    if (!type.find()) throw new IOException("Java: class Main with a main method is required");
                    String name = type.group(1);
                    Files.writeString(build.resolve(name + ".java"), text);
                    compile = List.of("/jdk/bin/javac", "-encoding", "UTF-8", "-d", "/build/classes", "/build/" + name + ".java");
                    var pkg = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(text);
                    execute = List.of("/jdk/bin/java", "-Xmx256m", "-cp", "/build/classes", (pkg.find() ? pkg.group(1) + "." : "") + name);
                }
                case "Rust" -> {
                    compile = List.of("/rust/bin/rustc", "--edition=2021", "--crate-name", "retro_program", file, "-o", "/build/program");
                    execute = List.of("/build/program");
                }
                case "C#" -> {
                    Path references = dotnetReferences();
                    String version = references.getParent().getParent().getFileName().toString();
                    Files.writeString(build.resolve("program.runtimeconfig.json"),
                        "{\"runtimeOptions\":{\"tfm\":\"net" + version.split("\\.")[0] + ".0\",\"framework\":{\"name\":\"Microsoft.NETCore.App\",\"version\":\"" + version + "\"}}}");
                    compile = new ArrayList<>(List.of("/usr/bin/dotnet", NativeToolchains.inside(dotnetSdk().resolve("Roslyn/bincore/csc.dll")),
                        "-nologo", "-target:exe", "-out:/build/program.dll"));
                    try (var files = Files.list(references)) {
                        for (Path reference : files.filter(p -> p.toString().endsWith(".dll")).sorted().toList())
                            compile.add("-reference:" + NativeToolchains.inside(reference));
                    }
                    compile.add(file);
                    execute = List.of("/usr/bin/dotnet", "/build/program.dll");
                }
                default -> throw new IOException("Unknown language");
            }
            if (compile != null) {
                host.output(language + ": compiling...\n");
                int result = launch(root, build, control, trusted, directory, language, compile, false, host);
                if (result != 0) throw new IOException("Compiler exit code " + result);
            }
            if (!host.cancelled()) {
                int result = launch(root, build, control, trusted, directory, language, execute, true, host);
                if (result != 0 && !host.cancelled()) throw new IOException("Program exit code " + result);
            }
        } finally {
            bridgeClosed.set(true);
            if (bridge != null) try { bridge.get(12, TimeUnit.SECONDS); } catch (Exception ignored) { }
            try (var files = Files.walk(build)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
            try (var files = Files.walk(control)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
            try (var files = Files.walk(trusted)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static int launch(Path root, Path build, Path control, Path trusted, String directory, String language, List<String> command, boolean interactive, Host host) throws Exception {
        if (host.cancelled()) return -1;
        // Start even bubblewrap through the bundled dynamic loader: the host needs no matching libraries.
        List<String> args = new ArrayList<>(List.of(NativeToolchains.path("/usr/lib/ld-linux-x86-64.so.2").toString(),
            "--library-path", NativeToolchains.path("/usr/lib").toString(), NativeToolchains.path("/usr/bin/bwrap").toString(),
            "--unshare-all", "--uid", "0", "--gid", "0", "--die-with-parent", "--new-session", "--ro-bind", NativeToolchains.root().toString(), "/",
            "--proc", "/proc", "--dev", "/dev", "--dev-bind", "/dev/fuse", "/dev/fuse", "--tmpfs", "/tmp", "--tmpfs", "/work",
            "--bind", root.toString(), "/tmp/source", "--ro-bind", control.toString(), "/tmp/control",
            "--bind", build.toString(), "/build", "--ro-bind", NativeToolchains.root().toString(), "/build/.base",
            "--ro-bind", trusted.toString(), "/build/.quota", "--ro-bind", trusted.resolve("etc").toString(), "/etc",
            "--cap-add", "CAP_SYS_ADMIN", "--cap-add", "CAP_SETUID", "--cap-add", "CAP_SETGID", "--cap-add", "CAP_SETFCAP",
            "--clearenv", "--setenv", "PATH", "/build/.quota:/build:/usr/bin:/bin", "--setenv", "HOME", "/tmp",
            "--", "/build/.quota/quota-runner", "/tmp/source", "/tmp/control/limit", "/work", "--",
            "/usr/bin/bwrap", "--unshare-all", "--uid", "0", "--gid", "0", "--die-with-parent", "--new-session",
            "--ro-bind", "/build/.base", "/", "--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp",
            "--bind", "/work", "/work", "--bind", "/build", "/build", "--ro-bind", "/build/.quota/etc", "/etc", "--cap-drop", "ALL",
            "--clearenv", "--setenv", "PATH", "/build:/usr/bin:/bin",
            "--setenv", "HOME", "/tmp", "--setenv", "LANG", "C.UTF-8",
            "--setenv", "DOTNET_CLI_HOME", "/tmp/dotnet", "--setenv", "DOTNET_SKIP_FIRST_TIME_EXPERIENCE", "1",
            "--setenv", "DOTNET_CLI_TELEMETRY_OPTOUT", "1", "--setenv", "DOTNET_GCHeapHardLimit", "0x20000000"));
        args.addAll(List.of("--chdir", "/work" + (directory.equals("/") ? "" : directory), "--"));
        args.addAll(command);
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        try {
            host.process(process, interactive);
            var timeout = interactive ? null : java.util.concurrent.CompletableFuture.delayedExecutor(60, TimeUnit.SECONDS);
            if (timeout != null) timeout.execute(() -> { if (process.isAlive()) process.destroyForcibly(); });
            try (var out = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[1024];
                int count;
                while ((count = out.read(buffer)) >= 0 && !host.cancelled()) host.output(new String(buffer, 0, count));
            }
            return process.waitFor();
        } finally { process.destroyForcibly(); }
    }

    private static void bridge(Path build, Path control, Host host, java.util.concurrent.atomic.AtomicBoolean closed) {
        var decoder = java.util.Base64.getDecoder();
        var encoder = java.util.Base64.getEncoder();
        Path request = build.resolve(".rpc-request");
        int capacity = -1;
        while (!closed.get() && !host.cancelled()) {
            try {
                int nextCapacity = host.capacity();
                if (nextCapacity != capacity) { QuotaRuntime.limit(control, nextCapacity); capacity = nextCapacity; }
                if (!Files.exists(request)) { Thread.sleep(10); continue; }
                String result;
                try {
                    if (Files.size(request) > 16384) throw new IOException("Request too large");
                    List<String> lines = Files.readAllLines(request);
                    String operation = lines.getFirst();
                    if (!List.of("name", "ports", "read", "set", "disk").contains(operation)) throw new IOException("Unknown computer command");
                    var arguments = new ArrayList<String>();
                    for (int i = 1; i < lines.size(); i++) arguments.add(new String(decoder.decode(lines.get(i)), StandardCharsets.UTF_8));
                    result = "OK\t" + encoder.encodeToString(host.api(operation, arguments).getBytes(StandardCharsets.UTF_8));
                } catch (Exception error) {
                    result = "ERR\t" + encoder.encodeToString(String.valueOf(error.getMessage()).getBytes(StandardCharsets.UTF_8));
                }
                Files.deleteIfExists(request);
                Path pending = build.resolve(".rpc-pending");
                Files.writeString(pending, result);
                Files.move(pending, build.resolve(".rpc-response"), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception error) {
                if (error instanceof InterruptedException) { Thread.currentThread().interrupt(); return; }
                if (!closed.get()) return;
            }
        }
    }

    private static void require(Path executable) throws IOException {
        if (!Files.isExecutable(executable)) throw new IOException("Missing " + executable.getFileName());
    }

    private static Path rustHome() throws IOException {
        Path home = NativeToolchains.path("/rust");
        require(home.resolve("bin/rustc"));
        return home;
    }

    private static Path latest(Path root) throws IOException {
        if (!Files.isDirectory(root)) throw new IOException("Missing " + root);
        try (var paths = Files.list(root)) {
            return paths.filter(Files::isDirectory).max(Comparator.comparing(p -> p.getFileName().toString())).orElseThrow(() -> new IOException("Empty " + root));
        }
    }

    private static Path dotnetSdk() throws IOException {
        Path sdk = latest(NativeToolchains.path("/usr/share/dotnet/sdk"));
        if (!Files.exists(sdk.resolve("Roslyn/bincore/csc.dll"))) throw new IOException("Missing .NET SDK");
        return sdk;
    }

    private static Path dotnetReferences() throws IOException {
        return latest(latest(NativeToolchains.path("/usr/share/dotnet/packs/Microsoft.NETCore.App.Ref")).resolve("ref"));
    }
}
