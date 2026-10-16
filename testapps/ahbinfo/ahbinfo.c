// ahbinfo: what a gralloc buffer of the guest looks like (the own host with its virtio-gpu and gfxstream, docs/own-host.md). Allocates
// AHardwareBuffers of known sizes and formats, prints their native_handle (fds and ints), and for the first fd of each
// the virtio-gpu resource behind it (PRIME on renderD128, RESOURCE_INFO). Then checks the read path a composer would
// use: a pattern written through AHardwareBuffer_lock comes back by TRANSFER_FROM_HOST + VIRTGPU_MAP + mmap.
// Run as root (adb shell, after adb root). 16.10.2026.
#include <android/hardware_buffer.h>
#include <dlfcn.h>
#include <drm/drm.h>
#include <drm/virtgpu_drm.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

typedef struct { int version, numFds, numInts; int data[0]; } native_handle_t;

static int g_drm = -1;

static uint32_t resourceOf(int fd, uint32_t* bo, uint32_t* size, uint32_t* blobMem)
{
    struct drm_prime_handle p = {.fd = fd};
    if (ioctl(g_drm, DRM_IOCTL_PRIME_FD_TO_HANDLE, &p)) { printf("    PRIME_FD_TO_HANDLE: %s\n", strerror(errno)); return 0; }
    struct drm_virtgpu_resource_info ri = {.bo_handle = p.handle};
    if (ioctl(g_drm, DRM_IOCTL_VIRTGPU_RESOURCE_INFO, &ri)) { printf("    RESOURCE_INFO: %s\n", strerror(errno)); return 0; }
    *bo = p.handle, *size = ri.size, *blobMem = ri.blob_mem;
    return ri.res_handle;
}

static AHardwareBuffer* show(const char* what, uint32_t w, uint32_t h, uint32_t format, uint64_t usage)
{
    const native_handle_t* (*getHandle)(const AHardwareBuffer*) = dlsym(RTLD_DEFAULT, "AHardwareBuffer_getNativeHandle");
    AHardwareBuffer_Desc d = {.width = w, .height = h, .layers = 1, .format = format, .usage = usage};
    AHardwareBuffer* b = NULL;
    int r = AHardwareBuffer_allocate(&d, &b);
    printf("%s %ux%u format %u usage 0x%llx: allocate %d\n", what, w, h, format, (unsigned long long)usage, r);
    if (r || !getHandle) return NULL;
    AHardwareBuffer_Desc o;
    AHardwareBuffer_describe(b, &o);
    printf("  describe: stride %u\n", o.stride);
    const native_handle_t* nh = getHandle(b);
    printf("  handle: numFds %d numInts %d\n", nh->numFds, nh->numInts);
    for (int i = 0; i < nh->numFds; i++)
    {
        char link[64], target[128] = "";
        snprintf(link, sizeof link, "/proc/self/fd/%d", nh->data[i]);
        ssize_t n = readlink(link, target, sizeof target - 1);
        if (n > 0) target[n] = 0;
        uint32_t bo = 0, size = 0, blobMem = 0;
        uint32_t res = resourceOf(nh->data[i], &bo, &size, &blobMem);
        printf("  fd[%d] %s: resource %u bo %u size %u blob_mem %u\n", i, target, res, bo, size, blobMem);
    }
    printf("  ints:");
    for (int i = 0; i < nh->numInts; i++) printf(" [%d]0x%x", i, (unsigned)nh->data[nh->numFds + i]);
    printf("\n");
    return b;
}

int main(void)
{
    g_drm = open("/dev/dri/renderD128", O_RDWR | O_CLOEXEC);
    if (g_drm < 0) { perror("renderD128"); return 1; }
    const uint64_t gpu = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    const uint64_t cpu = AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN;
    show("A", 1280, 800, AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM, gpu | AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY);
    show("B", 333, 77, AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM, gpu);
    show("C", 333, 77, AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM, gpu);
    show("D", 640, 480, AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM, gpu);
    show("E", 640, 480, 5 /* HAL_PIXEL_FORMAT_BGRA_8888 */, gpu);
    AHardwareBuffer* t = show("T", 301, 50, AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM, gpu | cpu);
    if (!t) return 0;
    // Pattern through the mapper, then read back the way a composer without the mapper would.
    AHardwareBuffer_Desc o;
    AHardwareBuffer_describe(t, &o);
    void* p = NULL;
    int r = AHardwareBuffer_lock(t, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, &p);
    printf("lock for write: %d (%p)\n", r, p);
    if (r) return 0;
    for (uint32_t y = 0; y < o.height; y++)
        for (uint32_t x = 0; x < o.width; x++) ((uint32_t*)p)[y * o.stride + x] = 0xff000000u | (y << 8) | x;
    AHardwareBuffer_unlock(t, NULL);
    const native_handle_t* (*getHandle)(const AHardwareBuffer*) = dlsym(RTLD_DEFAULT, "AHardwareBuffer_getNativeHandle");
    const native_handle_t* nh = getHandle(t);
    uint32_t bo = 0, size = 0, blobMem = 0;
    if (!resourceOf(nh->data[0], &bo, &size, &blobMem)) return 0;
    struct drm_virtgpu_3d_transfer_from_host tr = {.bo_handle = bo, .box = {0, 0, 0, o.width, o.height, 1}};
    r = ioctl(g_drm, DRM_IOCTL_VIRTGPU_TRANSFER_FROM_HOST, &tr);
    printf("TRANSFER_FROM_HOST: %d %s\n", r, r ? strerror(errno) : "");
    struct drm_virtgpu_3d_wait wt = {.handle = bo};
    r = ioctl(g_drm, DRM_IOCTL_VIRTGPU_WAIT, &wt);
    printf("WAIT: %d\n", r);
    struct drm_virtgpu_map m = {.handle = bo};
    r = ioctl(g_drm, DRM_IOCTL_VIRTGPU_MAP, &m);
    printf("MAP: %d offset 0x%llx\n", r, (unsigned long long)m.offset);
    if (r) return 0;
    uint32_t* q = mmap(NULL, size, PROT_READ, MAP_SHARED, g_drm, (off_t)m.offset);
    if (q == MAP_FAILED) { perror("mmap"); return 0; }
    int bad = 0, firstBad = -1;
    for (uint32_t y = 0; y < o.height; y++)
        for (uint32_t x = 0; x < o.width; x++)
        {
            uint32_t want = 0xff000000u | (y << 8) | x;
            if (q[y * o.stride + x] != want) { if (firstBad < 0) firstBad = (int)(y * o.stride + x); bad++; }
        }
    printf("readback: %d of %u pixels differ (first at %d: 0x%08x)\n", bad, o.width * o.height, firstBad, firstBad >= 0 ? q[firstBad] : 0);
    return 0;
}
