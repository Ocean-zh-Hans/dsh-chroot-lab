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
shellcheck -s sh -e SC3043,SC1091 "${scripts[@]}"
bash -n agent-app/build.sh agent-app/root-helper.sh console-app/build.sh
shellcheck -s bash agent-app/build.sh agent-app/root-helper.sh console-app/build.sh
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
shellcheck -s bash build/*.sh

printf '== Android helper ==\n'
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

printf '== Java behavior ==\n'
_java_test=$(mktemp -d "${TMPDIR:-/tmp}/dsh-java.XXXXXX")
trap 'rm -rf "$_java_test"' EXIT
javac -d "$_java_test" console-app/src/dev/chroot/console/ConsoleSupport.java tests/ConsoleSupportTest.java
java -cp "$_java_test" dev.chroot.console.ConsoleSupportTest
_sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/AppData/Local/Android/Sdk}}
_jar=${ANDROID_JAR:-}
[ -n "$_jar" ] || _jar=$(find "$_sdk/platforms" -name android.jar -type f 2>/dev/null | sort -V | tail -n 1)
[ -f "$_jar" ] || {
  printf 'android.jar not found; set ANDROID_HOME or ANDROID_JAR\n' >&2
  exit 2
}
_javac_jar=$_jar
_javac_out=$_java_test
if [ -x /usr/bin/cygpath ]; then
  _javac_jar=$(/usr/bin/cygpath -w "$_javac_jar")
  _javac_out=$(/usr/bin/cygpath -w "$_javac_out")
elif command -v cygpath >/dev/null 2>&1; then
  _javac_jar=$(cygpath -w "$_javac_jar")
  _javac_out=$(cygpath -w "$_javac_out")
fi
find console-app/src agent-app/src agent-root-helper/src -name '*.java' >"$_java_test/android-sources.txt"
javac -source 8 -target 8 -Xlint:-options -cp "$_javac_jar" -d "$_javac_out" @"$_java_test/android-sources.txt"
javac -cp "$_javac_jar" -d "$_javac_out" \
  agent-app/src/dev/dsh/agent/UiSelector.java agent-app/src/dev/dsh/agent/Cron.java \
  agent-app/src/dev/dsh/agent/AgentMethods.java agent-app/src/dev/dsh/agent/SafePaths.java tests/AgentCoreTest.java
java -cp "$_javac_out" dev.dsh.agent.AgentCoreTest
rm -rf "$_java_test"
trap - EXIT

printf '== Agent client ==\n'
PYTHONPATH="$ROOT${PYTHONPATH:+:$PYTHONPATH}" python3 -u -m unittest tests.test_agent_client -v
printf '== docs ==\n'
python3 build/check-docs.py
printf '== tests ==\n'
bash tests/run.sh
printf '== Compose Agent build ==\n'
./agent-app/build.sh
printf '\nall host checks passed\n'
