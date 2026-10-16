/*
 * Copyright 2026 The WSA-Reborn Authors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Headless HWC2 module for WSA without a host GPU (the own host, docs/own-host.md): one internal display of a fixed
 * mode, everything composed by SurfaceFlinger itself (CLIENT), presentDisplay accepts the client target and shows it
 * nowhere, vsync comes from a timer. Microsoft's hwcomposer.windows.so hands every frame to the host renderer through
 * renderControl and dies without virtio-gpu; this module is dropped in its place, the service binary
 * (hwcomposer-service.windows, a passthrough loader of the "hwcomposer" module) stays.
 *
 * Mode: vendor.wsa.headless.mode = <width>x<height>@<fps> (default 1280x800@60), density from ro.sf.lcd_density
 * (default 200).
 *
 * Frame sink (15.10.2026): every presented client target is copied out of its dma-buf (minigbm dmaheap handle: one
 * linear plane, fds[0]) and sent to the host over vsock, port vendor.wsa.headless.frame_port (default 50100, 0 = off),
 * where host/host shows it in a window. A sender thread keeps only the latest frame; while no host listens it
 * reconnects every 2 s and nothing is copied. Wire format: struct FrameHeader, then height * stride bytes.
 *
 * With a host GPU (16.10.2026): the module also runs over Microsoft's own vendor graphics — gralloc and EGL of gfxstream
 * on the own host's virtio-gpu — in place of hwcomposer.windows.so, whose frames go to the host's GSK renderer that only
 * WsaClient has. Buffers are then Microsoft's handles (struct MsHandle): their pixels are fetched from the host with
 * TRANSFER_FROM_HOST and read through a mapping of the blob; frames and task pictures go to the host as above.
 *
 * Task windows (15.10.2026): SurfaceFlinger (patch 0006) tags every layer of a task with the generic metadata
 * com.microsoft.wsa.taskid (inherited from the task surface), the same key Microsoft's hwcomposer.windows.so groups
 * layers into host windows by. The module declares the key, remembers each layer's buffer and geometry and, after the
 * client target, composes every task on its own on the CPU from its buffer layers only (z order, source crop scaled
 * to the display frame, blending, plane alpha, flips/rotation) — so a task covered by another one in the guest is
 * still whole in its window. Sent are a task list (struct TaskListHeader + TaskRect each, whenever the set or a
 * rectangle changes) and a picture per changed task (struct TaskHeader + height * stride bytes, RGBA premultiplied).
 * The host makes a window per task and sends its input with targetTaskId. Layers without a task but of an overlay
 * window type (toast, soft keyboard, system alerts/dialogs, application overlays) go as the pseudo task -<type>, for a
 * popup window of their own on the host. vendor.wsa.headless.task_windows=0 turns
 * this off. Everything SurfaceFlinger composes stays CLIENT as before.
 */

#define LOG_TAG "WsaHeadlessHwc"

#include <hardware/hardware.h>
#include <hardware/hwcomposer2.h>

#include <cutils/properties.h>
#include <log/log.h>

#include <drm/drm.h>
#include <drm/virtgpu_drm.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/dma-buf.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/socket.h>  // before linux/vm_sockets.h, which needs struct sockaddr
#include <sys/stat.h>
#include <linux/vm_sockets.h>

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cstdio>
#include <climits>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <time.h>
#include <unistd.h>
#include <vector>

namespace {

constexpr hwc2_display_t kDisplay = 1;
constexpr hwc2_config_t kConfig = 1;

struct Mode {
    int32_t width = 1280, height = 800, fps = 60, dpi = 200;
    int64_t periodNs() const { return 1000000000LL / fps; }
};

Mode readMode() {
    Mode m;
    char v[PROPERTY_VALUE_MAX];
    property_get("vendor.wsa.headless.mode", v, "");
    int w, h, f = 60;
    if (sscanf(v, "%dx%d@%d", &w, &h, &f) >= 2 && w > 0 && h > 0 && f > 0) {
        m.width = w;
        m.height = h;
        m.fps = f;
    }
    m.dpi = property_get_int32("ro.sf.lcd_density", 200);
    return m;
}

int64_t nowNs() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

void closeFence(int32_t fd) {
    if (fd >= 0) close(fd);
}

// minigbm's cros_gralloc_handle (external/minigbm/cros_gralloc/cros_gralloc_handle.h), as the mapper hands it out.
constexpr int kDrvMaxPlanes = 4;
struct __attribute__((packed)) CrosHandle {
    native_handle_t base;
    int32_t fds[kDrvMaxPlanes + 1];
    uint32_t strides[kDrvMaxPlanes];
    uint32_t offsets[kDrvMaxPlanes];
    uint32_t sizes[kDrvMaxPlanes];
    uint32_t id, width, height, format, tiling;
    uint64_t formatModifier, useFlags;
    uint32_t magic, pixelStride;
    int32_t droidFormat;
    int64_t usage;
    uint32_t numPlanes;
    uint64_t reservedRegionSize, totalSize;
};
constexpr uint32_t kCrosMagic = 0xABCDDCBA;

// Microsoft's gralloc on virtio-gpu with gfxstream (vendor as is, own host with its own virtio-gpu, docs/own-host.md,
// 16.10.2026): one fd — the dma-buf of a host-visible blob — and 21 ints: [0] the virtio-gpu resource, [1] usage,
// [2] width, [3] height, [4] HAL format, [13] bytes per pixel, [14] stride in pixels, [20] magic (read with
// testapps/ahbinfo). The pixels live in the host's GPU: TRANSFER_FROM_HOST copies them into the blob, read through a
// VIRTGPU_MAP mapping, as Microsoft's mapper does on lock.
struct __attribute__((packed)) MsHandle {
    native_handle_t base;
    int32_t fd;
    int32_t ints[21];
    uint32_t width() const { return (uint32_t)ints[2]; }
    uint32_t height() const { return (uint32_t)ints[3]; }
    int32_t format() const { return ints[4]; }
    uint32_t stride() const { return (uint32_t)ints[14] * (uint32_t)ints[13]; }  // bytes
};
constexpr uint32_t kMsMagic = 0xABCDDCBB;

const MsHandle* msHandle(buffer_handle_t h) {
    const MsHandle* m = reinterpret_cast<const MsHandle*>(h);
    return m && m->base.numFds == 1 && m->base.numInts == 21 && (uint32_t)m->ints[20] == kMsMagic ? m : nullptr;
}

// DRM fourcc of the frame header for a HAL format, 0 for the ones the host does not take.
uint32_t drmFormat(int32_t hal) {
    switch (hal) {
        case 1: return 0x34324241;  // RGBA_8888 -> AB24
        case 2: return 0x34324258;  // RGBX_8888 -> XB24
        case 5: return 0x34325241;  // BGRA_8888 -> AR24
        default: return 0;
    }
}

// Mappings of Microsoft's buffers on renderD128, by dma-buf inode; the GEM handle keeps a buffer SurfaceFlinger has
// freed alive until the cache is cleared, so an inode is not reused meanwhile.
class VirtioReader {
  public:
    // Copies the buffer's pixels, fresh from the host, into out (stride * height bytes, stride and size from the
    // handle). Copied under the lock: the composer thread and the task pictures share the cache.
    bool read(const MsHandle* h, std::vector<uint8_t>& out) {
        std::lock_guard<std::mutex> l(lock_);
        if (drm_ < 0) {
            drm_ = open("/dev/dri/renderD128", O_RDWR | O_CLOEXEC);
            if (drm_ < 0) return fail("open renderD128");
        }
        struct stat st;
        if (fstat(h->fd, &st)) return false;
        auto it = maps_.find((uint64_t)st.st_ino);
        if (it == maps_.end()) {
            if (maps_.size() >= 32) clear();
            drm_prime_handle p{};
            p.fd = h->fd;
            if (ioctl(drm_, DRM_IOCTL_PRIME_FD_TO_HANDLE, &p)) return fail("PRIME_FD_TO_HANDLE");
            drm_virtgpu_resource_info ri{};
            ri.bo_handle = p.handle;
            drm_virtgpu_map m{};
            m.handle = p.handle;
            void* a = MAP_FAILED;
            if (!ioctl(drm_, DRM_IOCTL_VIRTGPU_RESOURCE_INFO, &ri) && !ioctl(drm_, DRM_IOCTL_VIRTGPU_MAP, &m))
                a = mmap(nullptr, ri.size, PROT_READ, MAP_SHARED, drm_, (off_t)m.offset);
            if (a == MAP_FAILED) {
                closeBo(p.handle);
                return fail("map");
            }
            it = maps_.emplace((uint64_t)st.st_ino, Map{p.handle, a, ri.size}).first;
        }
        return fetch(it->second, h->width(), h->height(), h->stride(), out);
    }

