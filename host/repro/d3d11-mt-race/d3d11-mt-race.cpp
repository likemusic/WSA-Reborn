// d3d11-mt-race: a standalone reproduction of the race that crashed the own host's renderer in amdxx64.dll and once
// brought Windows down with bugcheck 0x119 VIDEO_SCHEDULER_INTERNAL_ERROR (Arg1 = 1, the driver reported an invalid
// fence ID; docs/own-host.md, "Hardware video decoding", 29.10.2026). No WSA, no ANGLE, no Media Foundation: just the D3D11 calls the two
// threads made.
//
// Device A is the renderer's (ANGLE's in the original), created WITHOUT ID3D10Multithread protection. Device B is the
// decoder's: it writes a shared keyed-mutex texture. Two threads use device A at once, as the original did:
//   "media"  — AcquireSync / CopyResource from the shared texture / ReleaseSync (DXGIReleaseSync -> Flush in the dump);
//   "render" — draws (ClearRenderTargetView, CopyResource), reads back through a staging texture (Map READ), Flush.
// A third thread on device B keeps the shared texture changing. This is a violation of the D3D11 threading contract
// without --protect, so a user-mode crash is within the rules; a kernel bugcheck is not.
//
//   d3d11-mt-race.exe [seconds=60] [--protect]     --protect: SetMultithreadProtected(TRUE) on device A (the control)
//
// Prints the iteration counts every second; on a crash writes d3d11-mt-race-<pid>.dmp next to the exe. 29.10.2026.
#include <windows.h>
#include <d3d11.h>
#include <d3d10.h>
#include <dxgi1_2.h>
#include <dbghelp.h>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <thread>

#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "dxgi.lib")
#pragma comment(lib, "dbghelp.lib")

static const UINT W = 1280, H = 720;
static std::atomic<bool> g_stop{false};
static std::atomic<unsigned long long> g_media{0}, g_render{0}, g_writer{0};

static LONG WINAPI Crash(EXCEPTION_POINTERS* e)
{
    wchar_t path[MAX_PATH];
    GetModuleFileNameW(nullptr, path, MAX_PATH);
    swprintf(wcsrchr(path, L'\\') + 1, 64, L"d3d11-mt-race-%lu.dmp", GetCurrentProcessId());
    HANDLE f = CreateFileW(path, GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, 0, nullptr);
    if (f != INVALID_HANDLE_VALUE)
    {
        MINIDUMP_EXCEPTION_INFORMATION mei = {GetCurrentThreadId(), e, FALSE};
        MiniDumpWriteDump(GetCurrentProcess(), GetCurrentProcessId(), f,
                          (MINIDUMP_TYPE)(MiniDumpWithDataSegs | MiniDumpWithThreadInfo | MiniDumpWithIndirectlyReferencedMemory), &mei, nullptr, nullptr);
        CloseHandle(f);
    }
    std::printf("CRASH 0x%08lx at %p, minidump %ls\n", e->ExceptionRecord->ExceptionCode, e->ExceptionRecord->ExceptionAddress, path);
    std::fflush(stdout);
    return EXCEPTION_CONTINUE_SEARCH;
}

static ID3D11Device* MakeDevice(ID3D11DeviceContext** ctx)
{
    ID3D11Device* dev = nullptr;
    HRESULT hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, D3D11_CREATE_DEVICE_VIDEO_SUPPORT | D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                                   nullptr, 0, D3D11_SDK_VERSION, &dev, nullptr, ctx);
    if (FAILED(hr)) { std::printf("D3D11CreateDevice: 0x%08lx\n", hr); std::exit(1); }
    return dev;
}

static D3D11_TEXTURE2D_DESC Desc(UINT bind, UINT misc, D3D11_USAGE usage = D3D11_USAGE_DEFAULT, UINT cpu = 0)
{
    D3D11_TEXTURE2D_DESC d = {};
    d.Width = W; d.Height = H; d.MipLevels = 1; d.ArraySize = 1; d.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    d.SampleDesc.Count = 1; d.Usage = usage; d.BindFlags = bind; d.MiscFlags = misc; d.CPUAccessFlags = cpu;
    return d;
}

