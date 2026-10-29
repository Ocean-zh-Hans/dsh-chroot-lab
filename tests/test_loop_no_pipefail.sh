#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -u
. "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"
set +o pipefail

case_dir=$(new_case)
fixture_module "$case_dir/module"
fixture_env "$case_dir"
export DSH_CTL
export DSH_TEST_ROOT="$CASE"
export DSH_TEST_ALLOW_NON_DEV_LOOP=1
export DSH_TEST_ALLOW_SAME_NS=1
export DSH_TEST_SKIP_NS_SCAN=1
export DSH_TEST_LOOP="$CASE/loop99"
: >"$DSH_TEST_LOOP"

cat >"$CASE/bin/losetup" <<'EOF'
#!/bin/sh
case "${1:-}" in
  -j) exit 1 ;;
  --help) printf '%s\n' '--sector-size --direct-io' ;;
  *) exit 0 ;;
esac
EOF
cat >"$CASE/bin/e2fsck" <<'EOF'
#!/bin/sh
printf 'called\n' >>"$DSH_TEST_ROOT/e2fsck-called"
exit 0
EOF
chmod 0755 "$CASE/bin/losetup" "$CASE/bin/e2fsck"
. "$DSH_MODULE_DIR/lib/common.sh"
. "$DSH_MODULE_DIR/lib/loop.sh"
if dsh_loop_attached_image "$DSH_IMAGE" >/dev/null 2>&1; then
  fail "loop query failure was returned as success"
fi
printf '%s  %s\n' "$(sha256sum "$DSH_IMAGE" | awk '{print $1}')" 'rootfs.img' >"$CASE/image.sha256"
if [ "${OS:-}" = Windows_NT ]; then
  MKSH=$(printf 'sh\n')
else
  MKSH=$(command -v mksh 2>/dev/null || printf 'sh\n')
fi
for command in 'start --apply' 'snapshot create bad' 'image install --source '$DSH_IMAGE' --sha256 '$CASE'/image.sha256 --apply'; do
  export DSH_TEST_COMMAND="$command"
  set +e
  # shellcheck disable=SC2016
  out=$("$MKSH" -c 'set +o pipefail; "$DSH_CTL" $DSH_TEST_COMMAND' 2>&1)
  rc=$?
  set -e
  [ "$rc" -ne 0 ] || fail "command succeeded after loop query failure: $command"
  assert_contains "$out" "loop"
  [ ! -e "$CASE/e2fsck-called" ] || fail "fsck ran after loop query failure: $command"
  case $command in
  image*)
    for tmp in "$DSH_IMAGE_DIR"/rootfs.img.import.*.tmp; do
      [ ! -e "$tmp" ] || fail "image copy continued after query failure"
    done
    ;;
  esac
done
rm -f "$DSH_IMAGE"
assert_eq "$(dsh_loop_attached_image "$DSH_IMAGE")" ""
printf 'test_loop_no_pipefail: ok\n'