  private:
    struct Map {
        uint32_t bo;
        void* addr;
        size_t size;
    };
    bool fail(const char* what) {
        if (warnings_++ < 10) ALOGW("virtio-gpu buffer: %s failed: %s", what, strerror(errno));
        return false;
    }
    bool fetch(const Map& m, uint32_t width, uint32_t height, uint32_t stride, std::vector<uint8_t>& out) {
        if ((size_t)stride * height > m.size) return fail("buffer larger than its blob");
        drm_virtgpu_3d_transfer_from_host t{};
        t.bo_handle = m.bo;
        t.box = {0, 0, 0, width, height, 1};
        if (ioctl(drm_, DRM_IOCTL_VIRTGPU_TRANSFER_FROM_HOST, &t)) return fail("TRANSFER_FROM_HOST");
        drm_virtgpu_3d_wait w{};
        w.handle = m.bo;
        ioctl(drm_, DRM_IOCTL_VIRTGPU_WAIT, &w);
        const uint8_t* p = static_cast<const uint8_t*>(m.addr);
        out.assign(p, p + (size_t)stride * height);
        return true;
    }
    void closeBo(uint32_t bo) {
        drm_gem_close c{};
        c.handle = bo;
        ioctl(drm_, DRM_IOCTL_GEM_CLOSE, &c);
    }
    void clear() {
        for (auto& [k, m] : maps_) {
            munmap(m.addr, m.size);
            closeBo(m.bo);
        }
        maps_.clear();
    }
    std::mutex lock_;
    int drm_ = -1;
    int warnings_ = 0;
    std::map<uint64_t, Map> maps_;
};
VirtioReader gVirtio;

struct __attribute__((packed)) FrameHeader {
    uint32_t magic = 0x46415357;  // "WSAF"
    uint32_t version = 1;
    uint32_t width = 0, height = 0;
    uint32_t stride = 0;  // bytes per row of the payload
    uint32_t format = 0;  // DRM fourcc of the client target (AB24 = R,G,B,A bytes)
    uint64_t frame = 0;
};

// A task's picture: rectangle in display px, RGBA bytes (premultiplied), height * stride.
struct __attribute__((packed)) TaskHeader {
    uint32_t magic = 0x54415357;  // "WSAT"
    uint32_t version = 1;
    int32_t taskId = 0;
    int32_t x = 0, y = 0;
    uint32_t width = 0, height = 0;
    uint32_t stride = 0;
    uint32_t format = 0x34324241;  // DRM AB24: bytes R,G,B,A
    uint32_t reserved = 0;
    uint64_t frame = 0;
};

struct __attribute__((packed)) TaskRect {
    int32_t taskId, x, y, width, height;
    bool operator==(const TaskRect& o) const {
        return taskId == o.taskId && x == o.x && y == o.y && width == o.width && height == o.height;
    }
};

// The current tasks; the host closes the windows of tasks that are not listed any more.
struct __attribute__((packed)) TaskListHeader {
    uint32_t magic = 0x4C415357;  // "WSAL"
    uint32_t version = 1;
    uint32_t count = 0;
    uint32_t reserved = 0;
    uint64_t frame = 0;
};

struct TaskImage {
    TaskHeader header;
    std::vector<uint8_t> pixels;
};

// Latest-frame-only sender to the host.
class FrameSink {
  public:
    explicit FrameSink(int port) : port_(port) {
        if (port_ > 0) thread_ = std::thread([this] { loop(); });
    }
    ~FrameSink() {
        {
            std::lock_guard<std::mutex> l(lock_);
            quit_ = true;
        }
        cv_.notify_all();
        if (thread_.joinable()) thread_.join();
        for (auto& [k, m] : maps_) munmap(m.addr, m.size);
    }

    bool connected() const { return connected_.load(); }

    // Copies the client target; fence: its acquire fence (owned, closed here).
    void submit(buffer_handle_t target, int fence, uint64_t frame) {
        if (fence >= 0) {
            pollfd p{fence, POLLIN, 0};
            poll(&p, 1, 100);
            close(fence);
        }
        if (!target || !connected_.load()) return;
        if (const MsHandle* m = msHandle(target)) {
            const uint32_t format = drmFormat(m->format());
            std::vector<uint8_t> px;
            if (!format || !gVirtio.read(m, px)) {
                if (!format && !warned_) ALOGW("client target format %d not sent", m->format());
                warned_ = true;
                return;
            }
            {
                std::lock_guard<std::mutex> l(lock_);
                latest_.assign(px.begin(), px.end());
                header_.width = m->width();
                header_.height = m->height();
                header_.stride = m->stride();
                header_.format = format;
                header_.frame = frame;
                dirty_ = true;
            }
            cv_.notify_all();
            return;
        }
        const CrosHandle* h = reinterpret_cast<const CrosHandle*>(target);
        if (h->base.numFds < 1 || h->magic != kCrosMagic) {
            if (!warned_) ALOGW("client target is not a minigbm handle (numFds %d), frames not sent", h->base.numFds);
            warned_ = true;
            return;
        }
        int fd = h->fds[0];
        struct stat st;
        if (fstat(fd, &st)) return;
        uint64_t key = (uint64_t)st.st_ino;
        auto it = maps_.find(key);
        if (it == maps_.end()) {
            if (maps_.size() >= 8) {
                for (auto& [k, m] : maps_) munmap(m.addr, m.size);
                maps_.clear();
            }
            size_t size = (size_t)lseek(fd, 0, SEEK_END);
            void* addr = mmap(nullptr, size, PROT_READ, MAP_SHARED, fd, 0);
            if (addr == MAP_FAILED) {
                if (!warned_) ALOGW("mmap of client target failed: %s", strerror(errno));
                warned_ = true;
                return;
            }
            it = maps_.emplace(key, Map{addr, size}).first;
        }
        uint32_t stride = h->strides[0], height = h->height;
        size_t bytes = (size_t)stride * height;
        if ((size_t)h->offsets[0] + bytes > it->second.size) return;
        dma_buf_sync sync{DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ};
        ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync);
        {
            std::lock_guard<std::mutex> l(lock_);
            latest_.resize(bytes);
            memcpy(latest_.data(), (const char*)it->second.addr + h->offsets[0], bytes);
            header_.width = h->width;
            header_.height = height;
            header_.stride = stride;
            header_.format = h->format;
            header_.frame = frame;
            dirty_ = true;
        }
        sync.flags = DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ;
        ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync);
        cv_.notify_all();
    }

    // The task list of this frame and the pictures of the tasks that changed. Tasks not in the list are forgotten.
    void submitTasks(const std::vector<TaskRect>& list, std::vector<TaskImage>&& changed, uint64_t frame) {
        {
            std::lock_guard<std::mutex> l(lock_);
            if (!(list == list_)) {
                list_ = list;
                listFrame_ = frame;
                listDirty_ = true;
                for (auto it = tasks_.begin(); it != tasks_.end();) {
                    bool listed = false;
                    for (const TaskRect& r : list) listed |= r.taskId == it->first;
                    it = listed ? std::next(it) : tasks_.erase(it);
                }
            }
            for (TaskImage& t : changed) {
                Task& slot = tasks_[t.header.taskId];
                slot.image = std::move(t);
                slot.dirty = true;
            }
        }
        cv_.notify_all();
    }

  private:
    struct Task {
        TaskImage image;
        bool dirty = false;
    };

    bool pendingLocked() const {
        if (dirty_ || listDirty_) return true;
        for (const auto& [id, t] : tasks_)
            if (t.dirty) return true;
        return false;
    }

    struct Map {
        void* addr;
        size_t size;
    };

    static bool sendAll(int s, const void* data, size_t n) {
        const char* p = static_cast<const char*>(data);
        while (n) {
            ssize_t w = send(s, p, n, MSG_NOSIGNAL);
            if (w <= 0) {
                if (w < 0 && errno == EINTR) continue;
                return false;
            }
            p += w;
            n -= (size_t)w;
        }
        return true;
    }

    int connectHost() {
        int s = socket(AF_VSOCK, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (s < 0) return -1;
        sockaddr_vm a{};
        a.svm_family = AF_VSOCK;
        a.svm_cid = VMADDR_CID_HOST;
        a.svm_port = (unsigned)port_;
        if (connect(s, reinterpret_cast<sockaddr*>(&a), sizeof a)) {
            close(s);
            return -1;
        }
        return s;
    }

    void loop() {
        std::vector<char> sending;
        bool loggedFail = false;
        while (true) {
            int s = connectHost();
            if (s < 0) {
                if (!loggedFail) ALOGI("frame sink: no host on vsock port %d yet, retrying every 2 s", port_);
                loggedFail = true;
                std::unique_lock<std::mutex> l(lock_);
                if (cv_.wait_for(l, std::chrono::seconds(2), [this] { return quit_; })) return;
                continue;
            }
            ALOGI("frame sink: host connected on vsock port %d", port_);
            loggedFail = false;
            {
                // A new host knows nothing: the list and every task picture go again.
                std::lock_guard<std::mutex> l(lock_);
                listDirty_ = true;
                for (auto& [id, t] : tasks_) t.dirty = true;
            }
            connected_ = true;
            while (true) {
                FrameHeader h;
                bool frame = false, sendList = false;
                std::vector<TaskRect> list;
                TaskListHeader lh;
                std::vector<TaskImage> images;
                {
                    std::unique_lock<std::mutex> l(lock_);
                    cv_.wait(l, [this] { return quit_ || pendingLocked(); });
                    if (quit_) {
                        connected_ = false;
                        close(s);
                        return;
                    }
                    if (listDirty_) {
                        sendList = true;
                        list = list_;
                        lh.count = (uint32_t)list.size();
                        lh.frame = listFrame_;
                        listDirty_ = false;
                    }
                    for (auto& [id, t] : tasks_)
                        if (t.dirty) {
                            images.push_back(t.image);
                            t.dirty = false;
                        }
                    if (dirty_) {
                        frame = true;
                        sending.swap(latest_);
                        h = header_;
                        dirty_ = false;
                    }
                }
                bool ok = true;
                if (sendList)
                    ok = sendAll(s, &lh, sizeof lh) && (list.empty() || sendAll(s, list.data(), list.size() * sizeof(TaskRect)));
                for (const TaskImage& t : images)
                    ok = ok && sendAll(s, &t.header, sizeof t.header) && sendAll(s, t.pixels.data(), t.pixels.size());
                if (ok && frame) ok = sendAll(s, &h, sizeof h) && sendAll(s, sending.data(), sending.size());
                if (!ok) break;
            }
            connected_ = false;
            close(s);
            ALOGI("frame sink: host went away");
        }
    }

    const int port_;
    std::thread thread_;
    std::mutex lock_;
    std::condition_variable cv_;
    bool quit_ = false, dirty_ = false, warned_ = false;
    std::atomic<bool> connected_{false};
    std::vector<char> latest_;
    FrameHeader header_;
    std::vector<TaskRect> list_;
    uint64_t listFrame_ = 0;
    bool listDirty_ = false;
    std::map<int32_t, Task> tasks_;
    std::map<uint64_t, Map> maps_;  // dma-buf inode -> read-only mapping (composer thread only)
};

