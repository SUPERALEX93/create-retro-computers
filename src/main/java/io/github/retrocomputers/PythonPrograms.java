package io.github.retrocomputers;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipInputStream;
import net.minecraft.network.chat.Component;

final class PythonPrograms {
    private static final Gson JSON = new Gson();
    private static final AtomicInteger RUNNING = new AtomicInteger();
    private static Path runtime;
    private final Device owner;
    private volatile Session active;

    private static final class Session {
        volatile Process process;
        volatile boolean cancelled;
        int printed;
        String partial = "";
        String directory;
        List<Component> console;
        volatile CompletableFuture<String> input;
        Path root;
        Path source;
        volatile PrintWriter nativeInput;
        volatile int capacity;
    }

    PythonPrograms(Device owner) { this.owner = owner; }
    boolean running() { return active != null; }

    Component start(String file) {
        if (active != null) return text("busy");
        final String source;
        try { source = owner.files.readText(file); }
        catch (Exception error) { return Component.literal(error.getMessage()); }
        if (!file.toLowerCase(java.util.Locale.ROOT).endsWith(".py") && NativePrograms.language(file).isEmpty()) return text("extension");
        if (RUNNING.incrementAndGet() > 4) {
            RUNNING.decrementAndGet();
            return text("slots");
        }
        Session session = new Session();
        session.capacity = owner.diskCapacity();
        session.root = owner.files.root();
        try { session.source = owner.files.runtimeSource(file); }
        catch (IOException error) { RUNNING.decrementAndGet(); return Component.literal(error.getMessage()); }
        session.directory = owner.files.directory();
        session.console = owner.output;
        owner.consoleBeforeProgram = session.console;
        active = session;
        owner.programRunning = true;
        owner.programWaiting = false;
        owner.programPrompt = "";
        owner.programPartial = "";
        owner.input = "";
        owner.output = List.of();
        owner.clearPendingCommands();
        owner.refresh();
        CompletableFuture.runAsync(() -> run(session, file, source));
        return null;
    }

    void stop() {
        Session session = active;
        if (session != null) {
            session.cancelled = true;
            if (session.input != null) session.input.cancel(false);
            if (session.process != null) session.process.destroyForcibly();
        }
    }

    void updateCapacity(int capacity) { Session session = active; if (session != null) session.capacity = capacity; }

    void submitInput(String value) {
        Session session = active;
        if (session != null && session.nativeInput != null && owner.programWaiting) {
            owner.appendOutput(Component.literal(session.partial + value));
            session.partial = "";
            owner.programPartial = owner.input = "";
            session.nativeInput.println(value);
            owner.refresh();
            return;
        }
        if (session == null || !owner.programWaiting || session.input == null) return;
        owner.appendOutput(Component.literal(owner.programPrompt + value));
        owner.input = "";
        owner.programPrompt = "";
        owner.programWaiting = false;
        owner.refresh();
        session.input.complete(value);
    }

    private CompletableFuture<String> requestInput(Session session, String prompt) throws IOException {
        if (active != session || session.cancelled || !owner.powered || owner.isRemoved()) throw new IOException("Computer is off");
        session.input = new CompletableFuture<>();
        owner.programPrompt = session.partial + prompt;
        session.partial = "";
        owner.programPartial = "";
        owner.programWaiting = true;
        owner.input = "";
        owner.refresh();
        return session.input;
    }

