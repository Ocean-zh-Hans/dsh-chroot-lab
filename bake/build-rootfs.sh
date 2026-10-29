#!/bin/sh
# Build an Ubuntu 24.04 arm64 rootfs on an aarch64 Linux host/VM.
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

PROJECT_ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
ROOTFS_DIR=${ROOTFS_DIR:-/srv/dsh-rootfs}
NODE_VERSION=${NODE_VERSION:-24.18.0}
DSH_VERSION=${DSH_VERSION:-0.2.0-rc.2}
SUITE=${SUITE:-noble}
MIRROR=${MIRROR:-https://ports.ubuntu.com/ubuntu-ports}
OUT=${OUT:-$(pwd)/out}
case $ROOTFS_DIR in
/ | *..*)
  echo "unsafe rootfs directory: $ROOTFS_DIR" >&2
  exit 2
  ;;
esac
case $NODE_VERSION in
*[!0-9.]*)
  echo "invalid Node version" >&2
  exit 2
  ;;
esac
case $DSH_VERSION in
*[!A-Za-z0-9._-]*)
  echo "invalid DSH version" >&2
  exit 2
  ;;
esac
for command_name in debootstrap chroot curl tar sha256sum; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "missing required command: $command_name" >&2
    exit 2
  }
done
[ -f "$PROJECT_ROOT/bake/dsh-runtime.package-lock.json" ] || {
  echo "locked DSH dependencies are missing" >&2
  exit 2
}
mkdir -p "$ROOTFS_DIR" "$OUT"

if [ ! -x "$ROOTFS_DIR/bin/sh" ]; then
  debootstrap --arch=arm64 --variant=minbase --components=main,universe \
    --include=ca-certificates,curl,git,xz-utils,s6,build-essential,python3,jq,rsync,golang-go \
    "$SUITE" "$ROOTFS_DIR" "$MIRROR"
