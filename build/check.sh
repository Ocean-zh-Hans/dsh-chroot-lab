#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
# Host-only checks and helper builds. Never touches a device or Android system state.
set -euo pipefail

ROOT=$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$ROOT"
if [ -d "$ROOT/build/.tools/bin" ]; then
  PATH="$ROOT/build/.tools/bin:$ROOT/build/.tools/msys/usr/bin:$PATH"
fi

need() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'missing required tool: %s\n' "$1" >&2
    exit 2
  }
}
need shellcheck
need shfmt
need sh
need mksh
need zip
need unzip
need file
need python3
need javac
need java
need sha256sum

mapfile -t scripts < <(
  find module bake agent-app console-app -type f |
    while IFS= read -r f; do
      case "$f" in
        agent-app/build.sh | agent-app/root-helper.sh | console-app/build.sh | module/control/bin/*.py) ;;
        *.sh | module/bin/* | module/control/bin/* | module/control/s6/*/run | module/control/s6/*/finish) printf '%s\n' "$f" ;;
      esac
    done | sort
)
if [ "${#scripts[@]}" -eq 0 ]; then
  printf 'no scripts found\n' >&2
  exit 2
fi

printf '== sh -n ==\n'
for f in "${scripts[@]}"; do
  sh -n "$f"
done
printf '== mksh -n ==\n'
for f in "${scripts[@]}"; do
  mksh -n "$f"
done
printf '== shellcheck ==\n'
shellcheck -s sh -S warning -e SC3043,SC1091 "${scripts[@]}"
bash -n agent-app/build.sh agent-app/root-helper.sh console-app/build.sh
shellcheck -s bash -S warning agent-app/build.sh agent-app/root-helper.sh console-app/build.sh
printf '== shfmt ==\n'
_shfmt_diff=$(mktemp "${TMPDIR:-/tmp}/dsh-shfmt.XXXXXX")
if ! shfmt -d -ln=mksh -i 2 "${scripts[@]}" >"$_shfmt_diff" 2>&1; then
  cat "$_shfmt_diff" >&2
  rm -f "$_shfmt_diff"
  exit 1
fi
rm -f "$_shfmt_diff"

printf '== build scripts ==\n'
for f in build/*.sh; do
  bash -n "$f"
done
shellcheck -s bash -S warning build/*.sh

printf '== Android helper ==\n'
if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -z "${ANDROID_NDK_ROOT:-}" ] && [ -d /opt/android-ndk-r29 ]; then
  export ANDROID_NDK_HOME=/opt/android-ndk-r29
fi
./build/build-tools.sh
./build/build-tools.sh --verify

printf '== module layout ==\n'
for f in module.prop customize.sh service.sh boot-completed.sh uninstall.sh action.sh \
  bin/dsh-ctl bin/dsh-nsinit bin/dsh-watchdog bin/dsh-recover-mounts bin/dsh-log-limit bin/dsh-lock \
  control/README.md control/bin/dsh-run control/bin/dsh-android-env control/bin/dsh-healthcheck.py control/dsh/hooks.patch.yml control/dsh/hooks-audit-only.patch.yml \
  control/s6/ttyd/down tools/SHA256SUMS tools/bin/dsh-mount-ro-recursive \
  tools/bin/cp tools/libexec/cp tools/bin/debugfs tools/libexec/debugfs tools/TERMUX-SOURCES.json tools/licenses/coreutils/copyright \
  lib/common.sh lib/config.sh lib/log.sh lib/proc.sh lib/state.sh lib/lock.sh \
  lib/loop.sh lib/mount.sh lib/unmount.sh lib/doctor.sh lib/plan.sh lib/status.sh lib/lifecycle.sh \
  lib/snapshot.sh lib/systemrw.sh lib/guard.sh lib/ttyd.sh lib/image.sh; do
  [ -f "module/$f" ] || {
    printf 'missing module file: %s\n' "$f" >&2
    exit 2
  }
done
[ ! -e module/install.sh ] || {
  printf 'module/install.sh must not exist\n' >&2
  exit 2
}
[ ! -d module/system ] || {
  printf 'global module/system overlay is not allowed\n' >&2
  exit 2
}

printf '== Android manifests ==\n'
python3 -c 'import xml.etree.ElementTree as ET
root = ET.parse("agent-app/AndroidManifest.xml").getroot()
name = "{http://schemas.android.com/apk/res/android}name"
assert any(node.get(name) == "android.permission.INTERNET" for node in root.findall("uses-permission")), "agent INTERNET permission missing"
console = ET.parse("console-app/AndroidManifest.xml").getroot()
for activity in console.find("application").findall("activity"):
    assert activity.get(name) in (".MainActivity", ".WebActivity"), "unexpected console activity"
    assert activity.get(name.replace("name", "screenOrientation")) == "portrait", "console activity must stay portrait"
print("Android manifest checks: ok")'
[ -f console-app/src/dev/chroot/console/MainActivity.kt ] || {
  printf 'Console MainActivity.kt is missing\n' >&2
  exit 1
}
[ -f console-app/src/dev/chroot/console/WebActivity.kt ] || {
  printf 'Console WebActivity.kt is missing\n' >&2
  exit 1
}
[ ! -e console-app/src/dev/chroot/console/MainActivity.java ] || {
  printf 'legacy Console MainActivity.java must not remain\n' >&2
  exit 1
}
[ ! -e console-app/src/dev/chroot/console/WebActivity.java ] || {
  printf 'legacy Console WebActivity.java must not remain\n' >&2
  exit 1
}

printf '== Kotlin behavior ==\n'
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -d /opt/android-sdk ]; then
  export ANDROID_HOME=/opt/android-sdk
fi
_sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}
export ANDROID_HOME="$_sdk" ANDROID_SDK_ROOT="$_sdk"
./gradlew :agent-core:test :agent-app:testDebugUnitTest :console-app:testDebugUnitTest --console=plain

printf '== Agent client ==\n'
PYTHONPATH="$ROOT${PYTHONPATH:+:$PYTHONPATH}" python3 -u -m unittest tests.test_agent_client -v
printf '== docs ==\n'
python3 build/check-docs.py
printf '== tests ==\n'
bash tests/run.sh
printf '== Compose Agent build ==\n'
./agent-app/build.sh
printf '\nall host checks passed\n'