    private void run(Session session, String file, String source) {
        var server = owner.getLevel().getServer();
        Component failure = null;
        try {
            String nativeLanguage = NativePrograms.language(file);
            if (!nativeLanguage.isEmpty()) {
                NativePrograms.run(nativeLanguage, session.root, session.source, source, session.directory, new NativePrograms.Host() {
                    public boolean cancelled() { return session.cancelled; }
                    public int capacity() { return session.capacity; }
                    public void process(Process process, boolean interactive) throws Exception {
                        session.process = process;
                        if (session.cancelled) { process.destroyForcibly(); return; }
                        server.submit(() -> {
                            if (active != session || session.cancelled) return;
                            session.nativeInput = interactive ? new PrintWriter(process.getOutputStream(), true, StandardCharsets.UTF_8) : null;
                            owner.programWaiting = interactive;
                            owner.refresh();
                        }).get(10, TimeUnit.SECONDS);
                    }
                    public void output(String chunk) throws Exception {
                        server.submit(() -> {
                            var args = new JsonArray(); args.add(chunk);
                            try { call(session, "print", args); }
                            catch (IOException error) { throw new java.util.concurrent.CompletionException(error); }
                        }).get(10, TimeUnit.SECONDS);
                    }
                    public String api(String operation, List<String> values) throws Exception {
                        return server.submit(() -> {
                            var args = new JsonArray(); values.forEach(args::add);
                            try { return JSON.toJson(call(session, operation, args)); }
                            catch (IOException error) { throw new java.util.concurrent.CompletionException(error); }
                        }).get(10, TimeUnit.SECONDS);
                    }
                });
                return;
            }
            Path libraries = runtime();
            if (session.cancelled) return;
            String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            Process process = new ProcessBuilder(java, "-Xmx" + (owner.advanced(Device.Slot.RAM) ? "512m" : "384m"),
                "-Dfile.encoding=UTF-8", "-cp", libraries.resolve("*").toString(),
                "io.github.retrocomputers.python.PythonWorker")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            session.process = process;
            if (session.cancelled) { process.destroyForcibly(); return; }
            try (var input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                 var output = new PrintWriter(process.getOutputStream(), true, StandardCharsets.UTF_8)) {
                JsonObject initial = new JsonObject();
                initial.addProperty("file", file);
                initial.addProperty("source", source);
                output.println(JSON.toJson(initial));
                String line;
                boolean finished = false;
                while (!session.cancelled && (line = input.readLine()) != null) {
                    if (line.length() > 2 * 1024 * 1024) throw new IOException("Python output too large");
                    JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                    String operation = request.get("op").getAsString();
                    if (operation.equals("done") || operation.equals("failure")) {
                        finished = true;
                        if (operation.equals("failure")) throw new IOException(request.get("message").getAsString());
                        break;
                    }
                    JsonObject response = new JsonObject();
                    try {
                        JsonArray arguments = JsonParser.parseString(request.get("args").getAsString()).getAsJsonArray();
                        if (operation.equals("sleep")) {
                            double seconds = arguments.get(0).getAsDouble();
                            if (!Double.isFinite(seconds) || seconds < 0 || seconds > 3600) throw new IOException("sleep accepts 0..3600 seconds");
                            long end = System.nanoTime() + (long) (seconds * 1_000_000_000L);
                            while (!session.cancelled && System.nanoTime() < end) Thread.sleep(25);
                        } else if (operation.equals("input")) {
                            CompletableFuture<String> answer = server.submit(() -> {
                                try { return requestInput(session, arguments.get(0).getAsString()); }
                                catch (IOException error) { throw new java.util.concurrent.CompletionException(error); }
                            }).get(10, TimeUnit.SECONDS);
                            while (!session.cancelled) {
                                try { response.addProperty("value", answer.get(250, TimeUnit.MILLISECONDS)); break; }
                                catch (java.util.concurrent.TimeoutException waiting) { /* Wait without blocking server ticks. */ }
                            }
                        } else {
                            Object value = server.submit(() -> {
                                try { return call(session, operation, arguments); }
                                catch (IOException error) { throw new java.util.concurrent.CompletionException(error); }
                            }).get(10, TimeUnit.SECONDS);
                            response.add("value", JSON.toJsonTree(value));
                        }
                    } catch (Exception error) {
                        if (error instanceof java.util.concurrent.TimeoutException) {
                            session.cancelled = true;
                            process.destroyForcibly();
                        }
                        Throwable cause = error.getCause() == null ? error : error.getCause();
                        response.addProperty("error", String.valueOf(cause.getMessage()));
                    }
                    output.println(JSON.toJson(response));
                }
                if (!finished && !session.cancelled) throw new IOException("Python worker exited before completion");
            } finally { process.destroyForcibly(); }
        } catch (Exception error) {
            if (!session.cancelled) failure = text("error", String.valueOf(error.getMessage()));
        } finally {
            Component result = failure;
            if (server != null) server.execute(() -> finish(session, result));
            RUNNING.decrementAndGet();
        }
    }

