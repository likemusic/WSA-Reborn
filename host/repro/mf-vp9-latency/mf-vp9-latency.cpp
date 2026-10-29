// mf-vp9-latency: how many VP9 packets the Windows VP9 decoder MFT takes before its first frame, with and without
// CODECAPI_AVLowLatencyMode and a D3D11 device manager, and whether a drain returns the frames it still holds.
// On the own host the Latte VP9 decoder (WSACodecs.dll -> Media Foundation, docs/own-host.md) gave its first frame
// only after the 131st packet and lost the last 128 frames at EOS, while H.264/HEVC/VP8/AV1 answer after 5-7 packets.
// This program feeds an IVF file (ffmpeg -c:v libvpx-vp9 x.ivf) straight into the MFT, no WSA.
//
//   mf-vp9-latency.exe <file.ivf> [--subtype vp80|vp90|av01|mp4v] [--wsa-type] [--no-times]
//     --wsa-type: the input type exactly as WSACodecs.dll sets it; --no-times: samples without time stamps
//
// For each of four runs (low latency off/on x system memory/D3D11) prints the MFT, the packet after which the first
// frame came out, the frames output before the drain and after it. 29.10.2026.
#define _CRT_SECURE_NO_WARNINGS
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mftransform.h>
#include <mferror.h>
#include <codecapi.h>
#include <strmif.h>
#include <d3d11.h>
#include <d3d10.h>
#include <cstdio>
#include <cstring>
#include <vector>

#pragma comment(lib, "mfplat.lib")
#pragma comment(lib, "mfuuid.lib")
#pragma comment(lib, "mf.lib")
#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "oleaut32.lib")
#pragma comment(lib, "strmiids.lib")

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

struct Result
{
    wchar_t name[128] = L"?";
    HRESULT error = S_OK;
    const char* step = "";
    int firstAfter = -1, beforeDrain = 0, afterDrain = 0, notAccepting = 0;
    bool lowLatencySet = false, d3dSet = false;
};

#define CHECK(x, s) do { HRESULT _hr = (x); if (FAILED(_hr)) { r.error = _hr; r.step = s; goto done; } } while (0)

// Pulls every frame the MFT has now; returns how many, or -1 on an error other than "needs more input".
static int Drain(IMFTransform* mft, bool providesSamples, UINT32 cbSize, Result& r)
{
    int n = 0;
    for (;;)
    {
        MFT_OUTPUT_DATA_BUFFER out = {};
        IMFSample* own = nullptr;
        if (!providesSamples)
        {
            IMFMediaBuffer* buf = nullptr;
            MFCreateMemoryBuffer(cbSize, &buf);
            MFCreateSample(&own);
            own->AddBuffer(buf);
            buf->Release();
            out.pSample = own;
        }
        DWORD status = 0;
        HRESULT hr = mft->ProcessOutput(0, 1, &out, &status);
        if (out.pEvents) out.pEvents->Release();
        if (out.pSample && out.pSample != own) out.pSample->Release();
        if (own) own->Release();
        if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT) return n;
        if (hr == MF_E_TRANSFORM_STREAM_CHANGE)
        {
            // The new output type: take the first one again (NV12 for these decoders).
            IMFMediaType* t = nullptr;
            for (DWORD i = 0; SUCCEEDED(mft->GetOutputAvailableType(0, i, &t)); i++)
            {
                GUID sub;
                t->GetGUID(MF_MT_SUBTYPE, &sub);
                if (sub == MFVideoFormat_NV12) { mft->SetOutputType(0, t, 0); t->Release(); break; }
                t->Release();
            }
            continue;
        }
        if (FAILED(hr)) { r.error = hr; r.step = "ProcessOutput"; return -1; }
        n++;
    }
}

static bool g_wsaType = false, g_noTimes = false;

