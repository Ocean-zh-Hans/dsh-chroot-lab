#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import posixpath
import re
import shutil
import subprocess
import tarfile
import urllib.request

PREFIX = "data/data/com.termux/files/usr/"
SYSTEM_LIBRARIES = {"libc.so", "libm.so", "libdl.so", "liblog.so"}
WRAPPER = """#!/system/bin/sh
ROOT=$(CDPATH='' cd -- \"$(dirname -- \"$0\")/..\" && pwd) || exit 2
LD_LIBRARY_PATH=\"$ROOT/lib\"
export LD_LIBRARY_PATH
exec \"$ROOT/libexec/cp\" \"$@\"
"""
DEBUGFS_WRAPPER = WRAPPER.replace("libexec/cp", "libexec/debugfs")
NOTICE_MEMBERS = {"e2fsprogs": "share/doc/e2fsprogs/NOTICE"}
COMMON_LICENSES = {"libblkid": "LGPL-2.1-or-later.txt"}
LOCAL_LICENSES = {"libuuid": "COPYING.BSD-3-Clause"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def normalize(name: str) -> str:
    while name.startswith("./"):
        name = name[2:]
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts:
        raise ValueError(f"unsafe archive path: {name}")
    return path.as_posix()


def deb_data(path: Path) -> bytes:
    data = path.read_bytes()
    if not data.startswith(b"!<arch>\n"):
        raise ValueError(f"invalid Debian archive: {path}")
    offset = 8
    members = {}
    while offset + 60 <= len(data):
        header = data[offset:offset + 60]
        size = int(header[48:58].decode("ascii").strip())
        name = header[:16].decode("ascii").strip().rstrip("/")
        start = offset + 60
        members[name] = data[start:start + size]
        offset = start + size + (size % 2)
    for name, content in members.items():
        if name.startswith("data.tar"):
            if not name.endswith(".xz"):
                raise ValueError(f"unsupported data archive {name} in {path}")
            return content
    raise ValueError(f"Debian data archive missing: {path}")


def read_member(archive: tarfile.TarFile, members: dict, member: tarfile.TarInfo,
                depth=0, common=None) -> bytes:
    if depth > 8:
        raise ValueError("archive link cycle")
    if member.isfile():
        source = archive.extractfile(member)
        if source is None:
            raise ValueError(f"cannot read {member.name}")
        return source.read()
    if member.issym() or member.islnk():
        target = member.linkname
        if target not in members:
            target = normalize(posixpath.normpath(posixpath.join(
                posixpath.dirname(member.name), target)))
        if target not in members:
            if common is not None and target in common:
                return common[target]
            raise KeyError(target)
        return read_member(archive, members, members[target], depth + 1, common)
    raise ValueError(f"unsupported archive member: {member.name}")


def package_members(package: Path) -> tuple[tarfile.TarFile, dict]:
    archive = tarfile.open(fileobj=io.BytesIO(deb_data(package)), mode="r:xz")
    members = {}
    for member in archive.getmembers():
        member.name = normalize(member.name)
        members[member.name] = member
    return archive, members


def download(package: dict, base_url: str, downloads: Path) -> Path:
    version = re.sub(r"[^A-Za-z0-9._-]", "_", package["version"])
    destination = downloads / f"{package['name']}-{version}.deb"
    if destination.exists() and sha256(destination) == package["sha256"]:
        return destination
    url = f"{base_url.rstrip('/')}/{package['filename']}"
    temporary = destination.with_suffix(".part")
    temporary.unlink(missing_ok=True)
    try:
        with urllib.request.urlopen(url, timeout=90) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output)
        if sha256(temporary) != package["sha256"]:
            raise ValueError(f"SHA256 mismatch: {url}")
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"verified download: {package['name']} {package['version']}")
    return destination


def load_common_licenses(package_path: Path, output: Path) -> dict:
    archive, members = package_members(package_path)
    common = {}
    with archive:
        for name, member in members.items():
            if not name.startswith(PREFIX + "share/LICENSES/") or member.isdir():
                continue
            common[name] = read_member(archive, members, member)
            filename = re.sub(r"\s+", "_", PurePosixPath(name).name)
            destination = output / "licenses" / "common" / filename
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(common[name])
    if not common:
        raise ValueError("common license files are missing")
    return common


def install_package(package: dict, package_path: Path, output: Path, common: dict) -> None:
    archive, members = package_members(package_path)
    with archive:
        license_count = 0
        for name, member in members.items():
            if not name.startswith(PREFIX):
                continue
            relative = name[len(PREFIX):]
            parts = PurePosixPath(relative).parts
            is_library = (len(parts) == 2 and parts[0] == "lib" and
                          re.match(r"^[^/]+\.so(?:\..+)?$", parts[1]))
            if is_library:
                destination = output / relative
                destination.write_bytes(read_member(archive, members, member, common=common))
                destination.chmod(0o644)
            if len(parts) >= 4 and parts[:2] == ("share", "doc") and parts[2] == package["name"]:
                basename = parts[-1].lower()
                if any(word in basename for word in ("copyright", "license", "copying")):
                    filename = re.sub(r"\s+", "_", parts[-1])
                    destination = output / "licenses" / package["name"] / filename
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(read_member(archive, members, member, common=common))
                    license_count += 1
        if license_count == 0:
            name = package["name"]
            notice = None
            if name in NOTICE_MEMBERS:
                member_name = PREFIX + NOTICE_MEMBERS[name]
                if member_name in members:
                    notice = read_member(archive, members, members[member_name])
            elif name in COMMON_LICENSES:
                member_name = PREFIX + "share/LICENSES/" + COMMON_LICENSES[name]
                notice = common.get(member_name)
            elif name in LOCAL_LICENSES:
                source = Path(__file__).parent / "licenses" / LOCAL_LICENSES[name]
                if sha256(source) != package["license_sha256"]:
                    raise ValueError(f"license hash mismatch: {source}")
                notice = source.read_bytes()
            if notice is None:
                raise ValueError(f"license file missing for {name}")
            destination = output / "licenses" / name / "NOTICE"
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(notice)
        if package["name"] in ("coreutils", "e2fsprogs"):
            tool = "cp" if package["name"] == "coreutils" else "debugfs"
            binary = members[PREFIX + "bin/" + tool]
            destination = output / "libexec" / tool
            destination.write_bytes(read_member(archive, members, binary, common=common))
            destination.chmod(0o755)