struct Layer {
    int32_t type = HWC2_COMPOSITION_CLIENT;  // requested by SurfaceFlinger
    bool changed = false;                    // validateDisplay forced it to CLIENT
    // For the task windows: what SurfaceFlinger told about the layer (geometry only when it changes).
    buffer_handle_t buffer = nullptr;
    int32_t acquireFence = -1;
    uint64_t serial = 0;  // bumped on every setLayerBuffer
    hwc_rect_t frame{0, 0, 0, 0};
    hwc_frect_t crop{0, 0, 0, 0};
    uint32_t z = 0;
    int32_t blend = HWC2_BLEND_MODE_NONE;
    float alpha = 1.0f;
    int32_t transform = 0;
    int32_t taskId = -1;      // com.microsoft.wsa.taskid, -1 when absent
    int32_t windowType = 0;   // com.microsoft.wsa.windowtype (WindowManager.LayoutParams.type), 0 when absent
};

// Windows without a task that still get a host window of their own (as the overlays of hwcomposer.windows.so): a
// toast, the soft keyboard, system alerts and dialogs, application overlays. Sent as a pseudo task -<type>.
bool isOverlayType(int32_t type) {
    switch (type) {
        case 2003:  // TYPE_SYSTEM_ALERT
        case 2005:  // TYPE_TOAST
        case 2008:  // TYPE_SYSTEM_DIALOG
        case 2010:  // TYPE_SYSTEM_ERROR
        case 2011:  // TYPE_INPUT_METHOD
        case 2012:  // TYPE_INPUT_METHOD_DIALOG
        case 2038:  // TYPE_APPLICATION_OVERLAY
            return true;
        default:
            return false;
    }
}

// One layer of a task as composePictures sees it (copied out of Layer under the device lock).
struct TaskLayer {
    hwc2_layer_t id;
    buffer_handle_t buffer;
    int32_t fence;  // owned
    uint64_t serial;
    hwc_rect_t frame;
    hwc_frect_t crop;
    uint32_t z;
    int32_t blend;
    float alpha;
    int32_t transform;
};

struct Device {
    hwc2_device_t base;  // first member: the hwc2_device_t* we hand out is a Device*
    Mode mode;
    std::mutex lock;
    std::map<hwc2_layer_t, Layer> layers;
    hwc2_layer_t nextLayer = 1;
    buffer_handle_t clientTarget = nullptr;
    int32_t clientTargetFence = -1;
    FrameSink* sink = nullptr;
    bool taskWindows = true;
    std::map<int32_t, std::string> taskSignatures;  // what each task's last sent picture was made of
    std::map<int32_t, TaskRect> shownRects;          // the rectangle each task was last sent with
    std::map<int32_t, bool> warnedFormats;
    int32_t powerMode = HWC2_POWER_MODE_ON;
    uint64_t frames = 0;

    // callbacks
    HWC2_PFN_HOTPLUG hotplug = nullptr;
    hwc2_callback_data_t hotplugData = nullptr;
    HWC2_PFN_REFRESH refresh = nullptr;
    hwc2_callback_data_t refreshData = nullptr;
    HWC2_PFN_VSYNC vsync = nullptr;
    hwc2_callback_data_t vsyncData = nullptr;
    HWC2_PFN_VSYNC_2_4 vsync24 = nullptr;
    hwc2_callback_data_t vsync24Data = nullptr;

    // vsync thread
    std::thread vsyncThread;
    std::condition_variable vsyncCv;
    bool vsyncEnabled = false;
    bool quit = false;

    void vsyncLoop() {
        int64_t next = nowNs();
        std::unique_lock<std::mutex> l(lock);
        while (!quit) {
            if (!vsyncEnabled) {
                vsyncCv.wait(l);
                next = nowNs();
                continue;
            }
            next += mode.periodNs();
            int64_t wait = next - nowNs();
            if (wait > 0) vsyncCv.wait_for(l, std::chrono::nanoseconds(wait));
            if (quit || !vsyncEnabled) continue;
            auto cb24 = vsync24;
            auto d24 = vsync24Data;
            auto cb = vsync;
            auto d = vsyncData;
            l.unlock();
            if (cb24)
                cb24(d24, kDisplay, next, mode.periodNs());
            else if (cb)
                cb(d, kDisplay, next);
            l.lock();
        }
    }
};

Device* dev(hwc2_device_t* d) { return reinterpret_cast<Device*>(d); }

#define CHECK_DISPLAY(display) \
    if ((display) != kDisplay) return HWC2_ERROR_BAD_DISPLAY

// ---- device-level

void getCapabilities(hwc2_device_t*, uint32_t* outCount, int32_t*) { *outCount = 0; }

