// Own guest init for the own host: replaces WSL's init (wsainit) in the initrd, so the VM
// needs no host-side init protocol. It mounts /proc, /sys and /dev, waits for the Android system disk (SCSI LUN 0 —
// the same address the fstab uses), mounts it read-only as the new root and execs its /init: Android's first stage,
// which mounts vendor/system_ext/product/data itself from fstab.<suffix>.windows (SCSI=0:0:0:N).
// Built static: host/guest-init/build.sh. 12.10.2026.
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <unistd.h>

static void klog(const char* fmt, ...)
{
    char buf[512];
    int n = snprintf(buf, sizeof buf, "<6>own-init: ");
    va_list ap;
    va_start(ap, fmt);
    n += vsnprintf(buf + n, sizeof buf - n, fmt, ap);
    va_end(ap);
    int fd = open("/dev/kmsg", O_WRONLY | O_CLOEXEC);
    if (fd >= 0) { if (write(fd, buf, n) < 0) {} close(fd); }
}

// Name of the block device behind a SCSI address, e.g. "0:0:0:0" -> "sda".
static int scsi_block(const char* addr, char* out, size_t len)
{
    char path[128];
    snprintf(path, sizeof path, "/sys/bus/scsi/devices/%s/block", addr);
    DIR* d = opendir(path);
    if (!d) return -1;
    struct dirent* e;
    int found = -1;
    while ((e = readdir(d)))
        if (e->d_name[0] != '.') { snprintf(out, len, "%s", e->d_name); found = 0; break; }
    closedir(d);
    return found;
}

int main(void)
{
    mount("devtmpfs", "/dev", "devtmpfs", 0, NULL);
    mount("proc", "/proc", "proc", 0, NULL);
    mount("sysfs", "/sys", "sysfs", 0, NULL);
    klog("started\n");

    char dev[32], node[64];
    int tries = 0;
    while (scsi_block("0:0:0:0", dev, sizeof dev) < 0 && tries++ < 200) usleep(50000);
    if (tries > 200) { klog("no SCSI 0:0:0:0 after 10 s\n"); return 1; }
    snprintf(node, sizeof node, "/dev/%s", dev);
    klog("system disk %s\n", node);

    mkdir("/newroot", 0755);
    if (mount(node, "/newroot", "ext4", MS_RDONLY, NULL) < 0) { klog("mount %s: %s\n", node, strerror(errno)); return 1; }

    // Android's first stage mounts /dev, /proc and /sys itself.
    umount2("/proc", MNT_DETACH);
    umount2("/sys", MNT_DETACH);
    umount2("/dev", MNT_DETACH);

    if (chdir("/newroot") < 0 || mount(".", "/", NULL, MS_MOVE, NULL) < 0 || chroot(".") < 0 || chdir("/") < 0)
    {
        int err = errno;
        mount("devtmpfs", "/dev", "devtmpfs", 0, NULL);
        klog("switch root: %s\n", strerror(err));
        return 1;
    }
    char* argv[] = {"/init", NULL};
    char* envp[] = {"PATH=/system/bin", NULL};
    execve("/init", argv, envp);
    return 1;
}
