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
OUT_DIR=$(dirname "$IMAGE_PATH")
mkdir -p "$OUT_DIR"
[ -x "$ROOTFS_DIR/bin/sh" ] || {
  echo "rootfs not found: $ROOTFS_DIR" >&2
  exit 1
}
rm -f "$IMAGE_PATH"
truncate -s "$IMAGE_SIZE" "$IMAGE_PATH"
mke2fs -t ext4 -b 4096 -d "$ROOTFS_DIR" "$IMAGE_PATH"
e2fsck -p "$IMAGE_PATH" || [ "$?" -eq 1 ]
sha256sum "$IMAGE_PATH" >"$IMAGE_PATH.sha256"
echo "built: $IMAGE_PATH"