int32_t registerCallback(hwc2_device_t* d, int32_t descriptor, hwc2_callback_data_t data,
                         hwc2_function_pointer_t pointer) {
    Device* D = dev(d);
    std::unique_lock<std::mutex> l(D->lock);
    switch (descriptor) {
        case HWC2_CALLBACK_HOTPLUG:
            D->hotplug = reinterpret_cast<HWC2_PFN_HOTPLUG>(pointer);
            D->hotplugData = data;
            if (D->hotplug) {
                auto cb = D->hotplug;
                l.unlock();
                cb(data, kDisplay, HWC2_CONNECTION_CONNECTED);
            }
            return HWC2_ERROR_NONE;
        case HWC2_CALLBACK_REFRESH:
            D->refresh = reinterpret_cast<HWC2_PFN_REFRESH>(pointer);
            D->refreshData = data;
            return HWC2_ERROR_NONE;
        case HWC2_CALLBACK_VSYNC:
            D->vsync = reinterpret_cast<HWC2_PFN_VSYNC>(pointer);
            D->vsyncData = data;
            return HWC2_ERROR_NONE;
        case HWC2_CALLBACK_VSYNC_2_4:
            D->vsync24 = reinterpret_cast<HWC2_PFN_VSYNC_2_4>(pointer);
            D->vsync24Data = data;
            return HWC2_ERROR_NONE;
        default:
            return HWC2_ERROR_NONE;  // seamless/timing-changed: never fired
    }
}

int32_t createVirtualDisplay(hwc2_device_t*, uint32_t, uint32_t, int32_t*, hwc2_display_t*) {
    return HWC2_ERROR_NO_RESOURCES;
}
int32_t destroyVirtualDisplay(hwc2_device_t*, hwc2_display_t) { return HWC2_ERROR_BAD_DISPLAY; }
uint32_t getMaxVirtualDisplayCount(hwc2_device_t*) { return 0; }

void dump(hwc2_device_t* d, uint32_t* outSize, char* outBuffer) {
    Device* D = dev(d);
    char s[256];
    int n = snprintf(s, sizeof(s), "WSA headless HWC: %dx%d@%d dpi %d, %zu layers, %llu frames presented\n",
                     D->mode.width, D->mode.height, D->mode.fps, D->mode.dpi, D->layers.size(),
                     (unsigned long long)D->frames);
    if (!outBuffer) {
        *outSize = n;
        return;
    }
    uint32_t c = *outSize < (uint32_t)n ? *outSize : (uint32_t)n;
    memcpy(outBuffer, s, c);
    *outSize = c;
}

// ---- display: configs and properties

int32_t getDisplayConfigs(hwc2_device_t*, hwc2_display_t display, uint32_t* outNum, hwc2_config_t* outConfigs) {
    CHECK_DISPLAY(display);
    if (outConfigs && *outNum >= 1) outConfigs[0] = kConfig;
    *outNum = 1;
    return HWC2_ERROR_NONE;
}

int32_t getDisplayAttribute(hwc2_device_t* d, hwc2_display_t display, hwc2_config_t config, int32_t attribute,
                            int32_t* outValue) {
    CHECK_DISPLAY(display);
    if (config != kConfig) return HWC2_ERROR_BAD_CONFIG;
    const Mode& m = dev(d)->mode;
    switch (attribute) {
        case HWC2_ATTRIBUTE_WIDTH: *outValue = m.width; break;
        case HWC2_ATTRIBUTE_HEIGHT: *outValue = m.height; break;
        case HWC2_ATTRIBUTE_VSYNC_PERIOD: *outValue = (int32_t)m.periodNs(); break;
        case HWC2_ATTRIBUTE_DPI_X:
        case HWC2_ATTRIBUTE_DPI_Y: *outValue = m.dpi * 1000; break;
        case HWC2_ATTRIBUTE_CONFIG_GROUP: *outValue = 0; break;
        default: *outValue = -1; break;
    }
    return HWC2_ERROR_NONE;
}

int32_t getActiveConfig(hwc2_device_t*, hwc2_display_t display, hwc2_config_t* outConfig) {
    CHECK_DISPLAY(display);
    *outConfig = kConfig;
    return HWC2_ERROR_NONE;
}

int32_t setActiveConfig(hwc2_device_t*, hwc2_display_t display, hwc2_config_t config) {
    CHECK_DISPLAY(display);
    return config == kConfig ? HWC2_ERROR_NONE : HWC2_ERROR_BAD_CONFIG;
}

int32_t setActiveConfigWithConstraints(hwc2_device_t*, hwc2_display_t display, hwc2_config_t config,
                                       hwc_vsync_period_change_constraints_t*,
                                       hwc_vsync_period_change_timeline_t* outTimeline) {
    CHECK_DISPLAY(display);
    if (config != kConfig) return HWC2_ERROR_BAD_CONFIG;
    if (outTimeline) {
        outTimeline->newVsyncAppliedTimeNanos = nowNs();
        outTimeline->refreshRequired = 0;
        outTimeline->refreshTimeNanos = 0;
    }
    return HWC2_ERROR_NONE;
}

int32_t getDisplayVsyncPeriod(hwc2_device_t* d, hwc2_display_t display, hwc2_vsync_period_t* outPeriod) {
    CHECK_DISPLAY(display);
    *outPeriod = (hwc2_vsync_period_t)dev(d)->mode.periodNs();
    return HWC2_ERROR_NONE;
}

int32_t getDisplayName(hwc2_device_t*, hwc2_display_t display, uint32_t* outSize, char* outName) {
    CHECK_DISPLAY(display);
    static const char kName[] = "WSA headless";
    if (!outName) {
        *outSize = sizeof(kName) - 1;
        return HWC2_ERROR_NONE;
    }
    uint32_t c = *outSize < sizeof(kName) - 1 ? *outSize : sizeof(kName) - 1;
    memcpy(outName, kName, c);
    *outSize = c;
    return HWC2_ERROR_NONE;
}

int32_t getDisplayType(hwc2_device_t*, hwc2_display_t display, int32_t* outType) {
    CHECK_DISPLAY(display);
    *outType = HWC2_DISPLAY_TYPE_PHYSICAL;
    return HWC2_ERROR_NONE;
}

int32_t getDisplayConnectionType(hwc2_device_t*, hwc2_display_t display, uint32_t* outType) {
    CHECK_DISPLAY(display);
    *outType = HWC2_DISPLAY_CONNECTION_TYPE_INTERNAL;
    return HWC2_ERROR_NONE;
}

int32_t getDozeSupport(hwc2_device_t*, hwc2_display_t display, int32_t* outSupport) {
    CHECK_DISPLAY(display);
    *outSupport = 0;
    return HWC2_ERROR_NONE;
}

int32_t getHdrCapabilities(hwc2_device_t*, hwc2_display_t display, uint32_t* outNumTypes, int32_t*, float*, float*,
                           float*) {
    CHECK_DISPLAY(display);
    *outNumTypes = 0;
    return HWC2_ERROR_NONE;
}

int32_t getColorModes(hwc2_device_t*, hwc2_display_t display, uint32_t* outNum, int32_t* outModes) {
    CHECK_DISPLAY(display);
    if (outModes && *outNum >= 1) outModes[0] = HAL_COLOR_MODE_NATIVE;
    *outNum = 1;
    return HWC2_ERROR_NONE;
}

int32_t setColorMode(hwc2_device_t*, hwc2_display_t display, int32_t mode) {
    CHECK_DISPLAY(display);
    return mode == HAL_COLOR_MODE_NATIVE ? HWC2_ERROR_NONE : HWC2_ERROR_UNSUPPORTED;
}

int32_t getRenderIntents(hwc2_device_t*, hwc2_display_t display, int32_t mode, uint32_t* outNum, int32_t* outIntents) {
    CHECK_DISPLAY(display);
    if (mode != HAL_COLOR_MODE_NATIVE) return HWC2_ERROR_BAD_PARAMETER;
    if (outIntents && *outNum >= 1) outIntents[0] = HAL_RENDER_INTENT_COLORIMETRIC;
    *outNum = 1;
    return HWC2_ERROR_NONE;
}

int32_t setColorModeWithRenderIntent(hwc2_device_t*, hwc2_display_t display, int32_t mode, int32_t intent) {
    CHECK_DISPLAY(display);
    return mode == HAL_COLOR_MODE_NATIVE && intent == HAL_RENDER_INTENT_COLORIMETRIC ? HWC2_ERROR_NONE
                                                                                     : HWC2_ERROR_UNSUPPORTED;
}

