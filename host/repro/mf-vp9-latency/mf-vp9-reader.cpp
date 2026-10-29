// mf-vp9-reader: the decode path of WSACodecs.dll (Latte, docs/own-host.md) rebuilt without WSA. A media source of
// our own hands IVF packets to a Source Reader created with the attributes CDecoderMFTWrapper::CreateSourceReader sets
// (rva 0x2403: MF_LOW_LATENCY, MF_READWRITE_ENABLE_HARDWARE_TRANSFORMS, MF_SOURCE_READER_ENABLE_ADVANCED_VIDEO_PROCESSING,
// the private 043ff126-…, the async callback, MF_XVP_DISABLE_FRC, MF_SOURCE_READER_D3D_MANAGER) and the input type of
// VP9CodecHandler::CreateMediaType. Counts the packets the reader asked for before its first frame and the frames it
// returns after the source said end of stream. mf-vp9-latency.cpp feeds the decoder MFT directly instead.
//
//   mf-vp9-reader.exe <file.ivf|file.m4v> [--subtype vp80|vp90|av01] [--no-d3d] [--no-lowlatency] [--live] [--no-hw] [--true-size] [--seq-header]
//     .m4v: MPEG-4 Part 2 (MP4VCodecHandler of WSACodecs); --no-hw: MF_READWRITE_ENABLE_HARDWARE_TRANSFORMS off;
//     --true-size: MF_MT_FRAME_SIZE width x height (WSACodecs puts the width in both); --seq-header: the first packet
//     as MF_MT_MPEG_SEQUENCE_HEADER instead of a sample
//
// 29.10.2026.
#define _CRT_SECURE_NO_WARNINGS
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <mferror.h>
#include <d3d11.h>
#include <d3d10.h>
#include <wrl/implements.h>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <vector>

#pragma comment(lib, "mfplat.lib")
#pragma comment(lib, "mfuuid.lib")
#pragma comment(lib, "mfreadwrite.lib")
#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "ole32.lib")

using Microsoft::WRL::ClassicCom;
using Microsoft::WRL::ComPtr;
using Microsoft::WRL::RuntimeClass;
using Microsoft::WRL::RuntimeClassFlags;

static const GUID WSA_PRIVATE_043ff126 = {0x043ff126, 0xfe2c, 0x4708, {0xa0, 0x9b, 0xda, 0x2a, 0xb4, 0x35, 0xce, 0xd9}};

struct Ivf
{
    UINT w = 0, h = 0, rate = 30, scale = 1;
    std::vector<std::vector<BYTE>> frames;
};

static bool ReadIvf(const char* path, Ivf& ivf)
{
    FILE* f = std::fopen(path, "rb");
    if (!f) return false;
    BYTE hdr[32];
    if (std::fread(hdr, 1, 32, f) != 32 || std::memcmp(hdr, "DKIF", 4)) { std::fclose(f); return false; }
    ivf.w = hdr[12] | hdr[13] << 8;
    ivf.h = hdr[14] | hdr[15] << 8;
    ivf.rate = hdr[16] | hdr[17] << 8 | hdr[18] << 16 | hdr[19] << 24;
    ivf.scale = hdr[20] | hdr[21] << 8 | hdr[22] << 16 | hdr[23] << 24;
    BYTE fh[12];
    while (std::fread(fh, 1, 12, f) == 12)
    {
        UINT size = fh[0] | fh[1] << 8 | fh[2] << 16 | fh[3] << 24;
        std::vector<BYTE> data(size);
        if (std::fread(data.data(), 1, size, f) != size) break;
        ivf.frames.push_back(std::move(data));
    }
    std::fclose(f);
    return true;
}