static Result Run(const Ivf& ivf, const GUID& subtype, bool lowLatency, bool d3d)
{
    Result r;
    IMFActivate** acts = nullptr;
    UINT32 count = 0;
    IMFTransform* mft = nullptr;
    IMFMediaType *in = nullptr, *outType = nullptr;
    ID3D11Device* dev = nullptr;
    IMFDXGIDeviceManager* mgr = nullptr;
    MFT_REGISTER_TYPE_INFO info = {MFMediaType_Video, subtype};
    MFT_OUTPUT_STREAM_INFO osi = {};
    bool provides = false;
    LONGLONG duration = 10000000LL * ivf.scale / ivf.rate;

    CHECK(MFTEnumEx(MFT_CATEGORY_VIDEO_DECODER, MFT_ENUM_FLAG_SYNCMFT | MFT_ENUM_FLAG_LOCALMFT | MFT_ENUM_FLAG_SORTANDFILTER, &info, nullptr, &acts, &count), "MFTEnumEx");
    if (!count) { r.error = E_FAIL; r.step = "no decoder"; goto done; }
    {
        wchar_t* name = nullptr;
        UINT32 len = 0;
        if (SUCCEEDED(acts[0]->GetAllocatedString(MFT_FRIENDLY_NAME_Attribute, &name, &len))) { wcsncpy_s(r.name, name, _TRUNCATE); CoTaskMemFree(name); }
    }
    CHECK(acts[0]->ActivateObject(IID_PPV_ARGS(&mft)), "ActivateObject");

    if (lowLatency)
    {
        ICodecAPI* api = nullptr;
        if (SUCCEEDED(mft->QueryInterface(IID_PPV_ARGS(&api))))
        {
            VARIANT v;
            VariantInit(&v);
            v.vt = VT_UI4;
            v.ulVal = 1;
            r.lowLatencySet = SUCCEEDED(api->SetValue(&CODECAPI_AVLowLatencyMode, &v));
            if (!r.lowLatencySet)
            {
                v.vt = VT_BOOL;
                v.boolVal = VARIANT_TRUE;
                r.lowLatencySet = SUCCEEDED(api->SetValue(&CODECAPI_AVLowLatencyMode, &v));
            }
            api->Release();
        }
        IMFAttributes* a = nullptr;
        if (SUCCEEDED(mft->GetAttributes(&a)))
        {
            if (SUCCEEDED(a->SetUINT32(MF_LOW_LATENCY, TRUE))) r.lowLatencySet = true;
            a->Release();
        }
    }
    if (d3d)
    {
        ID3D11DeviceContext* ctx = nullptr;
        CHECK(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, D3D11_CREATE_DEVICE_VIDEO_SUPPORT, nullptr, 0, D3D11_SDK_VERSION, &dev, nullptr, &ctx), "D3D11CreateDevice");
        ctx->Release();
        ID3D10Multithread* mt = nullptr;
        dev->QueryInterface(IID_PPV_ARGS(&mt));
        mt->SetMultithreadProtected(TRUE);
        mt->Release();
        UINT token = 0;
        CHECK(MFCreateDXGIDeviceManager(&token, &mgr), "MFCreateDXGIDeviceManager");
        CHECK(mgr->ResetDevice(dev, token), "ResetDevice");
        r.d3dSet = SUCCEEDED(mft->ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER, (ULONG_PTR)mgr));
    }

    CHECK(MFCreateMediaType(&in), "MFCreateMediaType");
    in->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    in->SetGUID(MF_MT_SUBTYPE, subtype);
    if (g_wsaType)
    {
        // The type WSACodecs.dll builds for every codec (VP9CodecHandler::CreateMediaType, rva 0x21560): compressed,
        // not all samples independent, 1:1 pixels, MF_MT_FRAME_SIZE from one field for both halves, no frame rate.
        in->SetUINT32(MF_MT_COMPRESSED, 1);
        in->SetUINT32(MF_MT_ALL_SAMPLES_INDEPENDENT, 0);
        MFSetAttributeRatio(in, MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
        MFSetAttributeSize(in, MF_MT_FRAME_SIZE, ivf.w, ivf.w);
    }
    else
    {
        MFSetAttributeSize(in, MF_MT_FRAME_SIZE, ivf.w, ivf.h);
        MFSetAttributeRatio(in, MF_MT_FRAME_RATE, ivf.rate, ivf.scale);
    }
    CHECK(mft->SetInputType(0, in, 0), "SetInputType");
    for (DWORD i = 0;; i++)
    {
        CHECK(mft->GetOutputAvailableType(0, i, &outType), "no NV12 output");
        GUID sub;
        outType->GetGUID(MF_MT_SUBTYPE, &sub);
        if (sub == MFVideoFormat_NV12) break;
        outType->Release();
        outType = nullptr;
    }
    CHECK(mft->SetOutputType(0, outType, 0), "SetOutputType");
    CHECK(mft->GetOutputStreamInfo(0, &osi), "GetOutputStreamInfo");
    provides = (osi.dwFlags & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES)) != 0;
    mft->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
    mft->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);

    for (size_t i = 0; i < ivf.frames.size(); i++)
    {
        IMFMediaBuffer* buf = nullptr;
        IMFSample* s = nullptr;
        BYTE* p = nullptr;
        MFCreateMemoryBuffer((DWORD)ivf.frames[i].size(), &buf);
        buf->Lock(&p, nullptr, nullptr);
        std::memcpy(p, ivf.frames[i].data(), ivf.frames[i].size());
        buf->Unlock();
        buf->SetCurrentLength((DWORD)ivf.frames[i].size());
        MFCreateSample(&s);
        s->AddBuffer(buf);
        buf->Release();
        if (!g_noTimes)
        {
            s->SetSampleTime((LONGLONG)i * duration);
            s->SetSampleDuration(duration);
        }
        HRESULT hr;
        while ((hr = mft->ProcessInput(0, s, 0)) == MF_E_NOTACCEPTING)
        {
            r.notAccepting++;
            int n = Drain(mft, provides, osi.cbSize ? osi.cbSize : ivf.w * ivf.h * 3 / 2, r);
            if (n < 0) { s->Release(); goto done; }
            if (n && r.firstAfter < 0) r.firstAfter = (int)i;
            r.beforeDrain += n;
            if (!n) break;
        }
        s->Release();
        CHECK(hr, "ProcessInput");
        int n = Drain(mft, provides, osi.cbSize ? osi.cbSize : ivf.w * ivf.h * 3 / 2, r);
        if (n < 0) goto done;
        if (n && r.firstAfter < 0) r.firstAfter = (int)i + 1;
        r.beforeDrain += n;
    }
    mft->ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
    CHECK(mft->ProcessMessage(MFT_MESSAGE_COMMAND_DRAIN, 0), "COMMAND_DRAIN");
    r.afterDrain = Drain(mft, provides, osi.cbSize ? osi.cbSize : ivf.w * ivf.h * 3 / 2, r);