int32_t getDisplayCapabilities(hwc2_device_t*, hwc2_display_t display, uint32_t* outNum, uint32_t*) {
    CHECK_DISPLAY(display);
    *outNum = 0;
    return HWC2_ERROR_NONE;
}

int32_t getClientTargetSupport(hwc2_device_t*, hwc2_display_t display, uint32_t, uint32_t, int32_t, int32_t) {
    CHECK_DISPLAY(display);
    return HWC2_ERROR_NONE;
}

int32_t setPowerMode(hwc2_device_t* d, hwc2_display_t display, int32_t mode) {
    CHECK_DISPLAY(display);
    std::lock_guard<std::mutex> l(dev(d)->lock);
    dev(d)->powerMode = mode;
    return HWC2_ERROR_NONE;
}

int32_t setVsyncEnabled(hwc2_device_t* d, hwc2_display_t display, int32_t enabled) {
    CHECK_DISPLAY(display);
    Device* D = dev(d);
    {
        std::lock_guard<std::mutex> l(D->lock);
        D->vsyncEnabled = enabled == HWC2_VSYNC_ENABLE;
    }
    D->vsyncCv.notify_all();
    return HWC2_ERROR_NONE;
}

// ---- layers and composition

int32_t createLayer(hwc2_device_t* d, hwc2_display_t display, hwc2_layer_t* outLayer) {
    CHECK_DISPLAY(display);
    Device* D = dev(d);
    std::lock_guard<std::mutex> l(D->lock);
    *outLayer = D->nextLayer++;
    D->layers[*outLayer] = Layer();
    return HWC2_ERROR_NONE;
}

int32_t destroyLayer(hwc2_device_t* d, hwc2_display_t display, hwc2_layer_t layer) {
    CHECK_DISPLAY(display);
    Device* D = dev(d);
    std::lock_guard<std::mutex> l(D->lock);
    auto it = D->layers.find(layer);
    if (it == D->layers.end()) return HWC2_ERROR_BAD_LAYER;
    closeFence(it->second.acquireFence);
    D->layers.erase(it);
    return HWC2_ERROR_NONE;
}

int32_t setLayerCompositionType(hwc2_device_t* d, hwc2_display_t display, hwc2_layer_t layer, int32_t type) {
    CHECK_DISPLAY(display);
    Device* D = dev(d);
    std::lock_guard<std::mutex> l(D->lock);
    auto it = D->layers.find(layer);
    if (it == D->layers.end()) return HWC2_ERROR_BAD_LAYER;
    it->second.type = type;
    return HWC2_ERROR_NONE;
}

// The fence passed with a layer buffer belongs to the device from now on.
int32_t setLayerBuffer(hwc2_device_t* d, hwc2_display_t display, hwc2_layer_t layer, buffer_handle_t buffer,
                       int32_t acquireFence) {
    if (display != kDisplay) {
        closeFence(acquireFence);
        return HWC2_ERROR_BAD_DISPLAY;
    }
    Device* D = dev(d);
    std::lock_guard<std::mutex> l(D->lock);
    auto it = D->layers.find(layer);
    if (it == D->layers.end()) {
        closeFence(acquireFence);
        return HWC2_ERROR_BAD_LAYER;
    }
    closeFence(it->second.acquireFence);
    it->second.buffer = buffer;
    it->second.acquireFence = acquireFence;
    it->second.serial++;
    return HWC2_ERROR_NONE;
}

// Layer geometry and the task tag, kept for the task windows.
template <typename F>
int32_t withLayer(hwc2_device_t* d, hwc2_display_t display, hwc2_layer_t layer, F f) {
    CHECK_DISPLAY(display);
    Device* D = dev(d);
    std::lock_guard<std::mutex> l(D->lock);
    auto it = D->layers.find(layer);
    if (it == D->layers.end()) return HWC2_ERROR_BAD_LAYER;
    f(it->second);
    return HWC2_ERROR_NONE;
}

int32_t setLayerDisplayFrame(hwc2_device_t* d, hwc2_display_t display, hwc2_layer_t layer, hwc_rect_t frame) {
    return withLayer(d, display, layer, [&](Layer& L) { L.frame = frame; });
}
int32_t setLayerSourceCrop(hwc2_device_t* d, hwc2_display_t display, hwc2_layer_t layer, hwc_frect_t crop) {
    return withLayer(d, display, layer, [&](Layer& L) { L.crop = crop; });
}
int32_t setLayerZOrder(hwc2_device_t* d, hwc2_display_t display, hwc2_layer_t layer, uint32_t z) {
    return withLayer(d, display, layer, [&](Layer& L) { L.z = z; });
}
int32_t setLayerBlendMode(hwc2_device_t* d, hwc2_display_t display, hwc2_layer_t layer, int32_t mode) {
    return withLayer(d, display, layer, [&](Layer& L) { L.blend = mode; });
}
int32_t setLayerPlaneAlpha(hwc2_device_t* d, hwc2_display_t display, hwc2_layer_t layer, float alpha) {
    return withLayer(d, display, layer, [&](Layer& L) { L.alpha = alpha; });
}
int32_t setLayerTransform(hwc2_device_t* d, hwc2_display_t display, hwc2_layer_t layer, int32_t transform) {
    return withLayer(d, display, layer, [&](Layer& L) { L.transform = transform; });
}

constexpr const char* kMetadataKeys[] = {"com.microsoft.wsa.taskid", "com.microsoft.wsa.windowtype"};

void getLayerGenericMetadataKey(hwc2_device_t*, uint32_t keyIndex, uint32_t* outKeyLength, char* outKey,
                                bool* outMandatory) {
    if (keyIndex >= sizeof(kMetadataKeys) / sizeof(kMetadataKeys[0])) {
        *outKeyLength = 0;
        return;
    }
    const char* key = kMetadataKeys[keyIndex];
    *outKeyLength = (uint32_t)strlen(key);
    if (outKey) memcpy(outKey, key, *outKeyLength);
    if (outMandatory) *outMandatory = false;
}

int32_t setLayerGenericMetadata(hwc2_device_t* d, hwc2_display_t display, hwc2_layer_t layer, uint32_t keyLength,
                                const char* key, bool, uint32_t valueLength, const uint8_t* value) {
    std::string k(key, keyLength);
    return withLayer(d, display, layer, [&](Layer& L) {
        const bool task = k == kMetadataKeys[0];
        if (!task && k != kMetadataKeys[1]) return;
        int32_t v = task ? -1 : 0;
        if (valueLength >= sizeof(v)) memcpy(&v, value, sizeof(v));  // LayerMetadata int32: 4 bytes, LE
        (task ? L.taskId : L.windowType) = v;
    });
}

int32_t setClientTarget(hwc2_device_t* d, hwc2_display_t display, buffer_handle_t target, int32_t acquireFence,
                        int32_t, hwc_region_t) {
    if (display != kDisplay) {
        closeFence(acquireFence);
        return HWC2_ERROR_BAD_DISPLAY;
    }
    std::lock_guard<std::mutex> l(dev(d)->lock);
    closeFence(dev(d)->clientTargetFence);  // a target that was set but never presented
    dev(d)->clientTarget = target;
    dev(d)->clientTargetFence = acquireFence;
    return HWC2_ERROR_NONE;
}

int32_t setOutputBuffer(hwc2_device_t*, hwc2_display_t, buffer_handle_t, int32_t releaseFence) {
    closeFence(releaseFence);
    return HWC2_ERROR_UNSUPPORTED;
}

int32_t validateDisplay(hwc2_device_t* d, hwc2_display_t display, uint32_t* outNumTypes, uint32_t* outNumRequests) {
    CHECK_DISPLAY(display);
    Device* D = dev(d);
    std::lock_guard<std::mutex> l(D->lock);
    uint32_t changed = 0;
    for (auto& [id, layer] : D->layers) {
        layer.changed = layer.type != HWC2_COMPOSITION_CLIENT;
        if (layer.changed) changed++;
    }
    *outNumTypes = changed;
    *outNumRequests = 0;
    return changed ? HWC2_ERROR_HAS_CHANGES : HWC2_ERROR_NONE;
}