def runtime_files(output: Path) -> list[Path]:
    files = []
    for directory in ("bin", "libexec", "lib"):
        root = output / directory
        if root.exists():
            files.extend(path for path in root.rglob("*") if path.is_file())
    return sorted(path for path in files if path.name != "BUILDINFO")


def write_manifest(output: Path) -> None:
    lines = [f"{sha256(path)}  {path.relative_to(output).as_posix()}\n"
             for path in runtime_files(output)]
    (output / "SHA256SUMS").write_text("".join(lines), encoding="utf-8", newline="\n")


def verify_manifest(output: Path) -> None:
    expected = {path.relative_to(output).as_posix(): sha256(path)
                for path in runtime_files(output)}
    entries = {}
    for line in (output / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
        digest, name = line.split(maxsplit=1)
        name = name.lstrip("*")
        if name not in expected or expected[name] != digest:
            raise ValueError(f"tool manifest mismatch: {name}")
        entries[name] = digest
    if entries != expected:
        raise ValueError("tool manifest has missing or stale entries")


def verify_binaries(output: Path, readelf: Path) -> None:
    needed = set()
    for path in runtime_files(output):
        relative = path.relative_to(output).as_posix()
        if relative in ("bin/cp", "bin/debugfs"):
            continue
        header = path.read_bytes()[:20]
        if len(header) != 20 or header[:4] != b"\x7fELF" or header[4] != 2 or header[5] != 1:
            raise ValueError(f"not an ELF64 little-endian binary: {relative}")
        if int.from_bytes(header[18:20], "little") != 183:
            raise ValueError(f"not an ARM64 binary: {relative}")
        description = subprocess.check_output(["file", str(path)], text=True)
        if "ARM aarch64" not in description:
            raise ValueError(f"file(1) rejected {relative}: {description.strip()}")
        dynamic = subprocess.check_output([str(readelf), "-d", str(path)], text=True)
        needed.update(re.findall(r"Shared library: \[([^]]+)\]", dynamic))
    for library in sorted(needed):
        if library not in SYSTEM_LIBRARIES and not (output / "lib" / library).is_file():
            raise ValueError(f"unmet Android library dependency: {library}")
    description = subprocess.check_output(["file", str(output / "libexec" / "cp")], text=True)
    if "/system/bin/linker64" not in description:
        raise ValueError("GNU cp is not linked to the Android linker")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--downloads", type=Path, required=True)
    parser.add_argument("--readelf", type=Path, required=True)
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    lock = json.loads(args.lock.read_text(encoding="utf-8"))
    source_text = json.dumps(lock, ensure_ascii=False, indent=2) + "\n"
    if args.verify:
        verify_manifest(args.output)
        verify_binaries(args.output, args.readelf)
        if (args.output / "TERMUX-SOURCES.json").read_text(encoding="utf-8") != source_text:
            raise ValueError("tool source lock changed; rebuild the tools")
        for package in lock["packages"]:
            name = "common" if package["name"] == "termux-licenses" else package["name"]
            directory = args.output / "licenses" / name
            if not directory.is_dir() or not any(directory.iterdir()):
                raise ValueError(f"license directory empty: {name}")
        print("Android sparse-copy tools: ok")
        return

    args.downloads.mkdir(parents=True, exist_ok=True)
    for directory in ("lib", "libexec", "licenses"):
        target = args.output / directory
        if target.exists():
            shutil.rmtree(target)
        target.mkdir(parents=True)
    common = {}
    for package in lock["packages"]:
        package_path = download(package, lock["base_url"], args.downloads)
        if package["name"] == "termux-licenses":
            common = load_common_licenses(package_path, args.output)
        else:
            install_package(package, package_path, args.output, common)
    (args.output / "bin").mkdir(parents=True, exist_ok=True)
    for tool, wrapper in (("cp", WRAPPER), ("debugfs", DEBUGFS_WRAPPER)):
        destination = args.output / "bin" / tool
        destination.write_text(wrapper, encoding="utf-8", newline="\n")
        destination.chmod(0o755)
    (args.output / "TERMUX-SOURCES.json").write_text(source_text, encoding="utf-8", newline="\n")
    verify_binaries(args.output, args.readelf)
    write_manifest(args.output)
    print(f"Android sparse-copy tools installed: {args.output}")


if __name__ == "__main__":
    main()
