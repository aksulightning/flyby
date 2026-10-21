#include "gpu.h"
#include "display.h"
#include "vulkan-probe.h"
#include <sys/uio.h>
extern "C" {
#include <rvvm/rvvm_region.h>
#include <rvvm/rvvm_fdt.h>
#include <virglrenderer.h>
#include <virgl_hw.h>
#include <epoxy/egl.h>
#include <epoxy/gl.h>
#include <linux/virtio_gpu.h>
}
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <cstdio>
#include <deque>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace flyby {
namespace {
constexpr uint64_t GpuAddress = 0x10004000;
constexpr unsigned QueueMax = 256;
constexpr uint32_t VenusCapset = 4; // Virtio-GPU Venus capset, absent from older host UAPI headers.
constexpr uint64_t HostMemoryBase = 0x20000000, HostMemorySize = 128 * 1024 * 1024;
constexpr size_t CommandLimit = 4 * 1024 * 1024, MemoryLimit = 128 * 1024 * 1024;
std::mutex rendererOwner; // virglrenderer has process-global state.
struct Descriptor { uint64_t address; uint32_t length; uint16_t flags, next; };
static_assert(sizeof(Descriptor) == 16);
struct Queue { uint32_t size = 0, ready = 0; uint64_t desc = 0, avail = 0, used = 0; uint16_t seen = 0, done = 0; };
struct Reply { unsigned queue; uint16_t head; std::vector<Descriptor> outputs; std::vector<uint8_t> bytes; };
template<class T> T packet(const std::vector<uint8_t>& data) {
    if (data.size() < sizeof(T)) throw std::invalid_argument("Truncated GPU command");
    T result; std::memcpy(&result, data.data(), sizeof(result)); return result;
}
template<class T> std::vector<uint8_t> packed(const T& value) {
    const auto* p = reinterpret_cast<const uint8_t*>(&value); return {p, p + sizeof(value)};
}
}
struct Gpu::Impl {
    rvvm_machine_t* machine;
    rvvm_irq_dev_t* irq;
    rvvm_irq_t irqNumber = RVVM_IRQ_INVALID;
    Display& display;
    const bool vulkan;
    uint32_t sharedMemorySelect = 0;
    uint64_t supportedFeatures() const { return 0x100000001ULL | (vulkan ? 0x18ULL : 0); }
    std::mutex mutex;
    std::condition_variable changed;
    std::thread thread;
    bool stopped = false, initialized = false, resetting = false;
    std::string failure;
    std::array<Queue, 2> queues{};
    uint32_t featureSelect = 0, driverSelect = 0, queueSelect = 0, status = 0, interrupt = 0;
    uint64_t driverFeatures = 0;
    EGLDisplay eglDisplay = EGL_NO_DISPLAY;
    EGLConfig eglConfig{};
    EGLSurface surface = EGL_NO_SURFACE;
    EGLContext rootContext = EGL_NO_CONTEXT;
    bool renderer = false;
    struct GlContext { EGLContext context; EGLSurface surface; };
    struct Resource { std::vector<iovec> backing; uint64_t size = 0; uint32_t width, height, format;
        bool blob = false; void* mapping = nullptr; uint64_t offset = 0, mappedSize = 0; };
    std::unordered_map<uint32_t, Resource> resources;
    std::unordered_set<uint32_t> contexts;
    std::unordered_set<uint32_t> venusContexts;
    std::mutex callbackMutex;
    std::deque<uint32_t> retiredLegacy;
    std::deque<uint64_t> retiredContexts;
    std::unordered_map<uint64_t, Reply> contextFences;
    uint64_t contextSequence = 0;
    uint64_t allocated = 0;
    uint32_t scanout = 0, nextFence = 0;
    unsigned errorsLogged = 0;
    std::deque<std::pair<uint32_t, Reply>> fences;

