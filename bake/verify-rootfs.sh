#!/bin/sh
# Read-only checks over a prepared rootfs directory. No mount required.
set -eu
ROOTFS_DIR=${ROOTFS_DIR:-/srv/dsh-rootfs}
for f in bin/sh usr/bin/env usr/bin/s6-svscan opt/node/bin/node opt/dsh/current/bin/dsh; do
  [ -e "$ROOTFS_DIR/$f" ] || {
    echo "missing: $ROOTFS_DIR/$f" >&2
    exit 1
  }
done
chroot "$ROOTFS_DIR" /bin/sh -c '
set -e
node -e "require(\"node-pty\"); console.log(\"node-pty ok\")"
/opt/dsh/current/bin/dsh --version
s6-svscan -V 2>/dev/null || true
'
echo "verify-rootfs: ok"