int32_t getChangedCompositionTypes(hwc2_device_t* d, hwc2_display_t display, uint32_t* outNum,
                                   hwc2_layer_t* outLayers, int32_t* outTypes) {
    CHECK_DISPLAY(display);
    Device* D = dev(d);
    std::lock_guard<std::mutex> l(D->lock);
    uint32_t n = 0;
    for (auto& [id, layer] : D->layers) {
        if (!layer.changed) continue;
        if (outLayers && outTypes) {
            if (n >= *outNum) break;
            outLayers[n] = id;
            outTypes[n] = HWC2_COMPOSITION_CLIENT;
        }
        n++;
    }
    *outNum = n;
    return HWC2_ERROR_NONE;
}

int32_t acceptDisplayChanges(hwc2_device_t* d, hwc2_display_t display) {
    CHECK_DISPLAY(display);
    Device* D = dev(d);
    std::lock_guard<std::mutex> l(D->lock);
    for (auto& [id, layer] : D->layers) {
        if (layer.changed) layer.type = HWC2_COMPOSITION_CLIENT;
        layer.changed = false;
    }
    return HWC2_ERROR_NONE;
}

int32_t getDisplayRequests(hwc2_device_t*, hwc2_display_t display, int32_t* outDisplayRequests, uint32_t* outNum,
                           hwc2_layer_t*, int32_t*) {
    CHECK_DISPLAY(display);
    *outDisplayRequests = 0;
    *outNum = 0;
    return HWC2_ERROR_NONE;
}

// ---- task pictures

// A read-only CPU view of a minigbm buffer (one linear plane; unmapped by the destructor) or of Microsoft's.
struct BufferView {
    const uint8_t* base = nullptr;
    void* addr = MAP_FAILED;
    size_t size = 0;
    int fd = -1;
    uint32_t stride = 0, width = 0, height = 0;
    int32_t format = 0;
    std::vector<uint8_t> copy;  // Microsoft's buffers

    bool open(buffer_handle_t handle) {
        if (const MsHandle* m = msHandle(handle)) {  // a copy, nothing to unmap
            if (!gVirtio.read(m, copy)) return false;
            base = copy.data();
            stride = m->stride();
            width = m->width();
            height = m->height();
            format = m->format();
            return true;
        }
        const CrosHandle* h = reinterpret_cast<const CrosHandle*>(handle);
        if (!h || h->base.numFds < 1 || h->magic != kCrosMagic) return false;
        fd = h->fds[0];
        off_t end = lseek(fd, 0, SEEK_END);
        if (end <= 0) return false;
        size = (size_t)end;
        if ((size_t)h->offsets[0] + (size_t)h->strides[0] * h->height > size) return false;
        addr = mmap(nullptr, size, PROT_READ, MAP_SHARED, fd, 0);
        if (addr == MAP_FAILED) return false;
        dma_buf_sync sync{DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ};
        ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync);
        base = static_cast<const uint8_t*>(addr) + h->offsets[0];
        stride = h->strides[0];
        width = h->width;
        height = h->height;
        format = h->droidFormat;
        return true;
    }
    ~BufferView() {
        if (addr == MAP_FAILED) return;
        dma_buf_sync sync{DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ};
        ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync);
        munmap(addr, size);
    }
};

// Pixel of a buffer as premultiplied-or-not R,G,B,A (as stored), false for formats we do not read.
inline bool readPixel(const BufferView& b, uint32_t x, uint32_t y, uint8_t px[4]) {
    const uint8_t* p = b.base + (size_t)y * b.stride;
    switch (b.format) {
        case HAL_PIXEL_FORMAT_RGBA_8888: memcpy(px, p + x * 4, 4); return true;
        case HAL_PIXEL_FORMAT_RGBX_8888: memcpy(px, p + x * 4, 3); px[3] = 255; return true;
        case HAL_PIXEL_FORMAT_BGRA_8888: p += x * 4; px[0] = p[2]; px[1] = p[1]; px[2] = p[0]; px[3] = p[3]; return true;
        case HAL_PIXEL_FORMAT_RGB_888: memcpy(px, p + x * 3, 3); px[3] = 255; return true;
        case HAL_PIXEL_FORMAT_RGB_565: {
            uint16_t v;
            memcpy(&v, p + x * 2, 2);
            px[0] = (uint8_t)(((v >> 11) & 31) * 255 / 31);
            px[1] = (uint8_t)(((v >> 5) & 63) * 255 / 63);
            px[2] = (uint8_t)((v & 31) * 255 / 31);
            px[3] = 255;
            return true;
        }
        default: return false;
    }
}

bool formatReadable(int32_t f) {
    return f == HAL_PIXEL_FORMAT_RGBA_8888 || f == HAL_PIXEL_FORMAT_RGBX_8888 || f == HAL_PIXEL_FORMAT_BGRA_8888 ||
           f == HAL_PIXEL_FORMAT_RGB_888 || f == HAL_PIXEL_FORMAT_RGB_565;
}

// Draws one layer into a task picture whose top-left is (ox, oy) in display px. Nearest sampling.
void drawLayer(Device* D, const TaskLayer& L, int32_t ox, int32_t oy, uint32_t w, uint32_t h, uint8_t* dst) {
    BufferView b;
    if (!b.open(L.buffer)) return;
    if (!formatReadable(b.format)) {
        if (!D->warnedFormats[b.format]) ALOGW("task windows: layer format %d not read, left out", b.format);
        D->warnedFormats[b.format] = true;
        return;
    }
    const int32_t fw = L.frame.right - L.frame.left, fh = L.frame.bottom - L.frame.top;
    float cl = L.crop.left, ct = L.crop.top, cw = L.crop.right - L.crop.left, ch = L.crop.bottom - L.crop.top;
    if (fw <= 0 || fh <= 0) return;
    if (cw <= 0 || ch <= 0) cl = 0, ct = 0, cw = (float)b.width, ch = (float)b.height;
    const uint32_t pa = (uint32_t)(L.alpha * 255.0f + 0.5f);
    int32_t x0 = L.frame.left - ox, y0 = L.frame.top - oy;
    int32_t xs = x0 < 0 ? 0 : x0, ys = y0 < 0 ? 0 : y0;
    int32_t xe = x0 + fw > (int32_t)w ? (int32_t)w : x0 + fw, ye = y0 + fh > (int32_t)h ? (int32_t)h : y0 + fh;
    const bool rot90 = L.transform & HAL_TRANSFORM_ROT_90, flipH = L.transform & HAL_TRANSFORM_FLIP_H,
               flipV = L.transform & HAL_TRANSFORM_FLIP_V;
    for (int32_t y = ys; y < ye; y++) {
        uint8_t* out = dst + ((size_t)y * w + xs) * 4;
        for (int32_t x = xs; x < xe; x++, out += 4) {
            // Display -> source, normalized: undo the rotation (clockwise 90), then the flips (applied first).
            float u = (x - x0 + 0.5f) / fw, v = (y - y0 + 0.5f) / fh;
            if (rot90) {
                float t = u;
                u = v;
                v = 1.0f - t;
            }
            if (flipH) u = 1.0f - u;
            if (flipV) v = 1.0f - v;
            int32_t sx = (int32_t)(cl + u * cw), sy = (int32_t)(ct + v * ch);
            if (sx < 0 || sy < 0 || (uint32_t)sx >= b.width || (uint32_t)sy >= b.height) continue;
            uint8_t s[4];
            readPixel(b, (uint32_t)sx, (uint32_t)sy, s);
            uint32_t r = s[0], g = s[1], bl = s[2], a = s[3];
            if (L.blend == HWC2_BLEND_MODE_NONE) a = 255;
            if (L.blend == HWC2_BLEND_MODE_COVERAGE) r = r * a / 255, g = g * a / 255, bl = bl * a / 255;
            // Premultiplied source with plane alpha, "over" the picture so far.
            r = r * pa / 255, g = g * pa / 255, bl = bl * pa / 255, a = a * pa / 255;
            uint32_t inv = 255 - a;
            out[0] = (uint8_t)(r + out[0] * inv / 255);
            out[1] = (uint8_t)(g + out[1] * inv / 255);
            out[2] = (uint8_t)(bl + out[2] * inv / 255);
            out[3] = (uint8_t)(a + out[3] * inv / 255);
        }
    }
}