// An MPEG-4 Part 2 elementary stream (ffmpeg -c:v mpeg4 -f m4v): the headers up to the first VOP go first, alone,
// as Android queues csd-0 (the VOL), then one VOP (start code 00 00 01 b6) per packet. The size is not in the file.
static bool ReadM4v(const char* path, Ivf& ivf)
{
    FILE* f = std::fopen(path, "rb");
    if (!f) return false;
    std::vector<BYTE> d;
    BYTE buf[65536];
    for (size_t n; (n = std::fread(buf, 1, sizeof buf, f)) > 0;) d.insert(d.end(), buf, buf + n);
    std::fclose(f);
    std::vector<size_t> vops;
    for (size_t i = 0; i + 4 <= d.size(); i++)
        if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1 && d[i + 3] == 0xb6) vops.push_back(i);
    if (vops.empty()) return false;
    ivf.frames.emplace_back(d.begin(), d.begin() + vops[0]);
    for (size_t k = 0; k < vops.size(); k++)
        ivf.frames.emplace_back(d.begin() + vops[k], d.begin() + (k + 1 < vops.size() ? vops[k + 1] : d.size()));
    ivf.w = 1280; ivf.h = 720; ivf.rate = 30; ivf.scale = 1;
    return true;
}

static const Ivf* g_ivf;
static std::atomic<int> g_requests{0}, g_frames{0}, g_firstAfter{-1}, g_framesAtEos{-1};
static bool g_live = false, g_noHw = false, g_trueSize = false, g_seqHeader = false;
static HANDLE g_done;
static HRESULT g_error = S_OK;

class Source;

class Stream : public RuntimeClass<RuntimeClassFlags<ClassicCom>, IMFMediaStream, IMFMediaEventGenerator>
{
public:
    ComPtr<IMFMediaEventQueue> queue;
    ComPtr<IMFStreamDescriptor> sd;
    IMFMediaSource* source = nullptr;  // not owned
    size_t next = 0;
    LONGLONG duration = 0;

    STDMETHODIMP GetEvent(DWORD f, IMFMediaEvent** e) override { return queue->GetEvent(f, e); }
    STDMETHODIMP BeginGetEvent(IMFAsyncCallback* cb, IUnknown* s) override { return queue->BeginGetEvent(cb, s); }
    STDMETHODIMP EndGetEvent(IMFAsyncResult* r, IMFMediaEvent** e) override { return queue->EndGetEvent(r, e); }
    STDMETHODIMP QueueEvent(MediaEventType t, REFGUID g, HRESULT hr, const PROPVARIANT* v) override { return queue->QueueEventParamVar(t, g, hr, v); }
    STDMETHODIMP GetMediaSource(IMFMediaSource** s) override { *s = source; source->AddRef(); return S_OK; }
    STDMETHODIMP GetStreamDescriptor(IMFStreamDescriptor** d) override { *d = sd.Get(); sd->AddRef(); return S_OK; }
    STDMETHODIMP RequestSample(IUnknown* token) override;
};

class Source : public RuntimeClass<RuntimeClassFlags<ClassicCom>, IMFMediaSource, IMFMediaEventGenerator>
{
public:
    ComPtr<IMFMediaEventQueue> queue;
    ComPtr<IMFPresentationDescriptor> pd;
    ComPtr<Stream> stream;
    bool started = false;

