#!/bin/sh
# Validate Node/DSH/node-pty and an s6 supervision tree without mounting.
# The guest needs a usable /dev/null; Android runtime verification uses the
# private namespace's bound /dev instead of a bare extracted /dev.
set -eu
ROOTFS_DIR=${ROOTFS_DIR:-/srv/dsh-rootfs}
NODE_VERSION=${NODE_VERSION:-24.18.0}
DSH_VERSION=${DSH_VERSION:-0.2.0-rc.2}
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
for f in bin/sh bin/bash usr/bin/env usr/bin/s6-svscan usr/bin/s6-svscanctl \
  usr/bin/s6-svc usr/bin/s6-svwait usr/bin/go opt/node/bin/node opt/dsh/current/bin/dsh \
  opt/dsh/current/package-lock.json; do
  [ -e "$ROOTFS_DIR/$f" ] || {
    echo "missing: $ROOTFS_DIR/$f" >&2
    exit 1
  }
done
# shellcheck disable=SC2016
chroot "$ROOTFS_DIR" /usr/bin/env -i HOME=/root \
  PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  TMPDIR=/tmp LANG=C.UTF-8 /bin/sh -c '
set -e
trap "rm -rf /tmp/dsh-go-smoke /tmp/dsh-s6-smoke" EXIT
[ "$(node --version)" = "v$1" ]
[ "$(/opt/dsh/current/bin/dsh --version)" = "$2" ]
cd /opt/dsh/current
node -e "require(\"node-pty\"); console.log(\"node-pty ok\")"
go_bin=$(readlink -f "$(command -v go)")
case $go_bin in
*/bin/go) GOROOT=${go_bin%/bin/go} ;;
*) exit 1 ;;
esac
export GOROOT
[ "$(go env GOARCH)" = arm64 ]
dir=/tmp/dsh-go-smoke
rm -rf "$dir"
mkdir -p "$dir"
printf "%s\n" "package main" "import \"fmt\"" "func main() { fmt.Print(\"go-ok\") }" >"$dir/main.go"
(cd "$dir" && GO111MODULE=off GOPROXY=off GOCACHE="$dir/cache" go build -o hello main.go)
[ "$("$dir/hello")" = go-ok ]
printf "go_compile_run=ok\n"
rm -rf "$dir"
dir=/tmp/dsh-s6-smoke
rm -rf "$dir"
mkdir -p "$dir"
s6-svscan -c 1 "$dir" &
pid=$!
trap "kill -TERM \"$pid\" 2>/dev/null || true; wait \"$pid\" 2>/dev/null || true; rm -rf \"$dir\"" EXIT
i=0
ok=0
while [ "$i" -lt 50 ]; do
  if s6-svscanctl -t "$dir" >/dev/null 2>&1; then
    ok=1
    break
  fi
  sleep 0.1
  i=$((i + 1))
done
[ "$ok" -eq 1 ]
wait "$pid"
' sh "$NODE_VERSION" "$DSH_VERSION"
echo "verify-rootfs: ok"
