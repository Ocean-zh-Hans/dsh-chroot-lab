# Hooks

`hooks.json` wires the PreToolUse hardguard/guard and PostToolUse audit hooks.
`dsh-run` passes `/opt/dsh-control/dsh/hooks.patch.yml` with `--patch`, which
mounts `dsh-hooks-claude-code` and points it at
`/opt/dsh-control/hooks/hooks.json`. The patch is validated with
`dsh --profile web --dump-config --patch ...`; real hook execution still needs
a device test.

- hardguard: always on; blocks obvious global SELinux/prop/mount changes.
- deny/ask lists: active only when `/run/dsh/guard` contains `1`
  (`dsh-ctl guard on`).
- This is a command-path guardrail, not a security boundary. ttyd, compiled
  binaries, direct syscalls, and `/proc/1/root` can bypass it.