done:
    if (outType) outType->Release();
    if (in) in->Release();
    if (mft) mft->Release();
    if (mgr) mgr->Release();
    if (dev) dev->Release();
    for (UINT32 i = 0; i < count; i++) acts[i]->Release();
    CoTaskMemFree(acts);
    return r;
}

int main(int argc, char** argv)
{
    if (argc < 2) { std::printf("usage: mf-vp9-latency <file.ivf> [--subtype vp80|vp90|av01|mp4v]\n"); return 2; }
    GUID subtype = MFVideoFormat_VP90;
    for (int i = 2; i < argc; i++)
        if (!std::strcmp(argv[i], "--wsa-type")) g_wsaType = true;
        else if (!std::strcmp(argv[i], "--no-times")) g_noTimes = true;
        else if (!std::strcmp(argv[i], "--subtype") && i + 1 < argc)
        {
            const char* s = argv[++i];
            subtype = !std::strcmp(s, "vp80") ? MFVideoFormat_VP80 : !std::strcmp(s, "av01") ? MFVideoFormat_AV1 : !std::strcmp(s, "mp4v") ? MFVideoFormat_MP4V : MFVideoFormat_VP90;
        }
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    MFStartup(MF_VERSION);
    {
        // Every decoder for the type, hardware and async ones too (what a Source Reader with a D3D manager may pick).
        MFT_REGISTER_TYPE_INFO info = {MFMediaType_Video, subtype};
        IMFActivate** acts = nullptr;
        UINT32 count = 0;
        MFTEnumEx(MFT_CATEGORY_VIDEO_DECODER, MFT_ENUM_FLAG_ALL | MFT_ENUM_FLAG_SORTANDFILTER, &info, nullptr, &acts, &count);
        for (UINT32 i = 0; i < count; i++)
        {
            wchar_t* name = nullptr;
            UINT32 len = 0, flags = 0;
            acts[i]->GetAllocatedString(MFT_FRIENDLY_NAME_Attribute, &name, &len);
            acts[i]->GetUINT32(MF_TRANSFORM_FLAGS_Attribute, &flags);
            std::printf("decoder %u: %ls, flags 0x%x%s%s\n", i, name ? name : L"?", flags, flags & MFT_ENUM_FLAG_HARDWARE ? " hardware" : "", flags & MFT_ENUM_FLAG_ASYNCMFT ? " async" : "");
            CoTaskMemFree(name);
            acts[i]->Release();
        }
        CoTaskMemFree(acts);
    }
    Ivf ivf;
    if (!ReadIvf(argv[1], ivf)) { std::printf("not an IVF file: %s\n", argv[1]); return 2; }
    std::printf("%s: %ux%u, %u/%u fps, %zu packets\n", argv[1], ivf.w, ivf.h, ivf.rate, ivf.scale, ivf.frames.size());
    for (int run = 0; run < 4; run++)
    {
        bool low = run & 1, d3d = run >> 1;
        Result r = Run(ivf, subtype, low, d3d);
        std::printf("lowlatency=%d%s d3d11=%d%s  %ls:", low, low && !r.lowLatencySet ? "(refused)" : "", d3d, d3d && !r.d3dSet ? "(refused)" : "", r.name);
        if (FAILED(r.error)) std::printf("  ERROR 0x%08lx at %s;", r.error, r.step);
        std::printf("  first frame after packet %d, %d frames before drain, %d after drain, %d NOTACCEPTING\n", r.firstAfter, r.beforeDrain, r.afterDrain, r.notAccepting);
        std::fflush(stdout);
    }
    MFShutdown();
    CoUninitialize();
    return 0;
}
