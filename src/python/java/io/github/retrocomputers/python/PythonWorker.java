package io.github.retrocomputers.python;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.ProxyExecutable;

public final class PythonWorker {
    public static void main(String[] args) throws Exception {
        Gson gson = new Gson();
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var output = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        JsonObject initial = gson.fromJson(input.readLine(), JsonObject.class);
        try (Context context = Context.newBuilder("python")
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(name -> false)
                .allowIO(IOAccess.NONE)
                .allowNativeAccess(false)
                .allowCreateProcess(false)
                .allowCreateThread(false)
                .option("engine.WarnInterpreterOnly", "false")
                .build()) {
            context.getBindings("python").putMember("_host", (ProxyExecutable) values -> {
                JsonObject request = new JsonObject();
                request.addProperty("op", values[0].asString());
                request.addProperty("args", values[1].asString());
                output.println(gson.toJson(request));
                try {
                    String response = input.readLine();
                    if (response == null) throw new IllegalStateException("Computer disconnected");
                    return response;
                } catch (java.io.IOException error) {
                    throw new IllegalStateException(error);
                }
            });
            context.eval("python", """
                import sys, json, types, io, builtins
                def _call(operation, *args):
                    response = json.loads(_host(operation, json.dumps(args, ensure_ascii=False)))
                    if "error" in response:
                        raise RuntimeError(response["error"])
                    return response.get("value")
                def _print(*args, sep=" ", end="\\n", file=None, flush=False):
                    if file is not None:
                        file.write(sep.join(str(arg) for arg in args) + end)
                    else:
                        _call("print", sep.join(str(arg) for arg in args) + end)
                builtins.print = _print
                def _input(prompt=""):
                    return _call("input", str(prompt))
                builtins.input = _input
                class _Console(io.TextIOBase):
                    def write(self, value):
                        _call("print", str(value))
                        return len(value)
                    def flush(self):
                        pass
                sys.stdout = _Console()
                sys.stderr = _Console()
                class _ComputerFile(io.StringIO):
                    def __init__(self, path, mode, encoding):
                        if mode not in ("r", "w", "a", "r+", "w+", "a+", "x", "x+"):
                            raise ValueError("Only UTF-8 text files are supported")
                        self._path = str(path)
                        self._writable_file = mode != "r"
                        self._file_mode = mode
                        if mode.startswith("x") and _call("exists", self._path):
                            raise FileExistsError(self._path)
                        text = _call("read_file", self._path) if mode.startswith("r") else (
                            _call("read_file", self._path) if mode.startswith("a") and _call("exists", self._path) else "")
                        super().__init__(text)
                        if mode.startswith(("w", "x")):
                            _call("write_file", self._path, "")
                        if mode.startswith("a"):
                            self.seek(0, 2)
                    def write(self, text):
                        if not self._writable_file:
                            raise io.UnsupportedOperation("not writable")
                        if self._file_mode.startswith("a"):
                            self.seek(0, 2)
                        before, position = self.getvalue(), self.tell()
                        size = super().write(text)
                        try:
                            self.flush()
                        except Exception:
                            super().seek(0)
                            super().truncate()
                            super().write(before)
                            super().seek(position)
                            raise
                        return size
                    def flush(self):
                        if not self.closed and self._writable_file:
                            _call("write_file", self._path, self.getvalue())
                    def close(self):
                        if not self.closed:
                            self.flush()
                        super().close()
                def _open(path, mode="r", buffering=-1, encoding="utf-8", errors=None, newline=None, closefd=True, opener=None):
                    if encoding not in (None, "utf-8", "UTF-8"):
                        raise ValueError("Use UTF-8")
                    return _ComputerFile(path, mode, encoding)
                builtins.open = _open
                computer = types.ModuleType("computer")
                computer.ports = lambda: _call("ports")
                computer.disk = lambda: _call("disk")
                computer.read = lambda port: _call("read", str(port))
                computer.set = lambda port, value: _call("set", str(port), int(value))
                computer.read_file = lambda path: _call("read_file", str(path))
                computer.write_file = lambda path, text: _call("write_file", str(path), str(text))
                computer.listdir = lambda path=".": _call("listdir", str(path))
                computer.mkdir = lambda path: _call("mkdir", str(path))
                computer.sleep = lambda seconds: _call("sleep", float(seconds))
                computer.name = _call("name")
                sys.modules["computer"] = computer
                import importlib.util
                class _ComputerModules:
                    def find_spec(self, fullname, path=None, target=None):
                        source_path = fullname.replace(".", "/")
                        for candidate, package in ((source_path + ".py", False), (source_path + "/__init__.py", True)):
                            if _call("exists", candidate):
                                spec = importlib.util.spec_from_loader(fullname, self, origin=candidate, is_package=package)
                                return spec
                        return None
                    def create_module(self, spec):
                        return None
                    def exec_module(self, module):
                        source_path = module.__spec__.origin
                        module.__file__ = source_path
                        exec(compile(_call("read_file", source_path), source_path, "exec"), module.__dict__)
                sys.meta_path.append(_ComputerModules())
                """);
            context.getBindings("python").putMember("__name__", "__main__");
            context.getBindings("python").putMember("__file__", initial.get("file").getAsString());
            context.eval(Source.newBuilder("python", initial.get("source").getAsString(),
                initial.get("file").getAsString()).build());
            JsonObject done = new JsonObject();
            done.addProperty("op", "done");
            output.println(gson.toJson(done));
        } catch (Throwable error) {
            JsonObject result = new JsonObject();
            result.addProperty("op", "failure");
            result.addProperty("message", error.getMessage());
            output.println(gson.toJson(result));
        }
    }
}
