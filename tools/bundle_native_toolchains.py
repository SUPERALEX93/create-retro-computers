"""Pack a self-contained Linux x86_64 compiler filesystem directly from local tools.

No staging copy is made. Symlinks and executable modes are restored by the mod.
The resulting bundle is pinned until build/generated/nativeResources is removed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

project = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--output", type=Path, default=project / "build/generated/nativeResources/retrocomputers/native/runtime")
args = parser.parse_args()
output = args.output
output.mkdir(parents=True, exist_ok=True)
archive = output / "rootfs.zip"
metadata = output / "bundle.json"
if archive.exists() and metadata.exists() and metadata.stat().st_mtime >= Path(__file__).stat().st_mtime:
    print("Native toolchain bundle is current", flush=True)
    raise SystemExit(0)

def command(*argv):
    return subprocess.check_output(argv, text=True).strip()

jdk = Path(os.environ.get("JAVA_HOME", str(next((Path.home() / ".jdks").iterdir())))).resolve()
rust = Path(command("rustup", "which", "rustc")).resolve().parent.parent
gcc = Path(command("gcc", "-print-libgcc-file-name")).parent
dotnet = Path("/usr/share/dotnet")
sdk = sorted((dotnet / "sdk").iterdir())[-1]
references = sorted((dotnet / "packs/Microsoft.NETCore.App.Ref").iterdir())[-1]
runtime = dotnet / "shared/Microsoft.NETCore.App" / references.name
python_version = command("python3", "-c", "import sys;print(f'{sys.version_info.major}.{sys.version_info.minor}')")

files = {}
links = {"bin": "usr/bin", "lib": "usr/lib", "lib64": "usr/lib", "usr/lib64": "lib"}
roots = [(jdk, "jdk"), (rust, "rust")]

def target(path):
    path = Path(os.path.abspath(path))
    for prefix, replacement in (("/usr/lib64/", "/usr/lib/"), ("/lib64/", "/usr/lib/"), ("/lib/", "/usr/lib/"), ("/bin/", "/usr/bin/")):
        if str(path).startswith(prefix):
            path = Path(replacement + str(path)[len(prefix):])
            break
    for source, destination in roots:
        if path.is_relative_to(source):
            return str(Path(destination) / path.relative_to(source))
    return str(path).lstrip("/")

def add(path, destination=None):
    path = Path(path)
    destination = destination or target(path)
    if destination in files or destination in links:
        return
    if path.is_symlink():
        value = os.readlink(path)
        links[destination] = "/" + target(Path(value)) if value.startswith("/") else value
        real = Path(os.path.abspath(path.parent / value)) if not value.startswith("/") else Path(value)
        add(real)
    elif path.is_file():
        files[destination] = path
    elif path.is_dir():
        for child in sorted(path.iterdir()):
            if child.name in {"__pycache__", "site-packages", "test", "tests", "idlelib", "tkinter", "ensurepip"}:
                continue
            add(child, str(Path(destination) / child.name))

for binary in ("bwrap", "lua", "gcc", "g++", "cc", "c++", "cpp", "as", "ld", "ar", "nm", "ranlib", "sh", "python3", "stdbuf"):
    add(Path("/usr/bin") / binary)
for child in gcc.iterdir():
    if child.name not in {"32", "plugin", "lto1", "cc1obj", "cc1objplus"}:
        add(child)
add("/usr/include/c++")
for package in ("glibc", "linux-api-headers"):
    for name in command("pacman", "-Qlq", package).splitlines():
        path = Path(name)
        if name.startswith("/usr/include/") or path.parent == Path("/usr/lib"):
            if path.is_file() or path.is_symlink():
                add(path)

add("/usr/lib/python" + python_version)
add("/usr/lib/coreutils/libstdbuf.so")
add(dotnet / "dotnet")
add("/usr/bin/dotnet")
add(dotnet / "host")
add(runtime)
add(references)
add(sdk / "Roslyn/bincore")
for name in ("LICENSE.txt", "ThirdPartyNotices.txt"):
    add(dotnet / name)
    add(sdk / name)
for child in (jdk / "lib").iterdir():
    if child.name != "src.zip": add(child)
for name in ("bin", "conf", "legal", "include", "release", "LICENSE", "ASSEMBLY_EXCEPTION", "ADDITIONAL_LICENSE_INFO"):
    add(jdk / name)
add(rust / "bin/rustc")
add(rust / "lib")
for name in ("LICENSE-APACHE", "LICENSE-MIT", "COPYRIGHT", "version"):
    add(rust / name)
for library in ("libicuuc.so", "libicui18n.so", "libssl.so", "libcrypto.so", "libz.so", "libgcc_s.so.1", "libstdc++.so.6", "ld-linux-x86-64.so.2"):
    add(Path("/usr/lib") / library)
for package in ("gcc", "gcc-libs", "glibc", "binutils", "lua", "python", "bubblewrap", "icu", "openssl", "zlib"):
    add(Path("/usr/share/licenses") / package)
add("/usr/share/licenses/common/GPL3")
add("/usr/share/licenses/common/LGPL2.1")

# Complete the dynamic-library closure, preserving the paths expected by ELF loaders.
checked = set()
while True:
    pending = [path for path in files.values() if path not in checked]
    if not pending: break
    for path in pending:
        checked.add(path)
        if path.suffix == ".o": continue
        with path.open("rb") as stream:
            if stream.read(4) != b"\x7fELF": continue
        result = subprocess.run(["ldd", str(path)], text=True, capture_output=True)
        for dependency in re.findall(r"(?:=>\s+)?(/[^\s()]+)", result.stdout):
            if Path(dependency).is_file(): add(dependency)

# Host lib aliases resolve inside the bundled filesystem, rather than onto the host.
for alias in list(files):
    if alias.startswith(("lib/", "lib64/")):
        files.pop(alias)
for alias in list(links):
    if alias.startswith(("lib/", "lib64/")):
        links.pop(alias)

total = sum(path.stat().st_size for path in files.values())
print(f"Packing {len(files)} files, {total / 1024**2:.0f} MiB uncompressed", flush=True)
executables = []
fingerprint = hashlib.sha256()
temporary = archive.with_suffix(".zip.part")
with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6, allowZip64=True) as bundle:
    for index, (name, path) in enumerate(sorted(files.items())):
        bundle.write(path, name)
        if path.stat().st_mode & 0o111: executables.append(name)
        fingerprint.update((name + str(path.stat().st_size) + str(path.stat().st_mtime_ns)).encode())
        if index % 1500 == 0: print(f"Packed {index}/{len(files)}", flush=True)
temporary.replace(archive)
info = {
    "id": "linux-x86_64-" + fingerprint.hexdigest()[:12],
    "platform": "linux-x86_64", "bytes": total, "files": len(files),
    "executables": executables, "links": links,
    "directories": ["proc", "dev", "tmp", "work", "build", "etc"],
    "versions": {"gcc": command("gcc", "-dumpfullversion"), "rust": command(str(rust / "bin/rustc"), "--version"),
                 "dotnet": references.name, "java": jdk.name, "pythonBridge": python_version},
}
metadata.write_text(json.dumps(info, indent=2) + "\n")
print(f"Bundle ready: {archive.stat().st_size / 1024**2:.0f} MiB", flush=True)
