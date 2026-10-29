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
#include <stdlib.h>
#include <sys/file.h>
#include <sys/stat.h>
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

static int claim_and_exec(const char *path, char **command,
                          unsigned long wait_seconds) {
  struct stat st;
  unsigned long waited = 0;
  int fd;
  for (;;) {
    fd = open(path, O_RDWR | O_CREAT | O_NOFOLLOW, 0600);
    if (fd < 0 || fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) ||
        fchmod(fd, 0600) != 0) {
      fprintf(stderr, "dsh-mount-ro-recursive: cannot claim %s: %s\n", path,
              strerror(errno));
      if (fd >= 0) close(fd);
      return 1;
    }
    if (flock(fd, LOCK_EX | LOCK_NB) == 0) break;
    int claim_errno = errno;
    int busy = claim_errno == EWOULDBLOCK || claim_errno == EAGAIN;
    if (!busy || waited >= wait_seconds) {
      fprintf(stderr, "dsh-mount-ro-recursive: cannot claim %s: %s\n", path,
              strerror(claim_errno));
      close(fd);
      return busy ? 75 : 1;
    }
    close(fd);
    if (sleep(1) != 0) {
      fprintf(stderr, "dsh-mount-ro-recursive: claim wait interrupted\n");
      return 1;
    }
    waited++;
  }
  char claim_fd[32];
  char claim_fds[256];
  const char *previous = getenv("DSH_CLAIM_FDS");
  int len;
  snprintf(claim_fd, sizeof(claim_fd), "%d", fd);
  if (previous != NULL && *previous != '\0') {
    len = snprintf(claim_fds, sizeof(claim_fds), "%s,%d", previous, fd);
  } else {
    len = snprintf(claim_fds, sizeof(claim_fds), "%d", fd);
  }
  if (len < 0 || (size_t)len >= sizeof(claim_fds) ||
      setenv("DSH_CLAIM_FD", claim_fd, 1) != 0 ||
      setenv("DSH_CLAIM_FDS", claim_fds, 1) != 0) {
    fprintf(stderr, "dsh-mount-ro-recursive: cannot publish claim fds: %s\n",
            strerror(errno));
    close(fd);
    return 1;
  }
  execvp(command[0], command);
  fprintf(stderr, "dsh-mount-ro-recursive: exec %s: %s\n", command[0],
          strerror(errno));
  close(fd);
  return 1;
}

int main(int argc, char **argv) {
  int rc = 0;
  int i;

  if (argc >= 4 && strcmp(argv[1], "--claim") == 0) {
    return claim_and_exec(argv[2], argv + 3, 0);
  }
  if (argc >= 5 && strcmp(argv[1], "--claim-wait") == 0) {
    char *end;
    unsigned long wait_seconds;
    errno = 0;
    wait_seconds = strtoul(argv[2], &end, 10);
    if (errno == ERANGE || argv[2][0] == '\0' || *end != '\0') {
      fprintf(stderr, "invalid claim wait: %s\n", argv[2]);
      return 2;
    }
    return claim_and_exec(argv[3], argv + 4, wait_seconds);
  }

  if (argc < 2) {
    fprintf(stderr, "usage: %s [--claim PATH COMMAND... | --claim-wait SECONDS PATH COMMAND... | --make-rprivate PATH | --rbind SRC DST | --bind SRC DST | --remount-ro PATH | --remount-ro-bind PATH | PATH...]\n", argv[0]);
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
