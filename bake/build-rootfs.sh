#!/bin/sh
# Build an Ubuntu 24.04 arm64 rootfs. Host must be aarch64 Ubuntu/Debian.
# Never run this inside the Android chroot or on the primary phone.
set -eu

if [ "$(id -u)" != 0 ]; then
  echo "run as root on an arm64 Linux host/VM" >&2
  exit 1
fi
case "$(uname -m)" in
aarch64 | arm64) ;;
*)
  echo "this script expects aarch64; use an arm64 VM" >&2
  exit 1
  ;;
esac

ROOTFS_DIR=${ROOTFS_DIR:-/srv/dsh-rootfs}
NODE_VERSION=${NODE_VERSION:-24.18.0}
DSH_VERSION=${DSH_VERSION:-0.2.0-rc.2}
SUITE=${SUITE:-noble}
MIRROR=${MIRROR:-http://ports.ubuntu.com/ubuntu-ports}
OUT=${OUT:-$(pwd)/out}

command -v debootstrap >/dev/null 2>&1 || {
  echo "debootstrap missing: apt-get install debootstrap" >&2
  exit 1
}
mkdir -p "$ROOTFS_DIR" "$OUT"

if [ ! -x "$ROOTFS_DIR/bin/sh" ]; then
  debootstrap --arch=arm64 --variant=minbase --include=ca-certificates,curl,git,xz-utils,s6,build-essential,python3,jq,rsync "$SUITE" "$ROOTFS_DIR" "$MIRROR"
fi

cp -L /etc/resolv.conf "$ROOTFS_DIR/etc/resolv.conf" 2>/dev/null || true
chroot "$ROOTFS_DIR" /bin/bash -c '
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends ca-certificates curl git xz-utils s6 build-essential python3 jq rsync
# ttyd is optional; install it only if the distro provides it.
apt-get install -y --no-install-recommends ttyd 2>/dev/null || true
apt-get clean
rm -rf /var/lib/apt/lists/*
'

# Node 24. Keep the checksum check explicit; replace with the official
# SHASUMS256 entry when pinning a different build.
NODE_TAR=node-v${NODE_VERSION}-linux-arm64.tar.xz
NODE_URL=https://nodejs.org/dist/v${NODE_VERSION}/${NODE_TAR}
mkdir -p "$ROOTFS_DIR/opt"
if [ ! -x "$ROOTFS_DIR/opt/node/bin/node" ]; then
  curl -fL "$NODE_URL" -o "$OUT/$NODE_TAR"
  tar -xJf "$OUT/$NODE_TAR" -C "$ROOTFS_DIR/opt"
  mv "$ROOTFS_DIR/opt/node-v${NODE_VERSION}-linux-arm64" "$ROOTFS_DIR/opt/node"
  ln -sf /opt/node/bin/node "$ROOTFS_DIR/usr/local/bin/node"
  ln -sf /opt/node/bin/npm "$ROOTFS_DIR/usr/local/bin/npm"
  ln -sf /opt/node/bin/npx "$ROOTFS_DIR/usr/local/bin/npx"
fi

# DSH runtime is versioned; keep current/previous symlinks for rollback.
if [ ! -x "$ROOTFS_DIR/opt/dsh/current/bin/dsh" ]; then
  mkdir -p "$ROOTFS_DIR/opt/dsh/versions/$DSH_VERSION"
  chroot "$ROOTFS_DIR" /bin/bash -c "
set -e
export PATH=/opt/node/bin:/usr/local/bin:/usr/bin:/bin
export npm_config_unsafe_perm=true
cd /opt/dsh/versions/$DSH_VERSION
npm init -y >/dev/null 2>&1 || true
npm install --omit=dev @deepseek-ai/dsh@$DSH_VERSION
"
  ln -sfn "versions/$DSH_VERSION" "$ROOTFS_DIR/opt/dsh/current"
  ln -sfn "versions/$DSH_VERSION" "$ROOTFS_DIR/opt/dsh/previous"
fi

# Stable launcher used by dsh-run, independent of npm's .bin layout.
mkdir -p "$ROOTFS_DIR/opt/dsh/versions/$DSH_VERSION/bin"
cat >"$ROOTFS_DIR/opt/dsh/versions/$DSH_VERSION/bin/dsh" <<EOF
#!/bin/sh
exec /opt/node/bin/node /opt/dsh/versions/$DSH_VERSION/node_modules/@deepseek-ai/dsh/lib/bin.js "\$@"
EOF
chmod 0755 "$ROOTFS_DIR/opt/dsh/versions/$DSH_VERSION/bin/dsh"

# Fail early if native modules are missing rather than discovering at boot.
chroot "$ROOTFS_DIR" /bin/bash -c '
set -e
export PATH=/opt/node/bin:/usr/local/bin:/usr/bin:/bin
node -e "require(\"node-pty\"); console.log(\"node-pty ok\")"
/opt/dsh/current/bin/dsh --version
'

(cd "$ROOTFS_DIR" && tar -czf "$OUT/rootfs.tar.gz" .)
sha256sum "$OUT/rootfs.tar.gz" >"$OUT/rootfs.tar.gz.sha256"
echo "built: $OUT/rootfs.tar.gz"
