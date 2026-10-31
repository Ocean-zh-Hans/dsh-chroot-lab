#!/usr/bin/env python3
"""Verify module archive structure, permissions, and bundled helper integrity."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import zipfile

EXECUTABLES = {
    "customize.sh",
    "service.sh",
    "boot-completed.sh",
    "uninstall.sh",
    "action.sh",
    "bin/dsh-ctl",
    "bin/dsh-nsinit",
    "bin/dsh-watchdog",
    "bin/dsh-log-limit",
    "bin/dsh-lock",
    "bin/dsh-recover-mounts",
    "control/bin/dsh-run",
    "control/bin/dsh-healthcheck.py",
    "control/s6/dsh/run",
    "control/s6/dsh/finish",
    "control/s6/ttyd/run",
    "control/hooks/riskcontext.sh",
    "control/hooks/posttool.sh",
    "tools/bin/dsh-mount-ro-recursive",
    "tools/bin/cp",
    "tools/libexec/cp",
}
REQUIRED = EXECUTABLES | {
    "module.prop",
    "tools/SHA256SUMS",
    "tools/TERMUX-SOURCES.json",
    "tools/licenses/coreutils/copyright",
    "control/bin/dsh-android-env",
    "control/dsh/hooks.patch.yml",
    "control/dsh/hooks-audit-only.patch.yml",
    "control/dsh/runtime.patch.yml",
    "control/dsh/phone-mcp/phone_mcp.py",
    "control/hooks/hooks.json",
    "control/s6/ttyd/down",
} | {
    f"lib/{name}.sh"
    for name in (
        "common",
        "config",
        "log",
        "proc",
        "state",
        "lock",
        "loop",
        "mount",
        "unmount",
        "doctor",
        "plan",
        "status",
        "lifecycle",
        "snapshot",
        "systemrw",
        "guard",
        "ttyd",
        "image",
    )
}


def fail(message: str) -> None:
    raise SystemExit(f"module verification failed: {message}")


def normalize_modes(path: Path) -> None:
    temporary = path.with_name(path.name + ".tmp")
    try:
        with zipfile.ZipFile(path) as source, zipfile.ZipFile(temporary, "w") as target:
            for info in source.infolist():
                data = source.read(info.filename)
                info.create_system = 3
                if info.is_dir():
                    info.external_attr = (0o40755 << 16) | 0x10
                elif info.filename in EXECUTABLES:
                    info.external_attr = 0o100755 << 16
                else:
                    info.external_attr = 0o100644 << 16
                target.writestr(info, data)
        temporary.replace(path)
    finally:
        if temporary.exists():
            temporary.unlink()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    parser.add_argument("--console", action="store_true")
    parser.add_argument("--fix-modes", action="store_true")
    args = parser.parse_args()
    if args.fix_modes:
        normalize_modes(args.archive)
    with zipfile.ZipFile(args.archive) as archive:
        bad = archive.testzip()
        if bad:
            fail(f"CRC mismatch: {bad}")
        entries = {entry.filename: entry for entry in archive.infolist()}
        for name in sorted(REQUIRED - entries.keys()):
            fail(f"missing {name}")
        if "install.sh" in entries:
            fail("install.sh is forbidden")
        if any(name.startswith("system/") for name in entries):
            fail("global system overlay is forbidden")
        if ("console.apk" in entries) != args.console:
            fail("console inclusion does not match the requested package mode")
        for name in EXECUTABLES:
            mode = entries[name].external_attr >> 16
            if mode & 0o777 != 0o755:
                fail(f"{name} is not executable (mode {mode:o})")
        manifest = archive.read("tools/SHA256SUMS").decode().splitlines()
        if not manifest:
            fail("tools/SHA256SUMS is empty")
        for line in manifest:
            expected, name = line.split(maxsplit=1)
            name = name.lstrip("*")
            if not name.startswith(("bin/", "libexec/", "lib/")):
                fail(f"unexpected tool manifest path: {name}")
            if "tools/" + name not in entries:
                fail(f"manifest entry missing from archive: {name}")
            data = archive.read("tools/" + name)
            if hashlib.sha256(data).hexdigest() != expected:
                fail(f"tool SHA256 mismatch: {name}")
            if data.startswith(b"\x7fELF"):
                if len(data) < 20 or data[4] != 2 or data[5] != 1:
                    fail(f"tool is not ELF64 little-endian: {name}")
                if int.from_bytes(data[18:20], "little") != 183:
                    fail(f"tool is not ARM64: {name}")
    print("module archive checks: ok")


if __name__ == "__main__":
    main()