    Impl(rvvm_machine_t* m, rvvm_irq_dev_t* i, Display& d, bool vk) : machine(m), irq(i), display(d), vulkan(vk) {}
    ~Impl() { shutdown(); }
    void shutdown() {
        { std::lock_guard lock(mutex); stopped = true; changed.notify_all(); }
        if (thread.joinable()) thread.join();
    }
    template<class T> T read(uint64_t address) {
        T value{};
        if (!rvvm_read_ram(machine, &value, address, sizeof(value))) throw std::runtime_error("Invalid GPU DMA address");
        return value;
    }
    template<class T> void write(uint64_t address, const T& value) {
        if (!rvvm_write_ram(machine, address, &value, sizeof(value))) throw std::runtime_error("Invalid GPU DMA address");
    }
    void setInterrupt(uint32_t bits) { interrupt |= bits; rvvm_irq_set(irq, irqNumber, interrupt != 0); }
    uint32_t mmioRead(size_t offset) {
        std::lock_guard lock(mutex);
        switch (offset) {
        case 0x000: return 0x74726976;
        case 0x004: return 2;
        case 0x008: return 16; // GPU device ID
        case 0x00c: return 0x554d4551;
        case 0x010: return featureSelect < 2 ? uint32_t(supportedFeatures() >> (featureSelect * 32)) : 0;
        case 0x034: return queueSelect < 2 ? QueueMax : 0;
        case 0x044: return queueSelect < 2 ? queues[queueSelect].ready : 0;
        case 0x060: return interrupt;
        case 0x070: return status;
        case 0x0b0: return vulkan && sharedMemorySelect == 1 ? uint32_t(HostMemorySize) : 0xffffffff;
        case 0x0b4: return vulkan && sharedMemorySelect == 1 ? 0 : 0xffffffff;
        case 0x0b8: return vulkan && sharedMemorySelect == 1 ? uint32_t(HostMemoryBase) : 0;
        case 0x0bc: return 0;
        case 0x0fc: return 0; // stable config generation
        case 0x108: return 1; // one scanout
        case 0x10c: return vulkan ? 3 : 2; // VIRGL, VIRGL2, optional VENUS
        default: return 0;
        }
    }
    void mmioWrite(size_t offset, uint32_t value) {
        std::lock_guard lock(mutex);
        switch (offset) {
        case 0x014: featureSelect = value; break;
        case 0x0ac: sharedMemorySelect = value; break;
        case 0x020:
            if (driverSelect < 2) {
                unsigned shift = driverSelect * 32;
                driverFeatures = (driverFeatures & ~(uint64_t(0xffffffff) << shift)) | (uint64_t(value) << shift);
            }
            break;
        case 0x024: driverSelect = value; break;
        case 0x030: queueSelect = value; break;
        case 0x050: changed.notify_one(); break;
        case 0x064: interrupt &= ~value; rvvm_irq_set(irq, irqNumber, interrupt != 0); break;
        case 0x070:
            if (!value) {
                queues = {}; status = interrupt = 0; driverFeatures = 0;
                resetting = true; rvvm_irq_lower(irq, irqNumber); changed.notify_one();
            } else {
                status = value;
                if ((status & 8) && ((driverFeatures & ~supportedFeatures()) || !(driverFeatures & (uint64_t(1) << 32)))) status &= ~8u;
            }
            break;
        default:
            if (queueSelect >= 2) break;
            auto& q = queues[queueSelect];
            if (offset == 0x038 && !q.ready) q.size = (value && value <= QueueMax && !(value & (value - 1))) ? value : 0;
            // Per-queue reset is not advertised. Only a whole-device reset may
            // invalidate a live ring while renderer fences still reference it.
            else if (offset == 0x044 && value == 1 && q.size) q.ready = 1;
            else if (!q.ready) {
                uint64_t* addr = nullptr;
                if (offset == 0x080 || offset == 0x084) addr = &q.desc;
                if (offset == 0x090 || offset == 0x094) addr = &q.avail;
                if (offset == 0x0a0 || offset == 0x0a4) addr = &q.used;
                if (addr) { unsigned shift = (offset & 4) ? 32 : 0; *addr = (*addr & ~(uint64_t(0xffffffff) << shift)) | (uint64_t(value) << shift); }
            }
        }
    }
    EGLContext context(bool shared, int minor) {
        EGLint attrs[] = {EGL_CONTEXT_MAJOR_VERSION_KHR, 3, EGL_CONTEXT_MINOR_VERSION_KHR, std::min(minor, 2), EGL_NONE};
        return eglCreateContext(eglDisplay, eglConfig, shared ? rootContext : EGL_NO_CONTEXT, attrs);
    }
    void initRenderer() {
        if (vulkan) { auto support = probeVulkan(); if (!support.available) throw std::runtime_error("Vulkan unavailable: " + support.detail); }
        eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        EGLint major, minor, count;
        if (eglDisplay == EGL_NO_DISPLAY || !eglInitialize(eglDisplay, &major, &minor) || !eglBindAPI(EGL_OPENGL_ES_API))
            throw std::runtime_error("GPU unavailable: cannot initialize Android EGL");
        const EGLint config[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
        if (!eglChooseConfig(eglDisplay, config, &eglConfig, 1, &count) || count != 1)
            throw std::runtime_error("GPU unavailable: OpenGL ES 3 is required");
        const EGLint size[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
        surface = eglCreatePbufferSurface(eglDisplay, eglConfig, size);
        rootContext = context(false, 0);
        if (surface == EGL_NO_SURFACE || rootContext == EGL_NO_CONTEXT || !eglMakeCurrent(eglDisplay, surface, surface, rootContext))
            throw std::runtime_error("GPU unavailable: cannot create GLES context");
        static virgl_renderer_callbacks callbacks = [] {
            virgl_renderer_callbacks c{}; c.version = 3;
            c.create_gl_context = [](void* p, int, virgl_renderer_gl_ctx_param* params) -> void* {
                // ctx0 and all guest contexts must see the same textures/buffers.
                // Our persistent root is outside VirGL, so even its first context
                // (shared=false in the callback) joins that root's share group.
                auto& g = *static_cast<Impl*>(p);
                auto context = g.context(true, params->minor_ver);
                const EGLint size[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
                auto surface = eglCreatePbufferSurface(g.eglDisplay, g.eglConfig, size);
                if (context == EGL_NO_CONTEXT || surface == EGL_NO_SURFACE) {
                    if (context != EGL_NO_CONTEXT) eglDestroyContext(g.eglDisplay, context);
                    if (surface != EGL_NO_SURFACE) eglDestroySurface(g.eglDisplay, surface);
                    return nullptr;
                }
                return new GlContext{context, surface};
            };
            c.destroy_gl_context = [](void* p, void* ctx) {
                auto& g = *static_cast<Impl*>(p); auto* gl = static_cast<GlContext*>(ctx);
                if (gl) { eglDestroyContext(g.eglDisplay, gl->context); eglDestroySurface(g.eglDisplay, gl->surface); delete gl; }
            };
            c.make_current = [](void* p, int, void* ctx) {
                auto& g = *static_cast<Impl*>(p); auto* gl = static_cast<GlContext*>(ctx);
                // Renderer fence workers cannot share a current EGLSurface
                // with the submission thread, even when contexts share objects.
                return eglMakeCurrent(g.eglDisplay, gl ? gl->surface : EGL_NO_SURFACE,
                    gl ? gl->surface : EGL_NO_SURFACE, gl ? gl->context : EGL_NO_CONTEXT) ? 0 : -1;
            };
            c.write_fence = [](void* p, uint32_t id) {
                auto& g = *static_cast<Impl*>(p);
                std::lock_guard lock(g.callbackMutex); g.retiredLegacy.push_back(id);
                g.changed.notify_one();
            };
            c.write_context_fence = [](void* p, uint32_t, uint32_t, uint64_t id) {
                auto& g = *static_cast<Impl*>(p);
                std::lock_guard lock(g.callbackMutex); g.retiredContexts.push_back(id);
                g.changed.notify_one();
            };
            return c;
        }();
        if (virgl_renderer_init(this, VIRGL_RENDERER_USE_GLES | (vulkan ? VIRGL_RENDERER_VENUS | VIRGL_RENDERER_THREAD_SYNC | VIRGL_RENDERER_ASYNC_FENCE_CB : 0), &callbacks))
            throw std::runtime_error("GPU unavailable: VirGL initialization failed");
        renderer = true;
    }
    void cleanupRenderer() {
        if (renderer) { unmapAll(); virgl_renderer_cleanup(this); renderer = false; }
        resources.clear(); contexts.clear(); venusContexts.clear(); fences.clear(); contextFences.clear();
        if (eglDisplay != EGL_NO_DISPLAY) {
            eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (rootContext != EGL_NO_CONTEXT) eglDestroyContext(eglDisplay, rootContext);
            if (surface != EGL_NO_SURFACE) eglDestroySurface(eglDisplay, surface);
            eglTerminate(eglDisplay);
        }
        eglReleaseThread();
    }
    void unmapAll() {
        for (auto& [id, res] : resources) if (res.mapping) {
            virgl_renderer_resource_unmap(id); res.mapping = nullptr;
        }
    }
    void retireFences() {
        std::deque<uint32_t> legacy;
        std::deque<uint64_t> perContext;
        { std::lock_guard lock(callbackMutex);
          legacy.swap(retiredLegacy); perContext.swap(retiredContexts); }
        for (auto id : legacy) while (!fences.empty() && fences.front().first <= id) {
            complete(fences.front().second); fences.pop_front();
        }
        for (auto id : perContext) {
            auto it = contextFences.find(id);
            if (it != contextFences.end()) { complete(it->second); contextFences.erase(it); }
        }
    }
    void hostMemory(void* data, size_t size, uint64_t off, bool writing) {
        std::lock_guard lock(mutex);
        for (auto& [id, res] : resources) {
            (void)id;
            if (res.mapping && off >= res.offset && off - res.offset <= res.mappedSize &&
                size <= res.mappedSize - (off - res.offset)) {
                auto* ptr = static_cast<uint8_t*>(res.mapping) + off - res.offset;
                if (writing) std::memcpy(ptr, data, size); else std::memcpy(data, ptr, size);
                return;
            }
        }
        if (!writing) std::memset(data, 0, size);
    }
    void complete(Reply& reply) {
        auto& q = queues[reply.queue];
        size_t offset = 0;
        for (auto& d : reply.outputs) {
            size_t n = std::min<size_t>(d.length, reply.bytes.size() - offset);
            if (n && !rvvm_write_ram(machine, d.address, reply.bytes.data() + offset, n)) throw std::runtime_error("Invalid GPU response buffer");
            offset += n;
        }
        uint64_t entry = q.used + 4 + uint64_t(q.done % q.size) * 8;
        write<uint32_t>(entry, reply.head); write<uint32_t>(entry + 4, offset);
        std::atomic_thread_fence(std::memory_order_release);
        write<uint16_t>(q.used + 2, ++q.done);
        if (!(read<uint16_t>(q.avail) & 1)) setInterrupt(1);
    }
    Resource& resource(uint32_t id) {
        auto it = resources.find(id);
        if (it == resources.end()) throw std::invalid_argument("Unknown GPU resource");
        return it->second;
    }
    void create(virgl_renderer_resource_create_args args) {
        if (!args.handle || resources.count(args.handle) || resources.size() >= 1024 || args.target > 10 ||
            !args.width || args.width > 32 * 1024 * 1024 || args.height > 4096 || args.depth > 256 ||
            args.array_size > 256 || args.last_level > 12 || args.nr_samples > 8)
            throw std::invalid_argument("Invalid GPU resource dimensions");
        uint64_t cost = uint64_t(args.width) * std::max(args.height, 1u) * std::max(args.depth, 1u) *
            std::max(args.array_size, 1u) * std::max(args.nr_samples, 1u);
        if (cost > MemoryLimit / 8 || allocated + cost * 8 > MemoryLimit) throw std::invalid_argument("GPU memory budget exceeded");
        if (virgl_renderer_resource_create(&args, nullptr, 0)) throw std::invalid_argument("Unsupported GPU resource");
        resources.emplace(args.handle, Resource{{}, cost * 8, args.width, args.height, args.format}); allocated += cost * 8;
    }
    void present() {
        if (!scanout) return;
        auto& res = resource(scanout);
        if (res.width != Display::Width || res.height != Display::Height) return;
        // Readback is deliberately retained for the first implementation. The
        // renderer performs 3D on the host; Android's existing view presents pixels.
        std::vector<uint8_t> pixels(Display::Width * Display::Height * 4);
        iovec target{pixels.data(), pixels.size()};
        virgl_box box{0, 0, 0, Display::Width, Display::Height, 1};
        virgl_renderer_force_ctx_0();
        if (virgl_renderer_transfer_read_iov(scanout, 0, 0, Display::Width * 4, 0, &box, 0, &target, 1))
            throw std::invalid_argument("GPU scanout readback failed");
        // virtio/virgl BGRA formats 1/2, RGBA formats 67/68.
        if (res.format == 67 || res.format == 68)
            for (size_t i = 0; i < pixels.size(); i += 4) std::swap(pixels[i], pixels[i + 2]);
        display.update(pixels);
    }
    std::vector<uint8_t> execute(const std::vector<uint8_t>& bytes) {
        auto h = packet<virtio_gpu_ctrl_hdr>(bytes);
        virtio_gpu_ctrl_hdr response{}; response.type = VIRTIO_GPU_RESP_OK_NODATA;
        response.flags = h.flags & (VIRTIO_GPU_FLAG_FENCE | VIRTIO_GPU_FLAG_INFO_RING_IDX); response.ring_idx = h.ring_idx; response.fence_id = h.fence_id; response.ctx_id = h.ctx_id;
        try {
            switch (h.type) {
            case VIRTIO_GPU_CMD_GET_DISPLAY_INFO: {
                virtio_gpu_resp_display_info r{}; r.hdr = response; r.hdr.type = VIRTIO_GPU_RESP_OK_DISPLAY_INFO;
                r.pmodes[0].enabled = 1; r.pmodes[0].r = {0, 0, Display::Width, Display::Height}; return packed(r);
            }
            case VIRTIO_GPU_CMD_GET_CAPSET_INFO: {
                auto p = packet<virtio_gpu_get_capset_info>(bytes);
                if (p.capset_index > (vulkan ? 2u : 1u)) throw std::invalid_argument("Invalid capset index");
                virtio_gpu_resp_capset_info r{}; r.hdr = response; r.hdr.type = VIRTIO_GPU_RESP_OK_CAPSET_INFO;
                r.capset_id = p.capset_index == 2 ? VenusCapset : p.capset_index + 1;
                virgl_renderer_get_cap_set(r.capset_id, &r.capset_max_version, &r.capset_max_size); return packed(r);
            }
            case VIRTIO_GPU_CMD_GET_CAPSET: {
                auto p = packet<virtio_gpu_get_capset>(bytes); uint32_t version = 0, size = 0;
                if (p.capset_id < 1 || (p.capset_id > 2 && !(vulkan && p.capset_id == VenusCapset))) throw std::invalid_argument("Invalid capset");
                virgl_renderer_get_cap_set(p.capset_id, &version, &size);
                if (p.capset_version > version || size > CommandLimit) throw std::invalid_argument("Invalid capset version");
                response.type = VIRTIO_GPU_RESP_OK_CAPSET;
                auto result = packed(response); result.resize(sizeof(response) + size);
                virgl_renderer_fill_caps(p.capset_id, p.capset_version, result.data() + sizeof(response)); return result;
            }
            case VIRTIO_GPU_CMD_RESOURCE_CREATE_BLOB: {
                auto p = packet<virtio_gpu_resource_create_blob>(bytes);
                if (!vulkan || !p.resource_id || resources.count(p.resource_id) || resources.size() >= 1024 ||
                    !p.size || p.size > MemoryLimit || allocated > MemoryLimit - p.size ||
                    (p.blob_mem != VIRTIO_GPU_BLOB_MEM_HOST3D && p.blob_mem != VIRTIO_GPU_BLOB_MEM_GUEST) ||
                    (p.blob_flags & ~7u) || p.nr_entries > 32768 ||
                    bytes.size() != sizeof(p) + size_t(p.nr_entries) * sizeof(virtio_gpu_mem_entry) ||
                    (p.blob_mem == VIRTIO_GPU_BLOB_MEM_HOST3D && (!contexts.count(h.ctx_id) || p.nr_entries)))
                    throw std::invalid_argument("Invalid Vulkan blob");
                Resource res{{}, p.size, 0, 0, 0};
                res.blob = true;
                uint64_t backingSize = 0;
                for (unsigned i = 0; i < p.nr_entries; ++i) {
                    virtio_gpu_mem_entry e; std::memcpy(&e, bytes.data()+sizeof(p)+i*sizeof(e), sizeof(e));
                    void* ptr = rvvm_get_dma_ptr(machine, e.addr, e.length);
                    if (!ptr || !e.length || e.length > MemoryLimit - backingSize) throw std::invalid_argument("Invalid blob backing");
                    backingSize += e.length; res.backing.push_back({ptr, e.length});
                }
                if (p.blob_mem == VIRTIO_GPU_BLOB_MEM_GUEST && backingSize < p.size)
                    throw std::invalid_argument("Short blob backing");
                virgl_renderer_resource_create_blob_args args{};
                args.res_handle = p.resource_id; args.ctx_id = h.ctx_id; args.blob_mem = p.blob_mem;
                args.blob_flags = p.blob_flags; args.blob_id = p.blob_id; args.size = p.size;
                args.iovecs = res.backing.data(); args.num_iovs = res.backing.size();
                // Insert first so iovec storage lives until resource destruction.
                auto [it, added] = resources.emplace(p.resource_id, std::move(res));
                if (virgl_renderer_resource_create_blob(&args)) {
                    resources.erase(it); throw std::invalid_argument("Vulkan blob creation failed");
                }
                allocated += p.size; break;
            }
            case VIRTIO_GPU_CMD_RESOURCE_MAP_BLOB: {
                auto p = packet<virtio_gpu_resource_map_blob>(bytes); auto& r = resource(p.resource_id);
                if (!vulkan || !r.blob || r.mapping || p.offset % 4096 || p.offset > HostMemorySize ||
                    r.size > HostMemorySize - p.offset) throw std::invalid_argument("Invalid blob mapping");
                for (const auto& [id, other] : resources) if (id != p.resource_id && other.mapping &&
                    p.offset < other.offset + other.mappedSize && other.offset < p.offset + r.size)
                    throw std::invalid_argument("Overlapping blob mapping");
                void* ptr = nullptr; uint64_t size = 0; uint32_t info = 0;
                if (virgl_renderer_resource_get_map_info(p.resource_id, &info) ||
                    virgl_renderer_resource_map(p.resource_id, &ptr, &size))
                    throw std::invalid_argument("Vulkan memory mapping unsupported by host");
                if (!ptr || size < r.size || size > HostMemorySize - p.offset) {
                    virgl_renderer_resource_unmap(p.resource_id); throw std::invalid_argument("Invalid mapped blob size");
                }
                r.mapping = ptr; r.mappedSize = r.size; r.offset = p.offset;
                virtio_gpu_resp_map_info reply{}; reply.hdr = response;
                reply.hdr.type = VIRTIO_GPU_RESP_OK_MAP_INFO; reply.map_info = info; return packed(reply);
            }
            case VIRTIO_GPU_CMD_RESOURCE_UNMAP_BLOB: {
                auto p = packet<virtio_gpu_resource_unmap_blob>(bytes); auto& r = resource(p.resource_id);
                if (!r.mapping || virgl_renderer_resource_unmap(p.resource_id)) throw std::invalid_argument("Invalid blob unmap");
                r.mapping = nullptr; r.mappedSize = 0; break;
            }
            case VIRTIO_GPU_CMD_RESOURCE_CREATE_2D: {
                auto p = packet<virtio_gpu_resource_create_2d>(bytes);
                create({p.resource_id, 2, p.format, VIRGL_RES_BIND_SAMPLER_VIEW | VIRGL_RES_BIND_RENDER_TARGET | VIRGL_RES_BIND_SCANOUT,
                    p.width, p.height, 1, 1, 0, 0, VIRTIO_GPU_RESOURCE_FLAG_Y_0_TOP}); break;
            }
            case VIRTIO_GPU_CMD_RESOURCE_CREATE_3D: {
                auto p = packet<virtio_gpu_resource_create_3d>(bytes);
                create({p.resource_id, p.target, p.format, p.bind, p.width, p.height, p.depth, p.array_size, p.last_level, p.nr_samples, p.flags}); break;
            }
            case VIRTIO_GPU_CMD_RESOURCE_UNREF: {
                auto p = packet<virtio_gpu_resource_unref>(bytes); auto cost = resource(p.resource_id).size;
                if (resource(p.resource_id).mapping) virgl_renderer_resource_unmap(p.resource_id);
                virgl_renderer_resource_unref(p.resource_id); resources.erase(p.resource_id); allocated -= cost;
                if (scanout == p.resource_id) scanout = 0; break;
            }
            case VIRTIO_GPU_CMD_RESOURCE_ATTACH_BACKING: {
                auto p = packet<virtio_gpu_resource_attach_backing>(bytes); auto& r = resource(p.resource_id);
                if (!r.backing.empty() || p.nr_entries > 32768 || bytes.size() != sizeof(p) + size_t(p.nr_entries) * sizeof(virtio_gpu_mem_entry))
                    throw std::invalid_argument("Invalid GPU backing entries");
                std::vector<iovec> entries; uint64_t size = 0;
                for (unsigned i = 0; i < p.nr_entries; ++i) {
                    virtio_gpu_mem_entry e; std::memcpy(&e, bytes.data() + sizeof(p) + i * sizeof(e), sizeof(e));
                    size += e.length;
                    void* ptr = rvvm_get_dma_ptr(machine, e.addr, e.length);
                    if (!ptr || !e.length || size > MemoryLimit) throw std::invalid_argument("Invalid GPU backing memory");
                    entries.push_back({ptr, e.length});
                }
                r.backing = std::move(entries);
                if (virgl_renderer_resource_attach_iov(p.resource_id, r.backing.data(), r.backing.size())) { r.backing.clear(); throw std::invalid_argument("GPU attach failed"); }
                break;
            }
            case VIRTIO_GPU_CMD_RESOURCE_DETACH_BACKING: {
                auto p = packet<virtio_gpu_resource_detach_backing>(bytes); auto& r = resource(p.resource_id);
                virgl_renderer_resource_detach_iov(p.resource_id, nullptr, nullptr); r.backing.clear(); break;
            }
            case VIRTIO_GPU_CMD_CTX_CREATE: {
                auto p = packet<virtio_gpu_ctx_create>(bytes);
                // context_init=0 is the legacy protocol, not capset ID zero.
                uint32_t capset = p.context_init & VIRGL_RENDERER_CONTEXT_FLAG_CAPSET_ID_MASK;
                if (!h.ctx_id || contexts.count(h.ctx_id) || contexts.size() >= 64 || p.nlen > 64 ||
                    (p.context_init & ~VIRGL_RENDERER_CONTEXT_FLAG_CAPSET_ID_MASK) ||
                    (capset != 0 && capset != 1 && capset != 2 && !(vulkan && capset == VenusCapset)) ||
                    (p.context_init
                        ? virgl_renderer_context_create_with_flags(h.ctx_id, p.context_init, p.nlen, p.debug_name)
                        : virgl_renderer_context_create(h.ctx_id, p.nlen, p.debug_name)))
                    throw std::invalid_argument("Invalid GPU context");
                if (capset == VenusCapset) venusContexts.insert(h.ctx_id);
                contexts.insert(h.ctx_id); break;
            }
            case VIRTIO_GPU_CMD_CTX_DESTROY:
                if (!contexts.erase(h.ctx_id)) throw std::invalid_argument("Unknown GPU context");
                venusContexts.erase(h.ctx_id);
                virgl_renderer_context_destroy(h.ctx_id); break;
            case VIRTIO_GPU_CMD_CTX_ATTACH_RESOURCE:
            case VIRTIO_GPU_CMD_CTX_DETACH_RESOURCE: {
                auto p = packet<virtio_gpu_ctx_resource>(bytes); resource(p.resource_id);
                if (!contexts.count(h.ctx_id)) throw std::invalid_argument("Unknown GPU context");
                if (h.type == VIRTIO_GPU_CMD_CTX_ATTACH_RESOURCE) virgl_renderer_ctx_attach_resource(h.ctx_id, p.resource_id);
                else virgl_renderer_ctx_detach_resource(h.ctx_id, p.resource_id); break;
            }
            case VIRTIO_GPU_CMD_SUBMIT_3D: {
                auto p = packet<virtio_gpu_cmd_submit>(bytes);
                if (!contexts.count(h.ctx_id)) throw std::invalid_argument("Unknown GPU submission context");
                if (p.size % 4 || p.size != bytes.size() - sizeof(p))
                    throw std::invalid_argument("Invalid GPU submission size: declared=" + std::to_string(p.size) +
                        " actual=" + std::to_string(bytes.size() - sizeof(p)));
                // Snapshot the command stream: virgl must never decode mutable guest memory.
                std::vector<uint32_t> commands(p.size / 4); std::memcpy(commands.data(), bytes.data() + sizeof(p), p.size);
                int result = virgl_renderer_submit_cmd(commands.data(), h.ctx_id, commands.size());
                if (result) throw std::invalid_argument("GPU submission failed: " + std::to_string(result)); break;
            }
            case VIRTIO_GPU_CMD_TRANSFER_TO_HOST_2D: {
                auto p = packet<virtio_gpu_transfer_to_host_2d>(bytes); resource(p.resource_id);
                virgl_box box{p.r.x, p.r.y, 0, p.r.width, p.r.height, 1};
                if (virgl_renderer_transfer_write_iov(p.resource_id, 0, 0, 0, 0, &box, p.offset, nullptr, 0)) throw std::invalid_argument("GPU transfer failed"); break;
            }
            case VIRTIO_GPU_CMD_TRANSFER_TO_HOST_3D:
            case VIRTIO_GPU_CMD_TRANSFER_FROM_HOST_3D: {
                auto p = packet<virtio_gpu_transfer_host_3d>(bytes); resource(p.resource_id);
                if (h.ctx_id && !contexts.count(h.ctx_id)) throw std::invalid_argument("Unknown GPU context");
                virgl_box box{p.box.x, p.box.y, p.box.z, p.box.w, p.box.h, p.box.d};
                int result = h.type == VIRTIO_GPU_CMD_TRANSFER_TO_HOST_3D ?
                    virgl_renderer_transfer_write_iov(p.resource_id, h.ctx_id, p.level, p.stride, p.layer_stride, &box, p.offset, nullptr, 0) :
                    virgl_renderer_transfer_read_iov(p.resource_id, h.ctx_id, p.level, p.stride, p.layer_stride, &box, p.offset, nullptr, 0);
                if (result) throw std::invalid_argument("GPU transfer failed"); break;
            }
            case VIRTIO_GPU_CMD_SET_SCANOUT: {
                auto p = packet<virtio_gpu_set_scanout>(bytes);
                if (p.scanout_id) throw std::invalid_argument("Invalid scanout");
                if (p.resource_id) {
                    auto& r = resource(p.resource_id);
                    if (p.r.x || p.r.y || p.r.width != Display::Width || p.r.height != Display::Height ||
                        r.width != Display::Width || r.height != Display::Height ||
                        (r.format != 1 && r.format != 2 && r.format != 67 && r.format != 68)) throw std::invalid_argument("Unsupported scanout mode");
                }
                scanout = p.resource_id;
                // Page flips can flush the new buffer before selecting it.
                // Present on selection too, otherwise alternating buffers
                // never match scanout when RESOURCE_FLUSH arrives.
                if (scanout) present();
                break;
            }
            case VIRTIO_GPU_CMD_RESOURCE_FLUSH: {
                auto p = packet<virtio_gpu_resource_flush>(bytes); resource(p.resource_id);
                if (p.resource_id == scanout) present(); break;
            }
            // Xorg uses SWcursor. The unused cursor queue must still complete.
            case VIRTIO_GPU_CMD_UPDATE_CURSOR:
            case VIRTIO_GPU_CMD_MOVE_CURSOR: packet<virtio_gpu_update_cursor>(bytes); break;
            default: throw std::invalid_argument("Unsupported GPU command");
            }
        } catch (const std::invalid_argument& e) {
            if (errorsLogged++ < 20) std::fprintf(stderr, "Flyby GPU command 0x%x: %s\n", h.type, e.what());
            response.type = VIRTIO_GPU_RESP_ERR_INVALID_PARAMETER;
        }
        return packed(response);
    }
    bool process(unsigned index) {
        auto& q = queues[index];
        if (!q.ready || !q.size || !(status & 4) || (status & (64 | 128))) return false;
        if (uint16_t(q.seen - q.done) >= q.size) return false;
        uint16_t available = read<uint16_t>(q.avail + 2);
        std::atomic_thread_fence(std::memory_order_acquire);
        if (q.seen == available) return false;
        if (uint16_t(available - q.seen) > q.size) throw std::runtime_error("Invalid GPU queue index");
        uint16_t head = read<uint16_t>(q.avail + 4 + uint64_t(q.seen % q.size) * 2), next = head;
        Reply reply{index, head, {}, {}};
        std::vector<uint8_t> command;
        size_t outputSize = 0;
        std::array<bool, QueueMax> visited{};
        for (;;) {
            if (next >= q.size || visited[next]) throw std::runtime_error("Invalid GPU descriptor chain");
            visited[next] = true;
            auto d = read<Descriptor>(q.desc + uint64_t(next) * sizeof(Descriptor));
            if (d.flags & ~3u) throw std::runtime_error("Unsupported GPU descriptor flags");
            if (d.length > CommandLimit || !rvvm_get_dma_ptr(machine, d.address, d.length)) throw std::runtime_error("Invalid GPU descriptor memory");
            if (d.flags & 2) {
                outputSize += d.length;
                if (outputSize > CommandLimit) throw std::runtime_error("GPU response too large");
                reply.outputs.push_back(d);
            } else {
                if (!reply.outputs.empty() || command.size() + d.length > CommandLimit) throw std::runtime_error("Invalid GPU request size");
                size_t start = command.size(); command.resize(start + d.length);
                if (!rvvm_read_ram(machine, command.data() + start, d.address, d.length)) throw std::runtime_error("Invalid GPU request memory");
            }
            if (!(d.flags & 1)) break;
            next = d.next;
        }
        auto hdr = packet<virtio_gpu_ctrl_hdr>(command);
        reply.bytes = execute(command);
        if (index == 1) reply.bytes.clear();
        else if (outputSize < reply.bytes.size()) throw std::runtime_error("GPU response buffer too short");
        ++q.seen;
        if ((hdr.flags & VIRTIO_GPU_FLAG_FENCE) && index == 0) {
            if ((hdr.flags & VIRTIO_GPU_FLAG_INFO_RING_IDX) && venusContexts.count(hdr.ctx_id)) {
                if (++contextSequence == 0) throw std::runtime_error("Vulkan fence counter exhausted");
                auto id = contextSequence;
                contextFences.emplace(id, std::move(reply));
                auto ring = hdr.flags & VIRTIO_GPU_FLAG_INFO_RING_IDX ? hdr.ring_idx : 0;
                if (virgl_renderer_context_create_fence(hdr.ctx_id, 0, ring, id))
                    throw std::runtime_error("Vulkan fence creation failed");
                return true;
            }
            if (++nextFence == 0) throw std::runtime_error("GPU fence counter exhausted; restart Linux");
            uint32_t id = nextFence;
            fences.emplace_back(id, std::move(reply));
            if (virgl_renderer_create_fence(id, 0)) throw std::runtime_error("GPU fence creation failed");
        } else complete(reply);
        return true;
    }
    void run() {
        std::unique_lock owner(rendererOwner, std::try_to_lock);
        try {
            if (!owner.owns_lock()) throw std::runtime_error("Another GPU VM is already active");
            initRenderer();
            std::unique_lock lock(mutex);
            initialized = true; changed.notify_all();
            while (!stopped) {
                if (resetting) {
                    unmapAll(); fences.clear(); contextFences.clear(); virgl_renderer_reset(); resources.clear(); contexts.clear(); venusContexts.clear();
                    { std::lock_guard callbackLock(callbackMutex); retiredLegacy.clear(); retiredContexts.clear(); }
                    allocated = scanout = nextFence = 0; resetting = false;
                }
                for (unsigned i = 0; i < 32; ++i) { bool worked = process(0); worked = process(1) || worked; if (!worked) break; }
                virgl_renderer_poll(); retireFences();
                changed.wait_for(lock, std::chrono::milliseconds(2));
            }
        } catch (const std::exception& e) {
            std::lock_guard lock(mutex); failure = e.what(); initialized = true; changed.notify_all();
        }
        if (owner.owns_lock()) cleanupRenderer();
    }
};
Gpu::Gpu(rvvm_machine_t* machine, rvvm_irq_dev_t* irq, Display& display, bool vulkan)
    : impl(std::make_unique<Impl>(machine, irq, display, vulkan)) {
    impl->thread = std::thread([this] { impl->run(); });
    { std::unique_lock lock(impl->mutex); impl->changed.wait(lock, [this] { return impl->initialized; }); }
    check();
    impl->irqNumber = rvvm_irq_alloc(irq, 0);
    if (impl->irqNumber == RVVM_IRQ_INVALID) throw std::runtime_error("GPU interrupt unavailable");
    static rvvm_reg_type_t type = [] {
        rvvm_reg_type_t t{}; t.name = "flyby-virtio-gpu"; t.min_size = t.max_size = 4;
        t.read = [](rvvm_reg_dev_t* d, void* data, size_t, size_t off) { auto value = static_cast<Impl*>(rvvm_region_data(d))->mmioRead(off); std::memcpy(data, &value, 4); };
        t.write = [](rvvm_reg_dev_t* d, const void* data, size_t, size_t off) { uint32_t value; std::memcpy(&value, data, 4); static_cast<Impl*>(rvvm_region_data(d))->mmioWrite(off, value); };
        t.cleanup = [](rvvm_reg_dev_t* d) { auto* g = static_cast<Impl*>(rvvm_region_data(d)); g->shutdown(); rvvm_irq_dealloc(g->irq, g->irqNumber); };
        return t;
    }();
    rvvm_reg_desc_t desc{}; desc.addr = GpuAddress; desc.size = 0x1000; desc.attr = RVVM_REG_ATTR_FIX;
    desc.data = impl.get(); desc.type = &type;
    if (!rvvm_region_init(machine, &desc)) throw std::runtime_error("Cannot attach virtual GPU");
    if (vulkan) {
        static rvvm_reg_type_t memoryType = [] {
            rvvm_reg_type_t t{}; t.name = "flyby-gpu-host-memory"; t.min_size = 1; t.max_size = 8;
            t.read = [](rvvm_reg_dev_t* d, void* data, size_t size, size_t off) { static_cast<Impl*>(rvvm_region_data(d))->hostMemory(data, size, off, false); };
            t.write = [](rvvm_reg_dev_t* d, const void* data, size_t size, size_t off) { static_cast<Impl*>(rvvm_region_data(d))->hostMemory(const_cast<void*>(data), size, off, true); };
            return t;
        }();
        rvvm_reg_desc_t memory{}; memory.addr = HostMemoryBase; memory.size = HostMemorySize;
        memory.attr = RVVM_REG_ATTR_FIX; memory.type = &memoryType; memory.data = impl.get();
        if (!rvvm_region_init(machine, &memory)) throw std::runtime_error("Cannot attach Vulkan host memory");
    }
    auto* node = rvvm_fdt_init_reg("virtio_mmio", GpuAddress);
    rvvm_fdt_prop_set_str(node, "compatible", "virtio,mmio");
    rvvm_fdt_prop_set_reg(node, "reg", GpuAddress, 0x1000);
    rvvm_irq_fdt_describe(node, irq, impl->irqNumber);
    rvvm_fdt_attach(rvvm_get_fdt_soc(machine), node);
}
Gpu::~Gpu() = default;
void Gpu::check() { std::lock_guard lock(impl->mutex); if (!impl->failure.empty()) throw std::runtime_error(impl->failure); }
}
