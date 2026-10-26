/*
 * dsh-mount-ro-recursive — small mount helper for the DSH chroot holder.
 *
 * Modes:
 *   dsh-mount-ro-recursive PATH...              mark mounts read-only recursively
 *   dsh-mount-ro-recursive --make-rprivate PATH make a mount tree private recursively
 *   dsh-mount-ro-recursive --rbind SRC DST       recursive bind mount
 *   dsh-mount-ro-recursive --bind SRC DST        single bind mount
 *   dsh-mount-ro-recursive --remount-ro PATH      remount a mount read-only
 *   dsh-mount-ro-recursive --remount-ro-bind PATH remount a bind read-only
 *
 * The read-only mode uses mount_setattr(2) with AT_RECURSIVE; the private mode
 * uses mount(2) with MS_REC|MS_PRIVATE. Both are needed because Android's
 * toybox mount does not expose robust propagation/recursive helpers.
 *
 * Build (aarch64, static preferred):
 *   aarch64-linux-gnu-gcc -O2 -static -o dsh-mount-ro-recursive \
 *       dsh-mount-ro-recursive.c
 *
 * Termux clang may produce a bionic-linked binary; verify on the target.
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/mount.h>
#include <stdio.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef AT_RECURSIVE
#define AT_RECURSIVE 0x8000
#endif

#ifndef MOUNT_ATTR_RDONLY
#define MOUNT_ATTR_RDONLY 0x00000001
#endif

#ifndef __NR_mount_setattr
#error "__NR_mount_setattr is not defined for this architecture"
#endif

static int make_ro(const char *path) {
  struct mount_attr attr;
  memset(&attr, 0, sizeof(attr));
  attr.attr_set = MOUNT_ATTR_RDONLY;

  if (syscall(__NR_mount_setattr, AT_FDCWD, path, AT_RECURSIVE, &attr,
              sizeof(attr)) == 0) {
    return 0;
  }

  fprintf(stderr, "dsh-mount-ro-recursive: %s: %s\n", path, strerror(errno));
  return -1;
}

static int make_bind(const char *src, const char *dst, int recursive) {
  unsigned long flags = MS_BIND | (recursive ? MS_REC : 0);
  if (mount(src, dst, NULL, flags, NULL) == 0) {
    return 0;
  }
  fprintf(stderr, "dsh-mount-ro-recursive: %s %s -> %s: %s\n",
          recursive ? "--rbind" : "--bind", src, dst, strerror(errno));
  return -1;
}

static int remount_ro(const char *path, int bind) {
  unsigned long flags = MS_REMOUNT | MS_RDONLY | (bind ? MS_BIND : 0);
  if (mount(NULL, path, NULL, flags, NULL) == 0) {
    return 0;
  }
  fprintf(stderr, "dsh-mount-ro-recursive: %s %s: %s\n",
          bind ? "--remount-ro-bind" : "--remount-ro", path, strerror(errno));
  return -1;
}

static int make_rprivate(const char *path) {
  if (mount(NULL, path, NULL, MS_REC | MS_PRIVATE, NULL) == 0) {
    return 0;
  }
  fprintf(stderr, "dsh-mount-ro-recursive: --make-rprivate %s: %s\n", path,
          strerror(errno));
  return -1;
}

int main(int argc, char **argv) {
  int rc = 0;
  int i;

  if (argc < 2) {
    fprintf(stderr, "usage: %s [--make-rprivate PATH | --rbind SRC DST | --bind SRC DST | --remount-ro PATH | --remount-ro-bind PATH | PATH...]\n", argv[0]);
    return 2;
  }

  if (strcmp(argv[1], "--make-rprivate") == 0) {
    if (argc != 3) {
      fprintf(stderr, "usage: %s --make-rprivate PATH\n", argv[0]);
      return 2;
    }
    return make_rprivate(argv[2]) == 0 ? 0 : 1;
  }
  if (strcmp(argv[1], "--remount-ro") == 0 || strcmp(argv[1], "--remount-ro-bind") == 0) {
    if (argc != 3) {
      fprintf(stderr, "usage: %s %s PATH\n", argv[0], argv[1]);
      return 2;
    }
    return remount_ro(argv[2], strcmp(argv[1], "--remount-ro-bind") == 0) == 0 ? 0 : 1;
  }
  if (strcmp(argv[1], "--rbind") == 0 || strcmp(argv[1], "--bind") == 0) {
    if (argc != 4) {
      fprintf(stderr, "usage: %s %s SRC DST\n", argv[0], argv[1]);
      return 2;
    }
    return make_bind(argv[2], argv[3], strcmp(argv[1], "--rbind") == 0) == 0 ? 0 : 1;
  }

  for (i = 1; i < argc; i++) {
    if (make_ro(argv[i]) != 0) {
      rc = 1;
    }
  }
  return rc;
}