fi
cp -L /etc/resolv.conf "$ROOTFS_DIR/etc/resolv.conf" 2>/dev/null || true
chroot "$ROOTFS_DIR" /bin/bash -c '
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends ca-certificates curl git xz-utils s6 build-essential python3 jq rsync golang-go
apt-get install -y --no-install-recommends ttyd 2>/dev/null || true
apt-get clean
rm -rf /var/lib/apt/lists/*
'

NODE_TAR=node-v${NODE_VERSION}-linux-arm64.tar.xz
NODE_URL=https://nodejs.org/dist/v${NODE_VERSION}/${NODE_TAR}
mkdir -p "$ROOTFS_DIR/opt"
if [ -x "$ROOTFS_DIR/opt/node/bin/node" ]; then
  actual_node=$(chroot "$ROOTFS_DIR" /opt/node/bin/node --version)
  [ "$actual_node" = "v$NODE_VERSION" ] || {
    echo "existing Node is $actual_node, expected v$NODE_VERSION; use a clean rootfs" >&2
    exit 1
  }
else
  [ ! -e "$ROOTFS_DIR/opt/node" ] || {
    echo "incomplete Node installation; use a clean rootfs or repair it explicitly" >&2
    exit 1
  }
  curl -fL "$NODE_URL" -o "$OUT/$NODE_TAR"
  curl -fL "https://nodejs.org/dist/v${NODE_VERSION}/SHASUMS256.txt" -o "$OUT/SHASUMS256.txt"
  expected=$(awk -v file="$NODE_TAR" '$2 == file { print $1 }' "$OUT/SHASUMS256.txt")
  actual=$(sha256sum "$OUT/$NODE_TAR")
  actual=${actual%% *}
  [ -n "$expected" ] && [ "$actual" = "$expected" ] || {
    echo "Node download SHA256 mismatch" >&2
    exit 1
  }
  tar -xJf "$OUT/$NODE_TAR" -C "$ROOTFS_DIR/opt"
  mv "$ROOTFS_DIR/opt/node-v${NODE_VERSION}-linux-arm64" "$ROOTFS_DIR/opt/node"
  ln -sf /opt/node/bin/node "$ROOTFS_DIR/usr/local/bin/node"
  ln -sf /opt/node/bin/npm "$ROOTFS_DIR/usr/local/bin/npm"
  ln -sf /opt/node/bin/npx "$ROOTFS_DIR/usr/local/bin/npx"
fi

if [ -x "$ROOTFS_DIR/opt/dsh/current/bin/dsh" ]; then
  actual_dsh=$(chroot "$ROOTFS_DIR" /opt/dsh/current/bin/dsh --version)
  [ "$actual_dsh" = "$DSH_VERSION" ] || {
    echo "existing DSH is $actual_dsh, expected $DSH_VERSION; use a clean rootfs" >&2
    exit 1
  }
else
  mkdir -p "$ROOTFS_DIR/opt/dsh/versions/$DSH_VERSION"
  cp "$PROJECT_ROOT/bake/dsh-runtime.package.json" "$ROOTFS_DIR/opt/dsh/versions/$DSH_VERSION/package.json"
  cp "$PROJECT_ROOT/bake/dsh-runtime.package-lock.json" "$ROOTFS_DIR/opt/dsh/versions/$DSH_VERSION/package-lock.json"
  chroot "$ROOTFS_DIR" /bin/bash -c "
set -e
export PATH=/opt/node/bin:/usr/local/bin:/usr/bin:/bin
export npm_config_update_notifier=false
cd /opt/dsh/versions/$DSH_VERSION
npm ci --omit=dev --no-audit --no-fund
"
  ln -sfn "versions/$DSH_VERSION" "$ROOTFS_DIR/opt/dsh/current"
  ln -sfn "versions/$DSH_VERSION" "$ROOTFS_DIR/opt/dsh/previous"
fi

mkdir -p "$ROOTFS_DIR/opt/dsh/versions/$DSH_VERSION/bin"
cat >"$ROOTFS_DIR/opt/dsh/versions/$DSH_VERSION/bin/dsh" <<EOF
#!/bin/sh
exec /opt/node/bin/node /opt/dsh/versions/$DSH_VERSION/node_modules/@deepseek-ai/dsh/lib/bin.js "\$@"
EOF
chmod 0755 "$ROOTFS_DIR/opt/dsh/versions/$DSH_VERSION/bin/dsh"
chroot "$ROOTFS_DIR" /bin/bash -c '
set -e
export PATH=/opt/node/bin:/usr/local/bin:/usr/bin:/bin
cd /opt/dsh/current
node -e "require(\"node-pty\"); console.log(\"node-pty ok\")"
/opt/dsh/current/bin/dsh --version
'

TMP_ROOTFS=$OUT/rootfs.tar.gz.tmp.$$
trap 'rm -f "$TMP_ROOTFS"' EXIT HUP INT TERM
(cd "$ROOTFS_DIR" && tar -czf "$TMP_ROOTFS" .)
HASH=$(sha256sum "$TMP_ROOTFS")
HASH=${HASH%% *}
printf '%s  rootfs.tar.gz\n' "$HASH" >"$OUT/rootfs.tar.gz.sha256"
mv "$TMP_ROOTFS" "$OUT/rootfs.tar.gz"
if [ -f "$OUT/SHA256SUMS" ]; then
  awk '$NF != "rootfs.tar.gz" { print }' "$OUT/SHA256SUMS" >"$OUT/SHA256SUMS.tmp.$$" || true
else
  : >"$OUT/SHA256SUMS.tmp.$$"
fi
cat "$OUT/SHA256SUMS.tmp.$$" "$OUT/rootfs.tar.gz.sha256" >"$OUT/SHA256SUMS"
rm -f "$OUT/SHA256SUMS.tmp.$$"
trap - EXIT HUP INT TERM
echo "built: $OUT/rootfs.tar.gz"