// Overlay windows (the soft keyboard: a full-screen window that draws only its bottom part) are cut to their
// non-transparent pixels. False when nothing is visible.
bool trimTransparent(TaskImage& img) {
    const uint32_t w = img.header.width, h = img.header.height;
    uint32_t x0 = w, y0 = h, x1 = 0, y1 = 0;
    for (uint32_t y = 0; y < h; y++) {
        const uint8_t* row = img.pixels.data() + (size_t)y * w * 4;
        for (uint32_t x = 0; x < w; x++)
            if (row[x * 4 + 3]) {
                x0 = std::min(x0, x), x1 = std::max(x1, x + 1), y0 = std::min(y0, y), y1 = std::max(y1, y + 1);
            }
    }
    if (x1 <= x0 || y1 <= y0) return false;
    if (x0 == 0 && y0 == 0 && x1 == w && y1 == h) return true;
    const uint32_t nw = x1 - x0, nh = y1 - y0;
    std::vector<uint8_t> out((size_t)nw * nh * 4);
    for (uint32_t y = 0; y < nh; y++)
        memcpy(out.data() + (size_t)y * nw * 4, img.pixels.data() + ((size_t)(y + y0) * w + x0) * 4, (size_t)nw * 4);
    img.pixels.swap(out);
    img.header.x += (int32_t)x0;
    img.header.y += (int32_t)y0;
    img.header.width = nw;
    img.header.height = nh;
    img.header.stride = nw * 4;
    return true;
}

// Groups this frame's buffer layers by task, composes the tasks whose layers changed and hands everything to the
// sink. Runs on the composer thread after the client target; fences of the layers are waited for here.
void composeTasks(Device* D, std::map<int32_t, std::vector<TaskLayer>>& tasks, uint64_t frame) {
    std::vector<TaskRect> list;
    std::vector<TaskImage> changed;
    std::map<int32_t, std::string> signatures;
    for (auto& [taskId, layers] : tasks) {
        std::sort(layers.begin(), layers.end(), [](const TaskLayer& a, const TaskLayer& b) { return a.z < b.z; });
        int32_t l = INT32_MAX, t = INT32_MAX, r = INT32_MIN, btm = INT32_MIN;
        for (const TaskLayer& L : layers) {
            if (L.frame.right <= L.frame.left || L.frame.bottom <= L.frame.top) continue;
            l = std::min(l, L.frame.left), t = std::min(t, L.frame.top);
            r = std::max(r, L.frame.right), btm = std::max(btm, L.frame.bottom);
        }
        l = std::max(l, 0), t = std::max(t, 0), r = std::min(r, D->mode.width), btm = std::min(btm, D->mode.height);
        if (r <= l || btm <= t) continue;
        const TaskRect full{taskId, l, t, r - l, btm - t};
        std::string sig;
        auto add = [&sig](const void* p, size_t n) { sig.append(static_cast<const char*>(p), n); };
        add(&full, sizeof(TaskRect));
        for (const TaskLayer& L : layers) {
            add(&L.id, sizeof L.id), add(&L.serial, sizeof L.serial), add(&L.frame, sizeof L.frame);
            add(&L.crop, sizeof L.crop), add(&L.z, sizeof L.z), add(&L.blend, sizeof L.blend);
            add(&L.alpha, sizeof L.alpha), add(&L.transform, sizeof L.transform);
        }
        signatures[taskId] = sig;
        auto prev = D->taskSignatures.find(taskId);
        if (prev != D->taskSignatures.end() && prev->second == sig) {
            // Unchanged: the rectangle sent last time (an overlay's is trimmed, see below).
            auto shown = D->shownRects.find(taskId);
            if (shown != D->shownRects.end()) list.push_back(shown->second);
            continue;
        }
        TaskImage img;
        img.header.taskId = taskId;
        img.header.x = l;
        img.header.y = t;
        img.header.width = (uint32_t)(r - l);
        img.header.height = (uint32_t)(btm - t);
        img.header.stride = img.header.width * 4;
        img.header.frame = frame;
        img.pixels.assign((size_t)img.header.stride * img.header.height, 0);
        for (TaskLayer& L : layers) {
            if (L.fence >= 0) {
                pollfd p{L.fence, POLLIN, 0};
                poll(&p, 1, 100);
            }
            drawLayer(D, L, l, t, img.header.width, img.header.height, img.pixels.data());
        }
        if (taskId < 0 && !trimTransparent(img)) {  // an overlay with nothing visible: no window
            D->shownRects.erase(taskId);
            continue;
        }
        TaskRect rect{taskId, img.header.x, img.header.y, (int32_t)img.header.width, (int32_t)img.header.height};
        D->shownRects[taskId] = rect;
        list.push_back(rect);
        changed.push_back(std::move(img));
    }
    for (auto& [taskId, layers] : tasks)
        for (TaskLayer& L : layers) closeFence(L.fence);
    if (D->taskSignatures.size() != signatures.size()) {
        std::string ids;
        for (const TaskRect& r : list) ids += " " + std::to_string(r.taskId);
        ALOGI("task windows: %zu tasks:%s", list.size(), ids.c_str());
    }
    D->taskSignatures.swap(signatures);
    for (auto it = D->shownRects.begin(); it != D->shownRects.end();)
        it = D->taskSignatures.count(it->first) ? std::next(it) : D->shownRects.erase(it);
    D->sink->submitTasks(list, std::move(changed), frame);
}

int32_t presentDisplay(hwc2_device_t* d, hwc2_display_t display, int32_t* outPresentFence) {
    CHECK_DISPLAY(display);
    Device* D = dev(d);
    buffer_handle_t target;
    int32_t fence;
    uint64_t frame;
    const bool tasksWanted = D->sink && D->taskWindows && D->sink->connected();
    std::map<int32_t, std::vector<TaskLayer>> tasks;
    {
        std::lock_guard<std::mutex> l(D->lock);
        frame = ++D->frames;
        target = D->clientTarget;
        fence = D->clientTargetFence;
        D->clientTargetFence = -1;
        // Layer fences are consumed here: handed to composeTasks for task layers, closed otherwise.
        for (auto& [id, L] : D->layers) {
            const int32_t group = L.taskId > 0 ? L.taskId : isOverlayType(L.windowType) ? -L.windowType : 0;
            if (tasksWanted && group != 0 && L.buffer)
                tasks[group].push_back(TaskLayer{id, L.buffer, L.acquireFence, L.serial, L.frame, L.crop, L.z,
                                                    L.blend, L.alpha, L.transform});
            else
                closeFence(L.acquireFence);
            L.acquireFence = -1;
        }
    }
    // The copy is done before returning, so the buffer can be reused by SurfaceFlinger right away.
    if (D->sink)
        D->sink->submit(target, fence, frame);
    else
        closeFence(fence);
    // Layer buffers stay SurfaceFlinger's current buffers until a newer one is latched — safe to read here.
    if (tasksWanted) composeTasks(D, tasks, frame);
    *outPresentFence = -1;  // shown "immediately"
    return HWC2_ERROR_NONE;
}

int32_t getReleaseFences(hwc2_device_t*, hwc2_display_t display, uint32_t* outNum, hwc2_layer_t*, int32_t*) {
    CHECK_DISPLAY(display);
    *outNum = 0;
    return HWC2_ERROR_NONE;
}

// Everything else a display or a layer can be told is accepted and ignored: the frame is composed by
// SurfaceFlinger anyway. Called through the specific HWC2_PFN_* types; on x86-64 a callee that reads none of its
// arguments is safe for any of them.
int32_t acceptNone() { return HWC2_ERROR_NONE; }
int32_t unsupported() { return HWC2_ERROR_UNSUPPORTED; }

hwc2_function_pointer_t fn(int32_t (*f)()) { return reinterpret_cast<hwc2_function_pointer_t>(f); }
template <typename F>
hwc2_function_pointer_t fn(F f) {
    return reinterpret_cast<hwc2_function_pointer_t>(f);
}

