#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
proc="$case_dir/proc"
host="$case_dir/host"
pidof="$case_dir/pidof"
boot=/system/framework/framework.jar:/apex/com.android.art/javalib/core-oj.jar
oat=/apex/com.android.art/javalib/core-oj.jar
mkdir -p "$proc/4242" "$host/system/framework" "$host/apex/com.android.art/javalib"
: >"$host/system/framework/framework.jar"
: >"$host/apex/com.android.art/javalib/core-oj.jar"
printf 'BOOTCLASSPATH=%s\0DEX2OATBOOTCLASSPATH=%s\0ANDROID_TEST_VALUE=not-imported\0' "$boot" "$oat" >"$proc/4242/environ"
cat >"$pidof" <<'EOF'
#!/bin/sh
[ "$1" = system_server ] || exit 1
printf '4242\n'
EOF
chmod 0755 "$pidof"
export DSH_ANDROID_PROC="$proc" DSH_ANDROID_PIDOF="$pidof" DSH_ANDROID_FS_ROOT="$host"

(
  unset BOOTCLASSPATH DEX2OATBOOTCLASSPATH
  . "$ROOT/module/control/bin/dsh-android-env"
  assert_eq "$BOOTCLASSPATH" "$boot"
  assert_eq "$DEX2OATBOOTCLASSPATH" "$oat"
  assert_eq "${ANDROID_TEST_VALUE:-unset}" unset
)

(
  export BOOTCLASSPATH="$boot" DEX2OATBOOTCLASSPATH="$oat"
  DSH_ANDROID_PIDOF="$case_dir/missing-pidof"
  export DSH_ANDROID_PIDOF
  . "$ROOT/module/control/bin/dsh-android-env"
  assert_eq "$BOOTCLASSPATH" "$boot"
)

(
  unset BOOTCLASSPATH DEX2OATBOOTCLASSPATH
  DSH_ANDROID_PIDOF="$case_dir/missing-pidof"
  export DSH_ANDROID_PIDOF
  . "$ROOT/module/control/bin/dsh-android-env"
  assert_eq "${BOOTCLASSPATH:-unset}" unset
)

set +e
(
  export BOOTCLASSPATH="$boot:/system/framework/missing.jar"
  export DEX2OATBOOTCLASSPATH="$oat"
  . "$ROOT/module/control/bin/dsh-android-env"
)
missing_rc=$?
(
  export BOOTCLASSPATH='/system/framework/framework.jar;touch /tmp/not-allowed'
  export DEX2OATBOOTCLASSPATH="$oat"
  . "$ROOT/module/control/bin/dsh-android-env"
)
invalid_rc=$?
set -e
[ "$missing_rc" -ne 0 ] || fail 'missing jar was accepted'
[ "$invalid_rc" -ne 0 ] || fail 'unsafe classpath was accepted'
[ ! -e /tmp/not-allowed ] || fail 'classpath was executed'
printf 'android environment tests passed\n'