    STDMETHODIMP GetEvent(DWORD f, IMFMediaEvent** e) override { return queue->GetEvent(f, e); }
    STDMETHODIMP BeginGetEvent(IMFAsyncCallback* cb, IUnknown* s) override { return queue->BeginGetEvent(cb, s); }
    STDMETHODIMP EndGetEvent(IMFAsyncResult* r, IMFMediaEvent** e) override { return queue->EndGetEvent(r, e); }
    STDMETHODIMP QueueEvent(MediaEventType t, REFGUID g, HRESULT hr, const PROPVARIANT* v) override { return queue->QueueEventParamVar(t, g, hr, v); }
    STDMETHODIMP GetCharacteristics(DWORD* c) override { *c = g_live ? MFMEDIASOURCE_IS_LIVE : 0; return S_OK; }
    STDMETHODIMP CreatePresentationDescriptor(IMFPresentationDescriptor** p) override { return pd->Clone(p); }
    STDMETHODIMP Start(IMFPresentationDescriptor*, const GUID*, const PROPVARIANT*) override
    {
        PROPVARIANT empty;
        PropVariantInit(&empty);
        queue->QueueEventParamUnk(started ? MEUpdatedStream : MENewStream, GUID_NULL, S_OK, static_cast<IMFMediaStream*>(stream.Get()));
        queue->QueueEventParamVar(MESourceStarted, GUID_NULL, S_OK, &empty);
        stream->queue->QueueEventParamVar(MEStreamStarted, GUID_NULL, S_OK, &empty);
        started = true;
        return S_OK;
    }
    STDMETHODIMP Stop() override { return S_OK; }
    STDMETHODIMP Pause() override { return S_OK; }
    STDMETHODIMP Shutdown() override
    {
        queue->Shutdown();
        stream->queue->Shutdown();
        return S_OK;
    }
};

STDMETHODIMP Stream::RequestSample(IUnknown* token)
{
    int request = ++g_requests;
    (void)request;
    if (next >= g_ivf->frames.size())
    {
        // End of stream: what the guest's EOS turns into. The reader now drains the decoder.
        if (g_framesAtEos < 0) g_framesAtEos = g_frames.load();
        PROPVARIANT empty;
        PropVariantInit(&empty);
        queue->QueueEventParamVar(MEEndOfStream, GUID_NULL, S_OK, &empty);
        ComPtr<IMFMediaEventGenerator> gen;
        source->QueryInterface(IID_PPV_ARGS(&gen));
        gen->QueueEvent(MEEndOfPresentation, GUID_NULL, S_OK, &empty);
        return S_OK;
    }
    const std::vector<BYTE>& data = g_ivf->frames[next];
    ComPtr<IMFMediaBuffer> buf;
    ComPtr<IMFSample> s;
    BYTE* p = nullptr;
    MFCreateMemoryBuffer((DWORD)data.size(), &buf);
    buf->Lock(&p, nullptr, nullptr);
    std::memcpy(p, data.data(), data.size());
    buf->Unlock();
    buf->SetCurrentLength((DWORD)data.size());
    MFCreateSample(&s);
    s->AddBuffer(buf.Get());
    s->SetSampleTime((LONGLONG)next * duration);
    s->SetSampleDuration(duration);
    if (token) s->SetUnknown(MFSampleExtension_Token, token);
    next++;
    return queue->QueueEventParamUnk(MEMediaSample, GUID_NULL, S_OK, s.Get());
}

class Callback : public RuntimeClass<RuntimeClassFlags<ClassicCom>, IMFSourceReaderCallback>
{
public:
    IMFSourceReader* reader = nullptr;  // set after creation, not owned
    STDMETHODIMP OnReadSample(HRESULT hr, DWORD, DWORD flags, LONGLONG, IMFSample* sample) override
    {
        if (FAILED(hr)) { g_error = hr; SetEvent(g_done); return S_OK; }
        if (sample)
        {
            if (g_firstAfter < 0) g_firstAfter = g_requests.load();
            g_frames++;
        }
        if (flags & (MF_SOURCE_READERF_ENDOFSTREAM | MF_SOURCE_READERF_ERROR)) { SetEvent(g_done); return S_OK; }
        reader->ReadSample(MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0, nullptr, nullptr, nullptr, nullptr);
        return S_OK;
    }
    STDMETHODIMP OnFlush(DWORD) override { return S_OK; }
    STDMETHODIMP OnEvent(DWORD, IMFMediaEvent*) override { return S_OK; }
};

#define CHECK(x, s) do { HRESULT _hr = (x); if (FAILED(_hr)) { std::printf("ERROR 0x%08lx at %s\n", _hr, s); return 1; } } while (0)