hwc2_function_pointer_t getFunction(hwc2_device_t*, int32_t descriptor) {
    switch (descriptor) {
        case HWC2_FUNCTION_ACCEPT_DISPLAY_CHANGES: return fn(acceptDisplayChanges);
        case HWC2_FUNCTION_CREATE_LAYER: return fn(createLayer);
        case HWC2_FUNCTION_CREATE_VIRTUAL_DISPLAY: return fn(createVirtualDisplay);
        case HWC2_FUNCTION_DESTROY_LAYER: return fn(destroyLayer);
        case HWC2_FUNCTION_DESTROY_VIRTUAL_DISPLAY: return fn(destroyVirtualDisplay);
        case HWC2_FUNCTION_DUMP: return fn(dump);
        case HWC2_FUNCTION_GET_ACTIVE_CONFIG: return fn(getActiveConfig);
        case HWC2_FUNCTION_GET_CHANGED_COMPOSITION_TYPES: return fn(getChangedCompositionTypes);
        case HWC2_FUNCTION_GET_CLIENT_TARGET_SUPPORT: return fn(getClientTargetSupport);
        case HWC2_FUNCTION_GET_COLOR_MODES: return fn(getColorModes);
        case HWC2_FUNCTION_GET_DISPLAY_ATTRIBUTE: return fn(getDisplayAttribute);
        case HWC2_FUNCTION_GET_DISPLAY_CONFIGS: return fn(getDisplayConfigs);
        case HWC2_FUNCTION_GET_DISPLAY_NAME: return fn(getDisplayName);
        case HWC2_FUNCTION_GET_DISPLAY_REQUESTS: return fn(getDisplayRequests);
        case HWC2_FUNCTION_GET_DISPLAY_TYPE: return fn(getDisplayType);
        case HWC2_FUNCTION_GET_DOZE_SUPPORT: return fn(getDozeSupport);
        case HWC2_FUNCTION_GET_HDR_CAPABILITIES: return fn(getHdrCapabilities);
        case HWC2_FUNCTION_GET_MAX_VIRTUAL_DISPLAY_COUNT: return fn(getMaxVirtualDisplayCount);
        case HWC2_FUNCTION_GET_RELEASE_FENCES: return fn(getReleaseFences);
        case HWC2_FUNCTION_PRESENT_DISPLAY: return fn(presentDisplay);
        case HWC2_FUNCTION_REGISTER_CALLBACK: return fn(registerCallback);
        case HWC2_FUNCTION_SET_ACTIVE_CONFIG: return fn(setActiveConfig);
        case HWC2_FUNCTION_SET_CLIENT_TARGET: return fn(setClientTarget);
        case HWC2_FUNCTION_SET_COLOR_MODE: return fn(setColorMode);
        case HWC2_FUNCTION_SET_OUTPUT_BUFFER: return fn(setOutputBuffer);
        case HWC2_FUNCTION_SET_POWER_MODE: return fn(setPowerMode);
        case HWC2_FUNCTION_SET_VSYNC_ENABLED: return fn(setVsyncEnabled);
        case HWC2_FUNCTION_VALIDATE_DISPLAY: return fn(validateDisplay);
        case HWC2_FUNCTION_SET_LAYER_BUFFER: return fn(setLayerBuffer);
        case HWC2_FUNCTION_SET_LAYER_COMPOSITION_TYPE: return fn(setLayerCompositionType);
        case HWC2_FUNCTION_SET_LAYER_BLEND_MODE: return fn(setLayerBlendMode);
        case HWC2_FUNCTION_SET_LAYER_DISPLAY_FRAME: return fn(setLayerDisplayFrame);
        case HWC2_FUNCTION_SET_LAYER_PLANE_ALPHA: return fn(setLayerPlaneAlpha);
        case HWC2_FUNCTION_SET_LAYER_SOURCE_CROP: return fn(setLayerSourceCrop);
        case HWC2_FUNCTION_SET_LAYER_TRANSFORM: return fn(setLayerTransform);
        case HWC2_FUNCTION_SET_LAYER_Z_ORDER: return fn(setLayerZOrder);
        // composer 2.4: the task tag of patch 0006
        case HWC2_FUNCTION_SET_LAYER_GENERIC_METADATA: return fn(setLayerGenericMetadata);
        case HWC2_FUNCTION_GET_LAYER_GENERIC_METADATA_KEY: return fn(getLayerGenericMetadataKey);
        // accepted, ignored
        case HWC2_FUNCTION_SET_COLOR_TRANSFORM:
        case HWC2_FUNCTION_SET_CURSOR_POSITION:
        case HWC2_FUNCTION_SET_LAYER_COLOR:
        case HWC2_FUNCTION_SET_LAYER_DATASPACE:
        case HWC2_FUNCTION_SET_LAYER_SIDEBAND_STREAM:
        case HWC2_FUNCTION_SET_LAYER_SURFACE_DAMAGE:
        case HWC2_FUNCTION_SET_LAYER_VISIBLE_REGION:
        case HWC2_FUNCTION_SET_LAYER_FLOAT_COLOR:
        case HWC2_FUNCTION_SET_LAYER_PER_FRAME_METADATA:
        case HWC2_FUNCTION_SET_LAYER_COLOR_TRANSFORM:
        case HWC2_FUNCTION_SET_LAYER_PER_FRAME_METADATA_BLOBS:
            return fn(acceptNone);
        // composer 2.2–2.4
        case HWC2_FUNCTION_GET_RENDER_INTENTS: return fn(getRenderIntents);
        case HWC2_FUNCTION_SET_COLOR_MODE_WITH_RENDER_INTENT: return fn(setColorModeWithRenderIntent);
        case HWC2_FUNCTION_GET_DISPLAY_CAPABILITIES: return fn(getDisplayCapabilities);
        case HWC2_FUNCTION_SET_DISPLAY_BRIGHTNESS: return fn(unsupported);
        case HWC2_FUNCTION_GET_DISPLAY_VSYNC_PERIOD: return fn(getDisplayVsyncPeriod);
        case HWC2_FUNCTION_SET_ACTIVE_CONFIG_WITH_CONSTRAINTS: return fn(setActiveConfigWithConstraints);
        case HWC2_FUNCTION_GET_DISPLAY_CONNECTION_TYPE: return fn(getDisplayConnectionType);
        default:
            return nullptr;  // optional ones the loader probes for
    }
}

int closeDevice(hw_device_t* d) {
    Device* D = reinterpret_cast<Device*>(d);
    {
        std::lock_guard<std::mutex> l(D->lock);
        D->quit = true;
    }
    D->vsyncCv.notify_all();
    if (D->vsyncThread.joinable()) D->vsyncThread.join();
    delete D->sink;
    closeFence(D->clientTargetFence);
    for (auto& [id, L] : D->layers) closeFence(L.acquireFence);
    delete D;
    return 0;
}

int openDevice(const hw_module_t* module, const char* name, hw_device_t** outDevice) {
    if (strcmp(name, HWC_HARDWARE_COMPOSER) != 0) return -EINVAL;
    Device* D = new Device();
    memset(&D->base, 0, sizeof(D->base));
    D->base.common.tag = HARDWARE_DEVICE_TAG;
    D->base.common.version = HWC_DEVICE_API_VERSION_2_0;
    D->base.common.module = const_cast<hw_module_t*>(module);
    D->base.common.close = closeDevice;
    D->base.getCapabilities = getCapabilities;
    D->base.getFunction = getFunction;
    D->mode = readMode();
    D->vsyncThread = std::thread([D] { D->vsyncLoop(); });
    D->sink = new FrameSink(property_get_int32("vendor.wsa.headless.frame_port", 50100));
    D->taskWindows = property_get_bool("vendor.wsa.headless.task_windows", true);
    ALOGI("headless display %dx%d@%d, %d dpi", D->mode.width, D->mode.height, D->mode.fps, D->mode.dpi);
    *outDevice = &D->base.common;
    return 0;
}

hw_module_methods_t gMethods = {.open = openDevice};

}  // namespace

extern "C" {
__attribute__((visibility("default"))) hw_module_t HAL_MODULE_INFO_SYM = {
    .tag = HARDWARE_MODULE_TAG,
    .module_api_version = HWC_MODULE_API_VERSION_0_1,
    .hal_api_version = HARDWARE_HAL_API_VERSION,
    .id = HWC_HARDWARE_MODULE_ID,
    .name = "WSA headless hwcomposer",
    .author = "The WSA-Reborn Authors",
    .methods = &gMethods,
};
}
