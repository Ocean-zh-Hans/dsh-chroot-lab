#!/bin/sh
# Create a sparse ext4 image from a prepared rootfs directory.
set -eu
if [ "$(id -u)" != 0 ]; then
  echo "run as root (mke2fs -d needs device/file access)" >&2
  exit 1
fi
ROOTFS_DIR=${ROOTFS_DIR:-/srv/dsh-rootfs}
IMAGE_SIZE=${IMAGE_SIZE:-128G}
IMAGE_PATH=${IMAGE_PATH:-$(pwd)/out/rootfs.img}
case $ROOTFS_DIR in
/ | *..*)
  echo "unsafe rootfs directory: $ROOTFS_DIR" >&2
  exit 2
  ;;
esac
[ -x "$ROOTFS_DIR/bin/sh" ] || {
  echo "rootfs not found: $ROOTFS_DIR" >&2
  exit 1
}
ROOTFS_ABS=$(CDPATH='' cd -- "$ROOTFS_DIR" && pwd)
OUT_DIR=$(dirname "$IMAGE_PATH")
mkdir -p "$OUT_DIR"
OUT_ABS=$(CDPATH='' cd -- "$OUT_DIR" && pwd)
IMAGE_PATH=$OUT_ABS/${IMAGE_PATH##*/}
case $IMAGE_PATH in
"$ROOTFS_ABS"/*)
  echo "image must be outside the source rootfs" >&2
  exit 1
  ;;
esac
[ ! -e "$IMAGE_PATH" ] || {
  echo "refusing to overwrite an existing image: $IMAGE_PATH" >&2
  exit 1
}
TMP_IMAGE=$IMAGE_PATH.tmp.$$
TMP_SUM=$IMAGE_PATH.sha256.tmp.$$
TMP_LIST=$OUT_DIR/SHA256SUMS.tmp.$$
TMP_AGG=$OUT_DIR/SHA256SUMS.new.$$
trap 'rm -f "$TMP_IMAGE" "$TMP_SUM" "$TMP_LIST" "$TMP_AGG"' EXIT HUP INT TERM
truncate -s "$IMAGE_SIZE" "$TMP_IMAGE"
mke2fs -t ext4 -b 4096 -d "$ROOTFS_DIR" "$TMP_IMAGE"
rc=0
e2fsck -p "$TMP_IMAGE" || rc=$?
[ "$rc" -le 1 ] || {
  echo "e2fsck failed: $rc" >&2
  exit 1
}
e2fsck -fn "$TMP_IMAGE" >/dev/null
HASH=$(sha256sum "$TMP_IMAGE")
HASH=${HASH%% *}
printf '%s  %s\n' "$HASH" "${IMAGE_PATH##*/}" >"$TMP_SUM"
chmod 0600 "$TMP_IMAGE"
mv "$TMP_IMAGE" "$IMAGE_PATH"
mv "$TMP_SUM" "$IMAGE_PATH.sha256"
if [ -f "$OUT_DIR/SHA256SUMS" ]; then
  awk -v name="${IMAGE_PATH##*/}" '$NF != name { print }' "$OUT_DIR/SHA256SUMS" >"$TMP_LIST" || true
else
  : >"$TMP_LIST"
fi
cat "$TMP_LIST" "$IMAGE_PATH.sha256" >"$TMP_AGG"
mv "$TMP_AGG" "$OUT_DIR/SHA256SUMS"
rm -f "$TMP_LIST"
trap - EXIT HUP INT TERM
echo "built: $IMAGE_PATH"