    private void finish(Session session, Component failure) {
        if (active != session) return;
        active = null;
        List<Component> programOutput = owner.output;
        owner.output = session.console;
        // Retain the result in the shell after returning from the dedicated program screen.
        var result = new java.util.ArrayList<>(programOutput);
        if (!session.partial.isEmpty()) result.add(Component.literal(session.partial));
        owner.input = "";
        owner.programRunning = false;
        owner.programWaiting = false;
        owner.programPrompt = "";
        owner.programPartial = "";
        result.add(failure != null ? failure : text(session.cancelled ? "stopped" : "finished"));
        owner.appendOutputs(result);
        owner.consoleBeforeProgram = List.of();
    }

    private Object call(Session session, String operation, JsonArray args) throws IOException {
        if (active != session || session.cancelled || owner.isRemoved() || !owner.powered
                || owner.getLevel().getBlockEntity(owner.getBlockPos()) != owner) {
            throw new IOException("Computer is off or moved");
        }
        String first = args.isEmpty() ? "" : args.get(0).getAsString();
        return switch (operation) {
            case "name" -> owner.computerName;
            case "disk" -> owner.files.statistics(owner.diskCapacity());
            case "print" -> {
                session.printed += first.length();
                if (session.printed > 131072) throw new IOException("Program output exceeds 128 KiB");
                String[] lines = (session.partial + first).split("\n", -1);
                for (int i = 0; i < lines.length - 1; i++) owner.appendOutput(Component.literal(lines[i]));
                session.partial = lines[lines.length - 1];
                owner.programPartial = session.partial;
                owner.refresh();
                yield null;
            }
            case "ports" -> owner.network.pythonPorts();
            case "read" -> owner.network.pythonRead(first);
            case "set" -> {
                int value = args.get(1).getAsInt();
                owner.network.pythonSet(first, value);
                yield null;
            }
            case "read_file" -> owner.files.readText(localPath(session, first));
            case "write_file" -> {
                owner.files.writeText(localPath(session, first), args.get(1).getAsString(), owner.diskCapacity());
                yield null;
            }
            case "exists" -> owner.files.fileExists(localPath(session, first));
            case "listdir" -> owner.files.names(localPath(session, first));
            case "mkdir" -> {
                owner.files.mkdir(localPath(session, first), owner.diskCapacity());
                yield null;
            }
            default -> throw new IOException("Unknown computer API method: " + operation);
        };
    }

    private static String localPath(Session session, String path) {
        return path.startsWith("/") ? path : session.directory + "/" + path;
    }

    private static synchronized Path runtime() throws IOException {
        if (runtime != null) return runtime;
        Path root = ComputerStorage.base().getParent().resolve("python-runtime-24.1.2").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)) throw new IOException("Invalid Python runtime folder");
        Files.createDirectories(root);
        try (var resource = PythonPrograms.class.getResourceAsStream("/retrocomputers/python-runtime.zip")) {
            if (resource == null) throw new IOException("Bundled Python runtime missing");
            try (var zip = new ZipInputStream(resource)) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    Path file = root.resolve(entry.getName()).normalize();
                    if (!file.getParent().equals(root) || Files.isSymbolicLink(file)) throw new IOException("Invalid runtime entry");
                    Path temporary = Files.createTempFile(root, ".extract-", ".tmp");
                    try {
                        Files.copy(zip, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } finally { Files.deleteIfExists(temporary); }
                }
            }
        }
        runtime = root;
        return root;
    }

    static Component text(String key, Object... args) {
        return Component.translatable("retrocomputers.python." + key, args);
    }
}
