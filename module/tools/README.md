# Bundled tools

Binaries are **not committed**. A release build places them under
`module/tools/bin/`; `build/build-tools.sh` builds the mount helper and installs
the GNU cp runtime from the pinned `build/android-tools.json` lock. These are
build-time package sources, not a runtime dependency on a Termux installation,
its paths, or its package manager. The production guest is Ubuntu on KernelSU.

- `losetup` — optional util-linux variant with `--sector-size`/`--direct-io=off`;
  Android toybox fallback is allowed with an explicit warning.
- `mke2fs`, `e2fsck` — filesystem creation/checking tools.
- `debugfs` — bundled in `libexec` with `libblkid`/`libuuid` and a local
  `LD_LIBRARY_PATH` wrapper. Doctor uses it read-only on cold images to verify
  guest Python and `/tmp`; it never debugfs-inspects a mounted filesystem.
- `dsh-mount-ro-recursive` — Android ARM64 helper using `mount_setattr(2)` +
  `AT_RECURSIVE` for `/apex`, `/linkerconfig`, `/metadata`,
  `/mnt/vendor/persist`, `/mnt/vendor/qmcs`; also supports private propagation,
  bind/rbind and remount operations. `--claim` provides an advisory lock shared
  by cooperating DSH image operations; it cannot exclude an unrelated root
  process or raw block-device access. Android NDK/bionic dynamic linking or a
  verified static binary is accepted; an arbitrary host-glibc binary is not.
- `unshare`/`nsenter` — optional util-linux variants; Android toybox fallback
  (`-m -u`, `-t PID -m --`) is wired and must be verified on the target.
  Namespace entry always uses `--` before the guest command; `--mount=FILE`
  support is checked with a harmless real execution, then falls back to `-t PID -m --`.
- sparse copy: verified GNU `cp --sparse=always`; size and content hashes are
  checked. `build/fetch-android-tools.py` uses the pinned package lock, verifies
  SHA256, ARM64 binaries, dependencies and licenses. `tools/bin/cp` sets
  `LD_LIBRARY_PATH` only for that command, not for Android or the chroot.
- `debugfs` has the same local-wrapper treatment. Its source lock also covers
  `libblkid`, `libuuid`, `libandroid-posix-semaphore` and license texts; common
  license filenames are normalized to contain no whitespace.
- optional: `tar`, static `busybox`.

`doctor --for-apply` fails for required tools that cannot be replaced by a
validated fallback. The default NDK is `28.2.13676358`; `ANDROID_NDK_HOME` can
select another installed NDK. `tools/SHA256SUMS` covers the helper, GNU cp and
its libraries. `tools/bin/BUILDINFO` records the NDK, ABI and API level.
`tools/bin`, `tools/libexec`, `tools/lib`, `tools/licenses` and `build/.tools`
are generated and ignored by git.