int main(int argc, char** argv)
{
    if (argc < 2) { std::printf("usage: mf-vp9-reader <file.ivf|file.m4v> [--subtype vp80|vp90|av01] [--no-d3d] [--no-lowlatency] [--live] [--no-hw] [--true-size] [--seq-header]\n"); return 2; }
    GUID subtype = MFVideoFormat_VP90;
    bool d3d = true, lowLatency = true;
    for (int i = 2; i < argc; i++)
        if (!std::strcmp(argv[i], "--no-d3d")) d3d = false;
        else if (!std::strcmp(argv[i], "--no-lowlatency")) lowLatency = false;
        else if (!std::strcmp(argv[i], "--live")) g_live = true;
        else if (!std::strcmp(argv[i], "--subtype") && i + 1 < argc)
        {
            const char* s = argv[++i];
            subtype = !std::strcmp(s, "vp80") ? MFVideoFormat_VP80 : !std::strcmp(s, "av01") ? MFVideoFormat_AV1 : MFVideoFormat_VP90;
        }
        else if (!std::strcmp(argv[i], "--no-hw")) g_noHw = true;
        else if (!std::strcmp(argv[i], "--true-size")) g_trueSize = true;
        else if (!std::strcmp(argv[i], "--seq-header")) g_seqHeader = true;
    Ivf ivf;
    size_t len = std::strlen(argv[1]);
    bool m4v = len > 4 && !std::strcmp(argv[1] + len - 4, ".m4v");
    if (m4v ? !ReadM4v(argv[1], ivf) : !ReadIvf(argv[1], ivf)) { std::printf("cannot read %s\n", argv[1]); return 2; }
    if (m4v) subtype = MFVideoFormat_MP4V;
    g_ivf = &ivf;
    g_done = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    CHECK(MFStartup(MF_VERSION), "MFStartup");

    // The input type as VP9CodecHandler::CreateMediaType builds it (one field for both halves of MF_MT_FRAME_SIZE).
    ComPtr<IMFMediaType> in;
    CHECK(MFCreateMediaType(&in), "MFCreateMediaType");
    in->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    in->SetGUID(MF_MT_SUBTYPE, subtype);
    in->SetUINT32(MF_MT_COMPRESSED, 1);
    in->SetUINT32(MF_MT_ALL_SAMPLES_INDEPENDENT, 0);
    MFSetAttributeRatio(in.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
    MFSetAttributeSize(in.Get(), MF_MT_FRAME_SIZE, ivf.w, g_trueSize ? ivf.h : ivf.w);
    if (g_seqHeader)
    {
        // The first packet (csd-0) as MF_MT_MPEG_SEQUENCE_HEADER instead of a sample.
        in->SetBlob(MF_MT_MPEG_SEQUENCE_HEADER, ivf.frames[0].data(), (UINT32)ivf.frames[0].size());
        ivf.frames.erase(ivf.frames.begin());
    }

    ComPtr<Source> source = Microsoft::WRL::Make<Source>();
    ComPtr<Stream> stream = Microsoft::WRL::Make<Stream>();
    CHECK(MFCreateEventQueue(&source->queue), "MFCreateEventQueue");
    CHECK(MFCreateEventQueue(&stream->queue), "MFCreateEventQueue");
    IMFMediaType* types[] = {in.Get()};
    CHECK(MFCreateStreamDescriptor(0, 1, types, &stream->sd), "MFCreateStreamDescriptor");
    ComPtr<IMFMediaTypeHandler> th;
    stream->sd->GetMediaTypeHandler(&th);
    th->SetCurrentMediaType(in.Get());
    IMFStreamDescriptor* sds[] = {stream->sd.Get()};
    CHECK(MFCreatePresentationDescriptor(1, sds, &source->pd), "MFCreatePresentationDescriptor");
    source->pd->SelectStream(0);
    stream->source = source.Get();
    stream->duration = 10000000LL * ivf.scale / ivf.rate;
    source->stream = stream;

    ComPtr<ID3D11Device> dev;
    ComPtr<IMFDXGIDeviceManager> mgr;
    if (d3d)
    {
        ComPtr<ID3D11DeviceContext> ctx;
        CHECK(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, D3D11_CREATE_DEVICE_VIDEO_SUPPORT | D3D11_CREATE_DEVICE_BGRA_SUPPORT, nullptr, 0, D3D11_SDK_VERSION, &dev, nullptr, &ctx), "D3D11CreateDevice");
        ComPtr<ID3D10Multithread> mt;
        dev.As(&mt);
        mt->SetMultithreadProtected(TRUE);
        UINT token = 0;
        CHECK(MFCreateDXGIDeviceManager(&token, &mgr), "MFCreateDXGIDeviceManager");
        CHECK(mgr->ResetDevice(dev.Get(), token), "ResetDevice");
    }

    ComPtr<Callback> cb = Microsoft::WRL::Make<Callback>();
    ComPtr<IMFAttributes> attrs;
    MFCreateAttributes(&attrs, 8);
    if (lowLatency) attrs->SetUINT32(MF_LOW_LATENCY, TRUE);
    attrs->SetUINT32(MF_READWRITE_ENABLE_HARDWARE_TRANSFORMS, g_noHw ? FALSE : TRUE);
    attrs->SetUINT32(MF_SOURCE_READER_ENABLE_ADVANCED_VIDEO_PROCESSING, TRUE);
    attrs->SetUINT32(WSA_PRIVATE_043ff126, TRUE);
    attrs->SetUnknown(MF_SOURCE_READER_ASYNC_CALLBACK, cb.Get());
    attrs->SetUINT32(MF_XVP_DISABLE_FRC, TRUE);
    if (mgr) attrs->SetUnknown(MF_SOURCE_READER_D3D_MANAGER, mgr.Get());
    ComPtr<IMFSourceReader> reader;
    CHECK(MFCreateSourceReaderFromMediaSource(source.Get(), attrs.Get(), &reader), "MFCreateSourceReaderFromMediaSource");
    cb->reader = reader.Get();

    ComPtr<IMFMediaType> out;
    MFCreateMediaType(&out);
    out->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    out->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12);
    CHECK(reader->SetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, nullptr, out.Get()), "SetCurrentMediaType NV12");
    ComPtr<IMFSourceReaderEx> ex;
    if (SUCCEEDED(reader.As(&ex)))
    {
        ComPtr<IMFTransform> mft;
        GUID cat;
        for (DWORD i = 0; SUCCEEDED(ex->GetTransformForStream(MF_SOURCE_READER_FIRST_VIDEO_STREAM, i, &cat, &mft)); i++, mft.Reset())
            std::printf("transform %lu: %s\n", i, cat == MFT_CATEGORY_VIDEO_DECODER ? "video decoder" : cat == MFT_CATEGORY_VIDEO_PROCESSOR ? "video processor" : "other");
    }
    std::printf("%s: %ux%u, %zu packets; d3d11=%d lowlatency=%d live=%d\n", argv[1], ivf.w, ivf.h, ivf.frames.size(), d3d, lowLatency, g_live);
    CHECK(reader->ReadSample(MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0, nullptr, nullptr, nullptr, nullptr), "ReadSample");
    if (WaitForSingleObject(g_done, 30000) != WAIT_OBJECT_0) std::printf("TIMEOUT after 30 s\n");
    if (FAILED(g_error)) std::printf("ERROR 0x%08lx in OnReadSample\n", g_error);
    int atEos = g_framesAtEos.load();
    std::printf("first frame after %d requested packets; %d frames in all, %d of them after end of stream; %d requests\n", g_firstAfter.load(), g_frames.load(), atEos < 0 ? 0 : g_frames.load() - atEos, g_requests.load());
    reader.Reset();
    source->Shutdown();
    MFShutdown();
    return 0;
}
