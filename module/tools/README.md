# Bundled tools

Binaries are **not committed**. `build/fetch-tools.sh` (P3) or a release
pipeline must place them under `module/tools/bin/` before a real module is
packaged:

- `losetup` — util-linux, must support `--sector-size` and `--direct-io=off`
- `mke2fs`, `e2fsck`, `resize2fs` — e2fsprogs
- `dsh-mount-ro-recursive` — tiny static helper using
  `mount_setattr(2)` + `AT_RECURSIVE` for `/apex`, `/linkerconfig`,
  `/metadata`, `/mnt/vendor/persist`, `/mnt/vendor/qmcs`
- optional: GNU `cp`/`tar`, static `busybox`

P1 `doctor` reports missing tools as WARN; `doctor --for-apply` reports them as
FAIL. No placeholder binaries are shipped.
