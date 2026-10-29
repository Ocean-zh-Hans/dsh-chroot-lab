#!/system/bin/sh
# DSH_AGENT_ROOT_HELPER
BASE=/data/adb/dsh-agent
PID_FILE=$BASE/helper.pid
START_FILE=$BASE/helper.starttime
JAR=$BASE/dsh-root-helper.jar
[ -s "$JAR" ] || exit 0
mkdir -p "$BASE"
chmod 0700 "$BASE"
nohup env ANDROID_DATA=/data ANDROID_ROOT=/system CLASSPATH="$JAR" \
  /system/bin/app_process -Djava.class.path="$JAR" / dev.dsh.agent.RootHelper \
  >"$BASE/helper.log" 2>&1 </dev/null &
echo $! >"$PID_FILE"
sleep 1
if [ -r "/proc/$(cat "$PID_FILE")/stat" ]; then
  awk '{ sub(/^.*\) /, ""); print $20 }' "/proc/$(cat "$PID_FILE")/stat" >"$START_FILE"
  chmod 0600 "$START_FILE"
fi
