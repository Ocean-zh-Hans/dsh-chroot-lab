#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"
rm -rf "$DSH_IMAGE_DIR"
printf 'first-install' >"$CASE/first.img"
sha256sum "$CASE/first.img" >"$CASE/first.img.sha256"
out=$("$DSH_CTL" image install --source "$CASE/first.img" --apply)
assert_contains "$out" "installed image:"
assert_eq "$(cat "$DSH_IMAGE")" "first-install"
rm -f "$DSH_IMAGE"

SOURCE="$CASE/rootfs-source"
mkdir -p "$SOURCE/etc" "$SOURCE/usr" "$SOURCE/bin"
printf 'rootfs-fixture\n' >"$SOURCE/etc/os-release"
out=$("$DSH_CTL" image create --source "$SOURCE" --size=1M --apply)
assert_contains "$out" "created image:"
[ -f "$DSH_IMAGE" ] || fail "image not created"
set +e
out=$("$DSH_CTL" image create --source / --size=1M --apply 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "image create accepted / as source"
assert_contains "$out" "non-rootfs"
set +e
out=$("$DSH_CTL" image create --source "$SOURCE" --size=1G2M --apply 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "image create accepted malformed size"
assert_contains "$out" "invalid image size"
[ "$(stat -c %a "$DSH_IMAGE")" = "600" ] || fail "image mode is not 600"
initial_size=$(stat -c %s "$DSH_IMAGE")

set +e
out=$("$DSH_CTL" image create --source "$SOURCE" --size=1M 2>&1)
rc=$?
set -e
assert_eq "$rc" 3
assert_contains "$out" "use --apply"

source_img=$CASE/source.img
printf 'verified image' >"$source_img"
sha256sum "$source_img" >"$source_img.sha256"
set +e
out=$("$DSH_CTL" image install --source "$source_img" --apply 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "install replaced the current image without --replace"
assert_eq "$(stat -c %s "$DSH_IMAGE")" "$initial_size"
out=$("$DSH_CTL" image install --source "$source_img" --replace --apply)
assert_contains "$out" "installed image:"
assert_eq "$(cat "$DSH_IMAGE")" "verified image"
for previous in "$DSH_IMAGE_DIR"/rootfs-pre-install-*.img; do
  [ -f "$previous" ] || fail "previous image was not preserved"
done

printf 'tampered' >"$source_img"
set +e
out=$("$DSH_CTL" image install --source "$source_img" --replace --apply 2>&1)
rc=$?
set -e
[ "$rc" -ne 0 ] || fail "install accepted a bad checksum"
assert_eq "$(cat "$DSH_IMAGE")" "verified image"

set +e
out=$("$DSH_CTL" image create --source "$SOURCE" --size=1M 2>&1)
rc=$?
set -e
assert_eq "$rc" 3
assert_contains "$out" "source-size:"
set +e
out=$("$DSH_CTL" image install --source "$source_img" 2>&1)
rc=$?
set -e
assert_eq "$rc" 3
assert_contains "$out" "sha256:"

printf 'test_image: ok\n'
