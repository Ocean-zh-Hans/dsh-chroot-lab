# Hooks

`hooks.json` wires the PreToolUse hardguard/guard and PostToolUse audit hooks.
The DSH profile must reference `/opt/dsh-control/hooks/hooks.json` via
`dsh-hooks-claude-code`; that profile wiring is part of P3 verification.

- hardguard: always on; blocks obvious global SELinux/prop/mount changes.
- deny/ask lists: active only when `/run/dsh/guard` contains `1`
  (`dsh-ctl guard on`).
- This is a command-path guardrail, not a security boundary. ttyd, compiled
  binaries, direct syscalls, and `/proc/1/root` can bypass it.
