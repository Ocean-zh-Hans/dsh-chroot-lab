/*
 * dsh-mount-ro-recursive — mark a mount and all submounts read-only.
 *
 * Uses mount_setattr(2) with AT_RECURSIVE. This is the only way to make
 * /apex, /linkerconfig, /metadata, persist and qmcs truly read-only without
 * pretending a top-level remount,ro,bind is recursive.
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

int main(int argc, char **argv) {
  int rc = 0;
  int i;

  if (argc < 2) {
    fprintf(stderr, "usage: %s PATH...\n", argv[0]);
    return 2;
  }

  for (i = 1; i < argc; i++) {
    if (make_ro(argv[i]) != 0) {
      rc = 1;
    }
  }
  return rc;
}
