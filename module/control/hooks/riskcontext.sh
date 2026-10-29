#!/bin/sh
set -u
_input=$(cat 2>/dev/null || true)
_event=$(printf '%s' "$_input" | sed -n 's/.*"hook_event_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
case $_event in
SessionStart)
  _warn=1
  ;;
UserPromptSubmit)
  case $_input in
  *setenforce* | *resetprop* | *mkfs* | *blockdev* | *fastboot* | *selinux* | *rootfs.img* | *purge* | *dd\ of=* | *of=/dev/block* | *of=/dev/loop*) _warn=1 ;;
  *) _warn=0 ;;
  esac
  ;;
*)
  exit 0
  ;;
esac
[ "$_warn" -eq 1 ] || exit 0
printf '%s\n' "{\"hookSpecificOutput\":{\"hookEventName\":\"$_event\",\"additionalContext\":\"安全提醒：本项目假设操作者善意但可能粗心。不要写入块设备或活动 rootfs.img，包括通过 /proc/1/root 或 mknod 绕过路径限制。不要修改全局 SELinux/prop。路径遮罩不保护块设备。执行高风险操作前核对目标和 last-good；没有验证的恢复路径就不要继续。此提示不拦截命令。\"}}"
