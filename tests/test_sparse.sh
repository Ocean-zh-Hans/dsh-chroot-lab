#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"
rm -f "$DSH_MODULE_DIR/tools/bin/cp"
cat >"$CASE/bin/cp" <<'EOF'
#!/bin/sh
if [ "${1:-}" = --help ]; then
  printf 'Usage: cp [--sparse=always] SOURCE DEST\n'
  exit 0
fi
printf '%s\n' "$*" >>"$DSH_TEST_ROOT/cp.log"
exec /usr/bin/cp "$@"
EOF
cat >"$CASE/bin/dd" <<'EOF'
#!/bin/sh
printf 'misleading sparse help\n' >>"$DSH_TEST_ROOT/dd.log"
exit 1
EOF
chmod 0755 "$CASE/bin/cp" "$CASE/bin/dd"
hash -r
export DSH_TEST_ROOT="$CASE"
. "$MODULE/lib/common.sh"
. "$MODULE/lib/snapshot.sh"

src="$CASE/source.img"
dst="$CASE/dest.img"
truncate -s 1048576 "$src"
printf 'sparse-data' | /usr/bin/dd of="$src" bs=1 seek=4096 conv=notrunc status=none
dsh_cp_sparse "$src" "$dst"
assert_eq "$(dsh_sha256 "$dst")" "$(dsh_sha256 "$src")"
assert_eq "$(stat -c %s "$dst")" "$(stat -c %s "$src")"
assert_contains "$(cat "$CASE/cp.log")" '--sparse=always'
[ ! -e "$CASE/dd.log" ] || fail 'unverified dd fallback was used'

cat >"$CASE/bin/cp" <<'EOF'
#!/bin/sh
printf 'toybox cp without sparse support\n'
EOF
chmod 0755 "$CASE/bin/cp"
hash -r
set +e
dsh_cp_sparse "$src" "$CASE/unsupported.img"
rc=$?
set -e
[ "$rc" -ne 0 ] || fail 'unsupported sparse copy returned success'
[ ! -e "$CASE/unsupported.img" ] || fail 'unsupported copy left an image'
printf 'test_sparse: ok\n'
