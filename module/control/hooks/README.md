# Hooks

`hooks.json` adds visible risk context and PostToolUse audit without PreToolUse blocking.
When the profile-local permission preset is available, `dsh-run` requests `/opt/dsh-control/dsh/hooks.patch.yml`, which also defines `approval-free` and an opt-in `approval-guard` preset. If only that preset is missing, it links the same-version package from the current runtime into the profile without replacing an existing entry. If it cannot provide the preset, it explicitly requests `hooks-audit-only.patch.yml` and warns; if the audit dependency is missing too, it disables the patch. `/run/dsh/hooks.status` records the selected mode and reason, not proof of successful upstream loading. `CLAUDE_PLUGIN_ROOT` resolves to `/opt/dsh-control` in production.

- `riskcontext.sh` uses `SessionStart` and high-risk `UserPromptSubmit` `additionalContext` to warn about block devices, the active image and global SELinux/prop. It never returns a deny/ask decision and does not log the prompt.
- `PostToolUse` records tool metadata, not full command text, prompt content or credentials.
- guard defaults to off (`approval-free`); explicitly enabling it selects `approval-guard` for new sessions after a DSH/chroot restart. Existing sessions use `/permission`. It does not enable regex deny/ask filtering.
- Display labels are `自动执行（不弹批准）` for `approval-free` and `谨慎执行（逐项批准）` for `approval-guard`; the preset keys and protocol IDs stay unchanged.
- These are reminders for well-intentioned but potentially careless operators, not a security boundary. SessionStart context can miss the first request, and direct block-device writes remain possible.
