// drmfill: shows a test picture on the first connected output of /dev/dri/card0 through plain KMS ioctls (the NDK has
// no libdrm): a dumb buffer with four colour bars and a white frame, SETCRTC, then DIRTYFB. On virtio-gpu that is
// RESOURCE_CREATE_2D, ATTACH_BACKING, SET_SCANOUT, TRANSFER_TO_HOST_2D and RESOURCE_FLUSH — the frame path of the
// own virtio-gpu (the host's "hdv gpu" line). Holds the picture for <seconds> (default 10). Needs root. 16.10.2026.
#include <drm/drm.h>
#include <drm/drm_mode.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

static int io(int fd, unsigned long req, void* arg, const char* what)
{
    int r;
    do r = ioctl(fd, req, arg); while (r < 0 && (errno == EINTR || errno == EAGAIN));
    if (r < 0) perror(what);
    return r;
}

int main(int argc, char** argv)
{
    int seconds = argc > 1 ? atoi(argv[1]) : 10;
    int fd = open("/dev/dri/card0", O_RDWR | O_CLOEXEC);
    if (fd < 0) { perror("card0"); return 1; }
    io(fd, DRM_IOCTL_SET_MASTER, NULL, "SET_MASTER");
    struct drm_mode_card_res res = {0};
    if (io(fd, DRM_IOCTL_MODE_GETRESOURCES, &res, "GETRESOURCES") < 0) return 1;
    uint32_t crtcs[8], conns[8], encs[8], fbs[8];
    res.crtc_id_ptr = (uintptr_t)crtcs; res.connector_id_ptr = (uintptr_t)conns; res.encoder_id_ptr = (uintptr_t)encs; res.fb_id_ptr = (uintptr_t)fbs;
    if (res.count_crtcs > 8) res.count_crtcs = 8;
    if (res.count_connectors > 8) res.count_connectors = 8;
    if (res.count_encoders > 8) res.count_encoders = 8;
    if (res.count_fbs > 8) res.count_fbs = 8;
    if (io(fd, DRM_IOCTL_MODE_GETRESOURCES, &res, "GETRESOURCES") < 0) return 1;
    printf("crtcs %u connectors %u encoders %u\n", res.count_crtcs, res.count_connectors, res.count_encoders);
    for (uint32_t c = 0; c < res.count_connectors; c++)
    {
        struct drm_mode_get_connector con = {.connector_id = conns[c]};
        if (io(fd, DRM_IOCTL_MODE_GETCONNECTOR, &con, "GETCONNECTOR") < 0) continue;
        struct drm_mode_modeinfo modes[128];  // the kernel copies only when all of them fit
        uint32_t cencs[8];
        if (con.count_modes > 128) con.count_modes = 128;
        if (con.count_encoders > 8) con.count_encoders = 8;
        con.modes_ptr = (uintptr_t)modes; con.encoders_ptr = (uintptr_t)cencs; con.count_props = 0;
        if (io(fd, DRM_IOCTL_MODE_GETCONNECTOR, &con, "GETCONNECTOR") < 0) continue;
        printf("connector %u: connection %u, %u modes\n", con.connector_id, con.connection, con.count_modes);
        if (con.connection != 1 || con.count_modes == 0) continue;
        struct drm_mode_modeinfo* m = &modes[0];
        printf("mode %s %ux%u@%u\n", m->name, m->hdisplay, m->vdisplay, m->vrefresh);
        struct drm_mode_create_dumb cd = {.height = m->vdisplay, .width = m->hdisplay, .bpp = 32};
        if (io(fd, DRM_IOCTL_MODE_CREATE_DUMB, &cd, "CREATE_DUMB") < 0) return 1;
        struct drm_mode_map_dumb md = {.handle = cd.handle};
        if (io(fd, DRM_IOCTL_MODE_MAP_DUMB, &md, "MAP_DUMB") < 0) return 1;
        uint8_t* px = mmap(NULL, cd.size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, md.offset);
        if (px == MAP_FAILED) { perror("mmap"); return 1; }
        // XRGB8888: red, green, blue, grey bars, a white frame 8 px wide.
        static const uint32_t bars[4] = {0x00ff0000, 0x0000ff00, 0x000000ff, 0x00808080};
        for (uint32_t y = 0; y < cd.height; y++)
            for (uint32_t x = 0; x < cd.width; x++)
            {
                uint32_t v = bars[x * 4 / cd.width];
                if (x < 8 || y < 8 || x >= cd.width - 8 || y >= cd.height - 8) v = 0x00ffffff;
                ((uint32_t*)(px + y * cd.pitch))[x] = v;
            }
        struct drm_mode_fb_cmd fb = {.width = cd.width, .height = cd.height, .pitch = cd.pitch, .bpp = 32, .depth = 24, .handle = cd.handle};
        if (io(fd, DRM_IOCTL_MODE_ADDFB, &fb, "ADDFB") < 0) return 1;
        uint32_t crtc = crtcs[0];
        struct drm_mode_crtc sc = {.set_connectors_ptr = (uintptr_t)&con.connector_id, .count_connectors = 1, .crtc_id = crtc, .fb_id = fb.fb_id, .mode_valid = 1, .mode = *m};
        if (io(fd, DRM_IOCTL_MODE_SETCRTC, &sc, "SETCRTC") < 0) return 1;
        struct drm_clip_rect clip = {0, 0, (unsigned short)cd.width, (unsigned short)cd.height};
        struct drm_mode_fb_dirty_cmd dirty = {.fb_id = fb.fb_id, .num_clips = 1, .clips_ptr = (uintptr_t)&clip};
        io(fd, DRM_IOCTL_MODE_DIRTYFB, &dirty, "DIRTYFB");
        printf("showing fb %u on crtc %u for %d s\n", fb.fb_id, crtc, seconds);
        sleep(seconds);
        return 0;
    }
    fprintf(stderr, "no connected output\n");
    return 1;
}
