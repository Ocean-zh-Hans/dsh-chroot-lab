#!/bin/sh
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail
export MSYS2_ARG_CONV_EXCL='*' MSYS_NO_PATHCONV=1
ROOT=$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
BASE=/data/adb/dsh-agent
JAR=$BASE/dsh-root-helper.jar
PID_FILE=$BASE/helper.pid
START_FILE=$BASE/helper.starttime
LOCAL_JAR=$ROOT/agent-root-helper/build/dsh-root-helper.jar

adb_cmd() {
  if [ -n "${DSH_ADB_SERIAL:-}" ]; then
    adb -s "$DSH_ADB_SERIAL" "$@"
  else
    adb "$@"
  fi
}

host_path() {
  if [ -x /usr/bin/cygpath ]; then
    /usr/bin/cygpath -w "$1"
  else
    printf '%s\n' "$1"
  fi
}

remote() {
  _encoded=$(printf '%s' "$*" | base64 -w0)
  adb_cmd shell "su -c 'printf %s $_encoded | base64 -d | /system/bin/sh'"
}

pid_alive() {
  _pid=$(remote "cat $PID_FILE 2>/dev/null") || return 1
  case $_pid in
  '' | *[!0-9]*) return 1 ;;
  esac
  _start=$(remote "awk '{ sub(/^.*\\) /, \"\"); print \$(20) }' /proc/$_pid/stat 2>/dev/null") || return 1
  case $_start in
  '' | *[!0-9]*) return 1 ;;
  esac
  _expected=$(remote "cat $START_FILE 2>/dev/null")
  [ -z "$_expected" ] || [ "$_start" = "$_expected" ] || return 1
  _cmd=$(remote "tr '\\000' ' ' </proc/$_pid/cmdline 2>/dev/null")
  case $_cmd in
  *RootHelper*) return 0 ;;
  *) return 1 ;;
  esac
}

stop() {
  if ! pid_alive; then
    printf 'root_helper=stopped\n'
    return 0
  fi
  _pid=$(remote "cat $PID_FILE")
  remote "kill -TERM $_pid"
  for _ in 1 2 3 4 5; do
    if ! pid_alive; then
      remote "rm -f $PID_FILE $START_FILE"
      printf 'root_helper=stopped\n'
      return 0
    fi
    sleep 1
  done
  if pid_alive; then
    remote "kill -KILL $_pid"
  fi
  remote "rm -f $PID_FILE $START_FILE"
  printf 'root_helper=stopped\n'
}

start() {
  if pid_alive; then
    printf 'root_helper=already_running\n'
    return 0
  fi
  remote "test -s $JAR" || {
    printf 'helper jar is missing; run install first\n' >&2
    return 2
  }
  remote "mkdir -p $BASE; chmod 0700 $BASE; nohup env ANDROID_DATA=/data ANDROID_ROOT=/system CLASSPATH=$JAR /system/bin/app_process -Djava.class.path=$JAR / dev.dsh.agent.RootHelper >$BASE/helper.log 2>&1 </dev/null & echo \$! >$PID_FILE"
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    if pid_alive; then
      _pid=$(remote "cat $PID_FILE")
      _start=$(remote "awk '{ sub(/^.*\\) /, \"\"); print \$(20) }' /proc/$_pid/stat")
      remote "printf '%s\\n' '$_start' >$START_FILE; chmod 0600 $START_FILE"
      printf 'root_helper=running\n'
      return 0
    fi
    sleep 1
  done
  remote "tail -n 8 $BASE/helper.log 2>/dev/null" >&2 || true
  printf 'root_helper=start_failed\n' >&2
  return 1
}

install() {
  [ -f "$LOCAL_JAR" ] || {
    printf 'missing %s; run ./agent-app/build.sh first\n' "$LOCAL_JAR" >&2
    return 2
  }
  unzip -t "$LOCAL_JAR" >/dev/null
  stop
  _tmp=/data/local/tmp/dsh-root-helper.$$.jar
  adb_cmd push "$(host_path "$LOCAL_JAR")" "$_tmp" >/dev/null
  remote "mkdir -p $BASE; chmod 0700 $BASE; cp $_tmp $JAR.tmp; chmod 0600 $JAR.tmp; mv $JAR.tmp $JAR; rm -f $_tmp"
  adb_cmd push "$(host_path "$ROOT/agent-app/root-helper.sh")" /data/local/tmp/dsh-root-helper.sh >/dev/null
  remote "cp /data/local/tmp/dsh-root-helper.sh $BASE/root-helper.sh; chmod 0755 $BASE/root-helper.sh; rm -f /data/local/tmp/dsh-root-helper.sh"
  printf 'root_helper=installed\n'
}

enable() {
  remote "if [ -e /data/adb/service.d/dsh-agent-root-helper.sh ] && ! grep -q DSH_AGENT_ROOT_HELPER /data/adb/service.d/dsh-agent-root-helper.sh; then exit 23; fi"
  adb_cmd push "$(host_path "$ROOT/agent-app/root-helper-service.sh")" /data/local/tmp/dsh-agent-root-helper.sh >/dev/null
  remote "mkdir -p /data/adb/service.d; cp /data/local/tmp/dsh-agent-root-helper.sh /data/adb/service.d/dsh-agent-root-helper.sh; chmod 0755 /data/adb/service.d/dsh-agent-root-helper.sh; rm -f /data/local/tmp/dsh-agent-root-helper.sh"
  printf 'root_helper_boot=enabled\n'
}

disable() {
  remote "if grep -q DSH_AGENT_ROOT_HELPER /data/adb/service.d/dsh-agent-root-helper.sh 2>/dev/null; then rm -f /data/adb/service.d/dsh-agent-root-helper.sh; fi"
  printf 'root_helper_boot=disabled\n'
}

case ${1:-status} in
install) install ;;
start) start ;;
stop) stop ;;
status)
  if pid_alive; then
    printf 'root_helper=running\n'
  else
    printf 'root_helper=stopped\n'
  fi
  ;;
enable) enable ;;
disable) disable ;;
*)
  printf 'usage: %s install|start|stop|status|enable|disable\n' "$0" >&2
  exit 2
  ;;
esac
