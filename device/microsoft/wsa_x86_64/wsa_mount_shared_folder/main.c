/*
 * wsa_mount_shared_folder — mount a Windows shared folder (virtiofs) inside /data/media/0.
 *
 *   wsa_mount_shared_folder <virtiofs tag> <target> [<directory whose SELinux label to copy>]
 *
 * Example (this is how init calls it, see wsa_mount_shared_folder.rc):
 *   wsa_mount_shared_folder windows_user /data/media/0/Windows /data/media/0/Documents
 *
 * The host gives the guest the Windows user folder as a virtiofs disk tagged windows_user — only if
 * "share user folders" is enabled in the WSA settings (WsaService decides when starting the
 * VM). It cannot be described in fstab: /data/media/0 appears only after the user is unlocked.
 * The label is taken from a sibling directory created by MediaProvider, so that the external storage
 * FUSE daemon can handle the files inside the same way as the rest.
 *
 * After a successful mount external_storage.<tag>.mounted=1 is set — the bridge's "FileSystem"
 * channel (FileSystemBridge) watches it — and external_storage.virtiofs.enabled=1.
 *
 * At Microsoft this is /system_ext/bin/mount_shared_folder (source device/microsoft/latte/native/bin/
 * mount_shared_folder/main.cpp, unpublished); the behaviour is reproduced from its strings and the reference
 * init script, the code is our own. Runs in the vold domain: the file is labeled vold_exec, and vold's permissions for
 * such a mount are in sepolicy/wsa_shared_folder.te. Analysis — docs/wsa-shell-protocol.md, section
 * "Windows user shared folder".
 */
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/system_properties.h>
#include <sys/xattr.h>

#define TAG "wsa_mount_shared_folder: "

int main(int argc, char **argv) {
    if (argc < 3 || argc > 4) {
        fprintf(stderr, "USAGE: %s <device name> <target path> [<selinux source path>]\n", argv[0]);
        return 2;
    }
    const char *device = argv[1];
    const char *target = argv[2];

    char data[512] = "";
    if (argc == 4) {
        char ctx[256];
        ssize_t n = getxattr(argv[3], "security.selinux", ctx, sizeof(ctx) - 1);
        if (n <= 0) {
            fprintf(stderr, TAG "get selinux context failed: path=%s errno=%d\n", argv[3], errno);
            return 1;
        }
        ctx[n] = '\0';  /* the label is stored with a trailing NUL, but we do not rely on that */
        /* Quotes are mandatory: user directories have a label with categories
         * (…:s0:c<N>,c<M>,…), and without them the kernel splits parameters at commas —
         * "virtiofs: Unknown parameter 'c256'". Caught in a live run on 24.09.2026. */
        snprintf(data, sizeof(data), "context=\"%s\"", ctx);
    }

    if (mount(device, target, "virtiofs", MS_NOSUID | MS_NODEV | MS_NOATIME, data) != 0) {
        fprintf(stderr, TAG "mount virtiofs failed: device=%s target=%s data=%s errno=%d (%s)\n",
                device, target, data, errno, strerror(errno));
        return 1;
    }
    fprintf(stderr, TAG "mounted %s at %s (%s)\n", device, target, data);

    /* Not PROP_NAME_MAX: that is the old 32-character limit, and the name would be truncated to
     * "external_storage.windows_user.m". Android has long accepted long property names. */
    char prop[128];
    snprintf(prop, sizeof(prop), "external_storage.%s.mounted", device);
    if (__system_property_set(prop, "1") != 0) {
        fprintf(stderr, TAG "set property %s=1 failed\n", prop);
    }
    if (__system_property_set("external_storage.virtiofs.enabled", "1") != 0) {
        fprintf(stderr, TAG "set property external_storage.virtiofs.enabled=1 failed\n");
    }
    return 0;
}