int main(int argc, char** argv)
{
    SetUnhandledExceptionFilter(Crash);
    int seconds = 60;
    bool protect = false;
    for (int i = 1; i < argc; i++)
    {
        if (!std::strcmp(argv[i], "--protect")) protect = true;
        else seconds = std::atoi(argv[i]);
    }

    ID3D11DeviceContext *ctxA = nullptr, *ctxB = nullptr;
    ID3D11Device* devA = MakeDevice(&ctxA);
    ID3D11Device* devB = MakeDevice(&ctxB);
    if (protect)
    {
        ID3D10Multithread* mt = nullptr;
        devA->QueryInterface(__uuidof(ID3D10Multithread), (void**)&mt);
        mt->SetMultithreadProtected(TRUE);
        mt->Release();
    }
    std::printf("device A %s, %d s\n", protect ? "multithread protected" : "NOT multithread protected", seconds);

    // The shared texture: created on B (the decoder's), opened on A (the renderer's).
    ID3D11Texture2D* sharedB = nullptr;
    D3D11_TEXTURE2D_DESC sd = Desc(D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE, D3D11_RESOURCE_MISC_SHARED_KEYEDMUTEX);
    if (FAILED(devB->CreateTexture2D(&sd, nullptr, &sharedB))) { std::printf("shared texture failed\n"); return 1; }
    IDXGIResource* res = nullptr;
    HANDLE handle = nullptr;
    sharedB->QueryInterface(__uuidof(IDXGIResource), (void**)&res);
    res->GetSharedHandle(&handle);
    res->Release();
    ID3D11Texture2D* sharedA = nullptr;
    if (FAILED(devA->OpenSharedResource(handle, __uuidof(ID3D11Texture2D), (void**)&sharedA))) { std::printf("OpenSharedResource failed\n"); return 1; }
    IDXGIKeyedMutex *kmA = nullptr, *kmB = nullptr;
    sharedA->QueryInterface(__uuidof(IDXGIKeyedMutex), (void**)&kmA);
    sharedB->QueryInterface(__uuidof(IDXGIKeyedMutex), (void**)&kmB);
    ID3D11RenderTargetView* rtvB = nullptr;
    devB->CreateRenderTargetView(sharedB, nullptr, &rtvB);

    // Device A's own resources: a "color buffer" the media thread copies into, a render target, a staging texture.
    ID3D11Texture2D *colorA = nullptr, *targetA = nullptr, *stagingA = nullptr;
    D3D11_TEXTURE2D_DESC cd = Desc(D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE, 0);
    devA->CreateTexture2D(&cd, nullptr, &colorA);
    devA->CreateTexture2D(&cd, nullptr, &targetA);
    D3D11_TEXTURE2D_DESC st = Desc(0, 0, D3D11_USAGE_STAGING, D3D11_CPU_ACCESS_READ);
    devA->CreateTexture2D(&st, nullptr, &stagingA);
    ID3D11RenderTargetView* rtvA = nullptr;
    devA->CreateRenderTargetView(targetA, nullptr, &rtvA);

    // Device B: the decoder writing frames (key 0 -> 1).
    std::thread writer([&] {
        float c[4] = {0, 0, 0, 1};
        while (!g_stop)
        {
            if (kmB->AcquireSync(0, 100) != S_OK) continue;
            c[0] = (g_writer % 255) / 255.0f;
            ctxB->ClearRenderTargetView(rtvB, c);
            kmB->ReleaseSync(1);
            ctxB->Flush();
            g_writer++;
        }
    });
    // Device A, thread 1: the media channel taking the frame into its color buffer (key 1 -> 0).
    std::thread media([&] {
        while (!g_stop)
        {
            if (kmA->AcquireSync(1, 100) != S_OK) continue;
            ctxA->CopyResource(colorA, sharedA);
            kmA->ReleaseSync(0);
            g_media++;
        }
    });
    // Device A, thread 2: the renderer composing and reading frames back (glReadPixels -> Map in the dump).
    std::thread render([&] {
        float c[4] = {0.2f, 0.4f, 0.6f, 1};
        while (!g_stop)
        {
            ctxA->ClearRenderTargetView(rtvA, c);
            ctxA->CopyResource(targetA, colorA);
            ctxA->CopyResource(stagingA, targetA);
            D3D11_MAPPED_SUBRESOURCE m;
            if (SUCCEEDED(ctxA->Map(stagingA, 0, D3D11_MAP_READ, 0, &m))) ctxA->Unmap(stagingA, 0);
            ctxA->Flush();
            g_render++;
        }
    });

    for (int s = 0; s < seconds; s++)
    {
        Sleep(1000);
        HRESULT removed = devA->GetDeviceRemovedReason();
        std::printf("%3d s: writer %llu, media %llu, render %llu", s + 1, g_writer.load(), g_media.load(), g_render.load());
        if (removed != S_OK) std::printf(" — device A removed: 0x%08lx", removed);
        std::printf("\n");
        std::fflush(stdout);
        if (removed != S_OK) break;
    }
    g_stop = true;
    writer.join();
    media.join();
    render.join();
    std::printf("done without a crash\n");
    return 0;
}
