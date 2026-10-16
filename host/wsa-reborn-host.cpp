// wsa-reborn-host — starts a WSA-compatible VM through HCS without WsaService/WsaClient and relays
// the guest's adbd (hvsocket service 5555) to 127.0.0.1:<port>.
//
//   wsa-reborn-host.exe <config.json> [adb-port=58600] [console-log=wsa-reborn-host-console.log]
//
// config.json is an HCS compute system document (make-config.sh writes it). Every "KernelFilePath", "InitRdPath" and
// "Path" value in it is granted to the VM worker (HcsGrantVmAccess) before the VM is created. ComPorts "0" must be the
// named pipe \\.\pipe\wsa-reborn-host-com1 (earlycon) and VirtioSerial port "0" (hvc0, WSA's console=) —
// \\.\pipe\wsa-reborn-host-hvc0; the program serves both and copies them to <console-log> and <console-log>.com1.
// HCS opens the pipes for reading AND writing: an inbound-only pipe is never connected.
// virtiofs shares (WSA's shared_mem/shared_tmp/shared_anr/shared_tombstone) are listed in shares.txt next to the exe,
// see AddShares() below.
// Needs an administrator (HCS answers 0x8037011B otherwise). Ctrl+C terminates the VM. 12.10.2026.
//
// Split mode, as WsaService/WsaClient (15.10.2026): with the line "client separate" in shares.txt the elevated process
// only runs the VM (HCS, disks, consoles, virtiofs, NIC, guest init, adb relay), writes runtime.txt "<runtime id> <pid>"
// and waits up to two minutes for client.ready before starting the VM. Everything of the user's desktop — RCM channels,
// windows, input, toasts, clipboard, files, Start menu shortcuts, the command port — is served by
//
//   wsa-reborn-host.exe --client [adb-port=58600]
//
// started in the same folder WITHOUT elevation: it waits for runtime.txt, listens on the guest's hvsockets (allowed by
// the HvSocket security descriptors of config.json, which name the user's SID), writes client.ready and exits when the
// service process does.
//
// The elevated half runs either from the agent (agent.ps1) or as a Windows service (LocalSystem):
//
//   wsa-reborn-host.exe --install [<folder>]  (administrator; again after editing config.json or shares.txt) copies
//                                            the exe, config.json and shares.txt of <folder> (default: the exe's
//                                            folder) to %ProgramFiles%\WsaReborn and registers the service
//                                            WsaRebornHost for <folder>, startable and stoppable by the installing
//                                            user without elevation; the service writes out.txt, the consoles and
//                                            runtime.txt to %ProgramData%\WsaReborn (see ConfigFile)
//   wsa-reborn-host.exe --uninstall           (administrator) stops and removes it
//   wsa-reborn-host.exe --stop                stops the service (and the VM) without elevation
//
// The service always runs split: a --client that finds no running VM starts the service itself, as WsaClient asks
// WsaService. The service runs in session 0, so the client hands it its session and SID in client.ready: the
// section-backed share lives in the client's \Sessions\<n>\BaseNamedObjects, as WslCoreVm does for the user.
//
// The VM's life follows the resources mode of settings.txt, as WSA's settings (ReadSettings, IdleWatch; 15.10.2026):
//
//   wsa-reborn-host.exe --launch <package>    starts the app; with the VM off it starts the client (and so the VM) first
//   wsa-reborn-host.exe --lifecycle <mode>    continuous|asneeded|partial: settings.txt and the start at sign-in
//   wsa-reborn-host.exe --config <install-dir> <data-dir> [mem-MB] [cpus]   config.json without WSL (= make-config.sh)
//   wsa-reborn-host.exe --networks            (administrator) HCN networks for the "nic" line of shares.txt
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <windowsx.h>
#include <hvsocket.h>
#include <computecore.h>
#include <computenetwork.h>
#include <objbase.h>
#include <shellapi.h>
#include <shlobj.h>
#include <shlwapi.h>
#include <winternl.h>
#include <gdiplus.h>
#include <sddl.h>
#include <aclapi.h>
#include <wtsapi32.h>
#include <io.h>
#include <fcntl.h>
#include <set>
#undef GetCurrentTime
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Data.Xml.Dom.h>
#include <winrt/Windows.UI.Notifications.h>
#include <algorithm>
#include <atomic>
#include <deque>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <regex>
#include <sstream>
#include <string>
#include <mutex>
#include <thread>
#include <map>
#include <vector>

#pragma comment(lib, "computecore.lib")
#pragma comment(lib, "computenetwork.lib")
#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "shlwapi.lib")
#pragma comment(lib, "user32.lib")
#pragma comment(lib, "gdi32.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "uuid.lib")
#pragma comment(lib, "gdiplus.lib")
#pragma comment(lib, "windowsapp.lib")
#pragma comment(lib, "advapi32.lib")
#pragma comment(lib, "wtsapi32.lib")
#pragma comment(lib, "winmm.lib")

// The compute system id is a GUID, as WSL's machine id: the virtio device host (IPlan9FileSystem::CreateVirtioDevice)
// answers E_INVALIDARG to any other name (12.10.2026).
static const wchar_t* kVmId = L"5f3c1a7e-2b4d-4e8a-9c61-0d7b2e4f9a13";
static const wchar_t* kComPipe = L"\\\\.\\pipe\\wsa-reborn-host-com1";
static const wchar_t* kHvcPipe = L"\\\\.\\pipe\\wsa-reborn-host-hvc0";
// adbd in the WSA guest listens on vsock 5555; on Hyper-V that is the service GUID <port>-facb-11e6-bd58-64006a7986d3.
static const GUID kAdbService = {0x000015b3, 0xfacb, 0x11e6, {0xbd, 0x58, 0x64, 0x00, 0x6a, 0x79, 0x86, 0xd3}};
static HCS_SYSTEM g_system = nullptr;
static GUID g_runtimeId{};
// Address for the guest's eth0 (shares.txt "nic" line), sent on the Settings channel.
static std::string g_nicIp, g_nicGateway;

static std::wstring Widen(const std::string& s)
{
    std::wstring w(MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), nullptr, 0), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.data(), (int)s.size(), w.data(), (int)w.size());
    return w;
}

static std::string Narrow(const wchar_t* w)
{
    if (!w) return {};
    std::string s(WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w, -1, s.data(), (int)s.size(), nullptr, nullptr);
    s.pop_back();
    return s;
}

// Under the installed service the LocalSystem process takes what it trusts — config.json and shares.txt — from the
// exe's folder (%ProgramFiles%\WsaReborn, copied there by --install, writable by administrators only), writes its own
// files to %ProgramData%\WsaReborn (its current folder, closed to users' writes) and only reads from the user's host
// folder what the client hands over: client.ready, devhost-<tag>.objref, settings.txt. Until 15.10.2026 it read
// everything from the user's %TEMP% — a user could have named any file in config.json and the service would have
// granted the VM access to it. Empty: the current folder (agent, client, console runs).
static std::wstring g_configDir, g_userDir;
static std::wstring ConfigFile(const wchar_t* name) { return g_configDir.empty() ? name : g_configDir + L"\\" + name; }
static std::wstring UserFile(const std::wstring& name) { return g_userDir.empty() ? name : g_userDir + L"\\" + name; }

// Runs one HCS operation to completion; prints and returns the HRESULT, result document goes to *doc.
static HRESULT Wait(HCS_OPERATION op, const char* what, std::string* doc = nullptr)
{
    PWSTR result = nullptr;
    HRESULT hr = HcsWaitForOperationResult(op, INFINITE, &result);
    std::printf("%s: 0x%08lx%s%s\n", what, hr, result && *result ? " " : "", result && !doc ? Narrow(result).c_str() : "");
    if (doc) *doc = Narrow(result);
    if (result) LocalFree(result);
    HcsCloseOperation(op);
    return hr;
}

static void Pump(SOCKET from, SOCKET to)
{
    char buf[65536];
    for (int n; (n = recv(from, buf, sizeof buf, 0)) > 0;)
        if (send(to, buf, n, 0) != n) break;
    shutdown(to, SD_SEND);
}

static void Relay(GUID runtimeId, unsigned short port)
{
    SOCKET l = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_port = htons(port);
    inet_pton(AF_INET, "127.0.0.1", &a.sin_addr);
    if (bind(l, (sockaddr*)&a, sizeof a) || listen(l, 8)) { std::printf("relay: cannot listen on %u (%d)\n", port, WSAGetLastError()); return; }
    std::printf("relay: adb connect 127.0.0.1:%u\n", port);
    for (SOCKET c; (c = accept(l, nullptr, nullptr)) != INVALID_SOCKET;)
    {
        SOCKET h = socket(AF_HYPERV, SOCK_STREAM, HV_PROTOCOL_RAW);
        SOCKADDR_HV hv{};
        hv.Family = AF_HYPERV;
        hv.VmId = runtimeId;
        hv.ServiceId = kAdbService;
        if (connect(h, (sockaddr*)&hv, sizeof hv)) { std::printf("relay: hvsocket connect failed (%d)\n", WSAGetLastError()); closesocket(h); closesocket(c); continue; }
        std::thread([=] { std::thread t(Pump, c, h); Pump(h, c); t.join(); closesocket(h); closesocket(c); }).detach();
    }
}

static void Console(const wchar_t* name, std::string log)
{
    HANDLE pipe = CreateNamedPipeW(name, PIPE_ACCESS_DUPLEX, PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT, 1, 65536, 65536, 0, nullptr);
    if (pipe == INVALID_HANDLE_VALUE) { std::printf("cannot create %ls (%lu)\n", name, GetLastError()); return; }
    ConnectNamedPipe(pipe, nullptr);
    std::printf("console %ls connected\n", name);
    std::ofstream out(log, std::ios::binary);
    char buf[4096];
    for (DWORD n; ReadFile(pipe, buf, sizeof buf, &n, nullptr) && n;)
    {
        out.write(buf, n);
        out.flush();
    }
}

// Guest init (wsainit = WSL's init) connects to hvsocket port 50000 several times (mini_init channel, notify
// channel, ...) and powers the VM off if nobody listens. Every frame received is logged to init.log
// (MESSAGE_HEADER {u32 type; u32 size} framing, size includes the header). What the host says is scripted with
// files next to the exe, so the protocol can be tried without rebuilding:
//   init-send-<N>.bin          — sent right after the N-th connection (from 0) is accepted;
//   init-send-<N>-after<K>.bin — sent after the K-th frame (from 1) is received on that connection.
static std::mutex g_logLock;
static std::ofstream g_initLog;

static void InitLog(const std::string& s)
{
    std::lock_guard<std::mutex> l(g_logLock);
    g_initLog << GetTickCount64() << " " << s << "\n";
    g_initLog.flush();
}

static bool SendFile(SOCKET s, const std::string& name, int conn)
{
    std::ifstream f(name, std::ios::binary);
    if (!f) return false;
    std::string b((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    int n = send(s, b.data(), (int)b.size(), 0);
    InitLog("conn " + std::to_string(conn) + " SENT " + name + " " + std::to_string(b.size()) + " bytes, send=" + std::to_string(n));
    return true;
}

static void InitConn(SOCKET s, int conn)
{
    SendFile(s, "init-send-" + std::to_string(conn) + ".bin", conn);
    std::string buf;
    char tmp[65536];
    int frames = 0;
    for (int n; (n = recv(s, tmp, sizeof tmp, 0)) > 0;)
    {
        buf.append(tmp, n);
        while (buf.size() >= 8)
        {
            unsigned type = *(unsigned*)buf.data(), size = *(unsigned*)(buf.data() + 4);
            if (size < 8 || size > 16 * 1024 * 1024) { InitLog("conn " + std::to_string(conn) + " bad frame size " + std::to_string(size)); size = (unsigned)buf.size(); }
            if (buf.size() < size) break;
            std::string hex;
            char h[4];
            for (unsigned i = 0; i < size && i < 512; i++) { std::snprintf(h, sizeof h, "%02x", (unsigned char)buf[i]); hex += h; }
            std::string text;
            for (unsigned i = 8; i < size && i < 512; i++) text += (buf[i] >= 32 && buf[i] < 127) ? buf[i] : '.';
            InitLog("conn " + std::to_string(conn) + " RECV #" + std::to_string(++frames) + " type=" + std::to_string(type) + " size=" + std::to_string(size) + " " + hex + " |" + text + "|");
            buf.erase(0, size);
            SendFile(s, "init-send-" + std::to_string(conn) + "-after" + std::to_string(frames) + ".bin", conn);
        }
    }
    InitLog("conn " + std::to_string(conn) + " closed (" + std::to_string(WSAGetLastError()) + ")");
}

static SOCKET InitListen(GUID runtimeId)
{
    SOCKET l = socket(AF_HYPERV, SOCK_STREAM, HV_PROTOCOL_RAW);
    SOCKADDR_HV hv{};
    hv.Family = AF_HYPERV;
    hv.VmId = runtimeId;
    hv.ServiceId = {50000, 0xfacb, 0x11e6, {0xbd, 0x58, 0x64, 0x00, 0x6a, 0x79, 0x86, 0xd3}};
    if (bind(l, (sockaddr*)&hv, sizeof hv) || listen(l, 16)) { std::printf("init: cannot listen on hvsocket 50000 (%d)\n", WSAGetLastError()); return INVALID_SOCKET; }
    g_initLog.open("init.log", std::ios::binary);
    std::thread([l] {
        int conn = 0;
        for (SOCKET c; (c = accept(l, nullptr, nullptr)) != INVALID_SOCKET; conn++)
        {
            InitLog("conn " + std::to_string(conn) + " accepted");
            std::thread(InitConn, c, conn).detach();
        }
    }).detach();
    return l;
}

// ---- virtiofs -------------------------------------------------------------------------------------------------
// The same scheme as WSL's DeviceHostProxy (microsoft/WSL, MIT, first open-source commit 697572d6,
// src/windows/service/exe/DeviceHostProxy.cpp and WslCoreVm::AddHdvShare): a virtio device is served by a COM server
// (wsldevicehost.dll, a DLL surrogate registered by the WSA package) through IPlan9FileSystem; the host adds it to the VM
// as a FlexibleIov device with the virtio-fs emulator id and answers the server's callbacks (doorbells and
// section-backed MMIO ranges go to the VM worker through vmwpctrl.dll!GetVmWorkerProcess, the device host itself is
// proxied with vmdevicehost.dll!HdvProxyDeviceHost). Interfaces as declared in WSL's windowsdefs.idl; their proxies are
// registered by Windows.
MIDL_INTERFACE("3e57bd3c-5a5d-4bdc-a0a6-5b4193d4b719") IVmVirtualDeviceAccess : IUnknown
{
    virtual HRESULT STDMETHODCALLTYPE _Reserved() = 0;
    virtual HRESULT STDMETHODCALLTYPE GetDevice(REFGUID CategoryID, REFGUID DeviceID, IUnknown** Device) = 0;
};
MIDL_INTERFACE("f5dfbec1-b9f3-4b26-bf6f-c251448bcf7a") IVmFiovGuestMemoryFastNotification : IUnknown
{
    virtual HRESULT STDMETHODCALLTYPE RegisterDoorbell(UINT BarIndex, UINT64 BarOffset, UINT64 TriggerValue, UINT64 Flags, HANDLE Event) = 0;
    virtual HRESULT STDMETHODCALLTYPE UnregisterDoorbell(UINT BarIndex, UINT64 BarOffset, UINT64 TriggerValue, UINT64 Flags) = 0;
};
MIDL_INTERFACE("9d416457-abbc-46cf-8b93-901c68bec627") IVmFiovGuestMmioMappings : IUnknown
{
    virtual HRESULT STDMETHODCALLTYPE CreateSectionBackedMmioRange(UINT BarIndex, ULONG64 BarOffsetInPages, UINT64 PageCount, UINT MappingFlags, HANDLE Section, UINT64 SectionOffsetInPages) = 0;
    virtual HRESULT STDMETHODCALLTYPE DestroySectionBackedMmioRange(UINT BarIndex, ULONG64 BarOffsetInPages) = 0;
};
MIDL_INTERFACE("78523d62-d919-47ca-9cd7-08139172d685") IVmDeviceHost : IUnknown
{
    virtual HRESULT STDMETHODCALLTYPE GetDeviceInstance(REFGUID DeviceClassId, REFGUID DeviceInstanceId, IUnknown** DeviceInstance) = 0;
};
MIDL_INTERFACE("e31aa49b-0914-465e-b145-1b9ba13efb10") IVmDeviceHostSupport : IUnknown
{
    virtual HRESULT STDMETHODCALLTYPE RegisterDeviceHost(IVmDeviceHost* DeviceHost, DWORD ProcessId, UINT64* IpcSectionHandle) = 0;
};
MIDL_INTERFACE("7649D52D-D275-4289-901E-EB626C967ECE") IPlan9FileSystem : IUnknown
{
    virtual HRESULT STDMETHODCALLTYPE AddShare(LPCWSTR name, HANDLE handle, UINT32 flags) = 0;
    virtual HRESULT STDMETHODCALLTYPE RemoveShare(LPCWSTR name) = 0;
    virtual HRESULT STDMETHODCALLTYPE Pause() = 0;
    virtual HRESULT STDMETHODCALLTYPE Resume() = 0;
    virtual HRESULT STDMETHODCALLTYPE Teardown() = 0;
    virtual HRESULT STDMETHODCALLTYPE SetAllowedFiles(LPCWSTR shareName, const LPCWSTR* files, int count) = 0;
    virtual HRESULT STDMETHODCALLTYPE Init(LPCGUID vmId, ULONG port) = 0;
    virtual HRESULT STDMETHODCALLTYPE AddSharePath(LPCWSTR name, LPCWSTR path, UINT32 flags) = 0;
    virtual HRESULT STDMETHODCALLTYPE CreateVirtioDevice(LPCWSTR vmId, IUnknown* deviceHostSupport, LPCWSTR tag, GUID* instanceId) = 0;
    virtual HRESULT STDMETHODCALLTYPE IsRunning() = 0;
};
MIDL_INTERFACE("8434F839-CA86-495D-8A75-7EB36D073FFE") IPlan9FileSystemHost : IUnknown
{
    virtual HRESULT STDMETHODCALLTYPE NotifyAllDevicesInUse(LPCWSTR tag) = 0;
    virtual HRESULT STDMETHODCALLTYPE RegisterDoorbell(REFGUID InstanceId, UINT8 BarIndex, UINT64 Offset, UINT64 TriggerValue, UINT64 Flags, HANDLE Event) = 0;
    virtual HRESULT STDMETHODCALLTYPE UnregisterDoorbell(REFGUID InstanceId, UINT8 BarIndex, UINT64 Offset, UINT64 TriggerValue, UINT64 Flags) = 0;
    virtual HRESULT STDMETHODCALLTYPE CreateSectionBackedMmioRange(REFGUID InstanceId, UINT8 BarIndex, UINT64 BarOffsetInPages, UINT64 PageCount, UINT64 MappingFlags, HANDLE Section, UINT64 SectionOffsetInPages) = 0;
    virtual HRESULT STDMETHODCALLTYPE DestroySectionBackedMmioRange(REFGUID InstanceId, UINT8 BarIndex, UINT64 BarOffsetInPages) = 0;
};

static const GUID kFlexIoCategory = {0xa8679153, 0x843f, 0x467f, {0xad, 0x7e, 0xf4, 0x29, 0x32, 0x8f, 0x75, 0x68}};
static const GUID kVirtioFsEmulator = {0x872270E1, 0xA899, 0x4AF6, {0xB4, 0x54, 0x71, 0x93, 0x63, 0x44, 0x35, 0xAD}};
// WslCoreVm: for virtio-fs the AddSharePath flags carry the kind in the low 16 bits and the shared memory (DAX window)
// size in MB in the high 16 bits.
static const UINT32 kVirtioFsFiles = 0x8000, kVirtioFsSections = 0x4000;

static std::string GuidStr(const GUID& g)
{
    wchar_t w[64];
    StringFromGUID2(g, w, 64);
    return Narrow(w);
}

class DeviceHostProxy : public IVmDeviceHostSupport, public IPlan9FileSystemHost
{
    struct Entry { IVmFiovGuestMemoryFastNotification* notify = nullptr; IVmFiovGuestMmioMappings* mmio = nullptr; };
    std::mutex m_lock;
    std::map<std::string, Entry> m_devices;
    IVmVirtualDeviceAccess* m_access = nullptr;

    HRESULT Device(REFGUID id, Entry*& e)
    {
        auto i = m_devices.find(GuidStr(id));
        if (i == m_devices.end()) return E_ACCESSDENIED;
        e = &i->second;
        if (!m_access)
        {
            using Fn = HRESULT(WINAPI*)(REFGUID, REFIID, IUnknown**);
            static Fn get = (Fn)GetProcAddress(LoadLibraryW(L"vmwpctrl.dll"), "GetVmWorkerProcess");
            if (!get) return HRESULT_FROM_WIN32(ERROR_PROC_NOT_FOUND);
            HRESULT hr = get(g_runtimeId, __uuidof(IVmVirtualDeviceAccess), (IUnknown**)&m_access);
            if (FAILED(hr)) return hr;
        }
        if (!e->notify || !e->mmio)
        {
            IUnknown* dev = nullptr;
            HRESULT hr = m_access->GetDevice(kFlexIoCategory, id, &dev);
            if (FAILED(hr)) return hr;
            if (!e->notify) dev->QueryInterface(__uuidof(IVmFiovGuestMemoryFastNotification), (void**)&e->notify);
            if (!e->mmio) dev->QueryInterface(__uuidof(IVmFiovGuestMmioMappings), (void**)&e->mmio);
            dev->Release();
        }
        return S_OK;
    }

public:
    void Known(const GUID& id) { std::lock_guard<std::mutex> l(m_lock); m_devices[GuidStr(id)]; }

    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void** out) override
    {
        if (iid == __uuidof(IUnknown) || iid == __uuidof(IVmDeviceHostSupport)) *out = static_cast<IVmDeviceHostSupport*>(this);
        else if (iid == __uuidof(IPlan9FileSystemHost)) *out = static_cast<IPlan9FileSystemHost*>(this);
        else { *out = nullptr; return E_NOINTERFACE; }
        return S_OK;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return 2; }
    ULONG STDMETHODCALLTYPE Release() override { return 1; }

    HRESULT STDMETHODCALLTYPE RegisterDeviceHost(IVmDeviceHost* host, DWORD pid, UINT64* ipc) override
    {
        using Fn = HRESULT(WINAPI*)(HCS_SYSTEM, PVOID, DWORD, UINT64*);
        static Fn proxy = (Fn)GetProcAddress(LoadLibraryW(L"vmdevicehost.dll"), "HdvProxyDeviceHost");
        IUnknown* unk = nullptr;
        host->QueryInterface(__uuidof(IUnknown), (void**)&unk);
        HRESULT hr = proxy ? proxy(g_system, unk, pid, ipc) : HRESULT_FROM_WIN32(ERROR_PROC_NOT_FOUND);
        if (unk) unk->Release();
        std::printf("virtiofs: RegisterDeviceHost pid %lu: 0x%08lx\n", pid, hr);
        return hr;
    }
    HRESULT STDMETHODCALLTYPE NotifyAllDevicesInUse(LPCWSTR) override { return S_OK; }
    HRESULT STDMETHODCALLTYPE RegisterDoorbell(REFGUID id, UINT8 bar, UINT64 off, UINT64 trig, UINT64 flags, HANDLE ev) override
    {
        std::lock_guard<std::mutex> l(m_lock);
        Entry* e;
        HRESULT hr = Device(id, e);
        if (SUCCEEDED(hr)) hr = e->notify ? e->notify->RegisterDoorbell(bar, off, trig, flags, ev) : E_NOINTERFACE;
        std::printf("virtiofs: doorbell %s bar %u off 0x%llx: 0x%08lx\n", GuidStr(id).c_str(), bar, off, hr);
        return hr;
    }
    HRESULT STDMETHODCALLTYPE UnregisterDoorbell(REFGUID id, UINT8 bar, UINT64 off, UINT64 trig, UINT64 flags) override
    {
        std::lock_guard<std::mutex> l(m_lock);
        Entry* e;
        HRESULT hr = Device(id, e);
        return SUCCEEDED(hr) && e->notify ? e->notify->UnregisterDoorbell(bar, off, trig, flags) : hr;
    }
    HRESULT STDMETHODCALLTYPE CreateSectionBackedMmioRange(REFGUID id, UINT8 bar, UINT64 offPages, UINT64 pages, UINT64 flags, HANDLE section, UINT64 secOffPages) override
    {
        std::lock_guard<std::mutex> l(m_lock);
        Entry* e;
        HRESULT hr = Device(id, e);
        if (SUCCEEDED(hr)) hr = e->mmio ? e->mmio->CreateSectionBackedMmioRange(bar, offPages, pages, (UINT)flags, section, secOffPages) : E_NOINTERFACE;
        std::printf("virtiofs: mmio range %s bar %u +%llu pages %llu: 0x%08lx\n", GuidStr(id).c_str(), bar, offPages, pages, hr);
        return hr;
    }
    HRESULT STDMETHODCALLTYPE DestroySectionBackedMmioRange(REFGUID id, UINT8 bar, UINT64 offPages) override
    {
        std::lock_guard<std::mutex> l(m_lock);
        Entry* e;
        HRESULT hr = Device(id, e);
        return SUCCEEDED(hr) && e->mmio ? e->mmio->DestroySectionBackedMmioRange(bar, offPages) : hr;
    }
};
static DeviceHostProxy g_deviceHost;

// Object directory \Sessions\<n>\BaseNamedObjects\WSL\<vm>\<tag> for a section-backed share (WslCoreVm::
// CreateSectionObjectRoot); the handles are kept open for the life of the process.
static DWORD g_clientSession = (DWORD)-1;  // from client.ready (split mode)
static std::string g_clientSid;

static std::string UserSid()
{
    HANDLE token = nullptr;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) return {};
    char buf[256];
    DWORD n = 0;
    std::string out;
    LPWSTR str = nullptr;
    if (GetTokenInformation(token, TokenUser, buf, sizeof buf, &n) && ConvertSidToStringSidW(((TOKEN_USER*)buf)->User.Sid, &str))
    {
        out = Narrow(str);
        LocalFree(str);
    }
    CloseHandle(token);
    return out;
}

static std::wstring SectionRoot(const std::wstring& tag)
{
    using Fn = NTSTATUS(NTAPI*)(PHANDLE, ACCESS_MASK, POBJECT_ATTRIBUTES);
    static Fn create = (Fn)GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "NtCreateDirectoryObject");
    static Fn open = (Fn)GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "NtOpenDirectoryObject");
    DWORD session = 0;
    ProcessIdToSessionId(GetCurrentProcessId(), &session);
    if (g_clientSession != (DWORD)-1) session = g_clientSession;
    std::wstring path = L"\\Sessions\\" + std::to_wstring(session) + L"\\BaseNamedObjects\\WSL\\" + kVmId + L"\\" + tag;
    // The directories an elevated process creates get the elevated default DACL (Administrators, SYSTEM): a separate
    // client running as the plain user could not create its clipboard sections in them. Grant the user explicitly.
    PSECURITY_DESCRIPTOR sd = nullptr;
    std::wstring sddl = L"D:(A;;GA;;;SY)(A;;GA;;;BA)";
    if (std::string sid = g_clientSid.empty() ? UserSid() : g_clientSid; !sid.empty()) sddl += L"(A;;GA;;;" + Widen(sid) + L")";
    ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.c_str(), SDDL_REVISION_1, &sd, nullptr);
    HANDLE parent = nullptr;
    for (size_t start = 1; start <= path.size();)
    {
        size_t end = path.find(L'\\', start);
        if (end == std::wstring::npos) end = path.size();
        std::wstring part = parent ? path.substr(start, end - start) : path.substr(0, end);
        UNICODE_STRING name{(USHORT)(part.size() * 2), (USHORT)(part.size() * 2), part.data()};
        OBJECT_ATTRIBUTES attr{sizeof attr, parent, &name, 0, sd, nullptr};
        HANDLE h = nullptr;
        NTSTATUS st = create(&h, 0xF000F /* DIRECTORY_ALL_ACCESS */, &attr);
        if (st == (NTSTATUS)0xC0000035 /* STATUS_OBJECT_NAME_COLLISION */) st = open(&h, MAXIMUM_ALLOWED, &attr);
        if (st < 0) { std::printf("virtiofs: object directory %ls: 0x%08lx\n", part.c_str(), st); return {}; }
        parent = h;
        start = end + 1;
    }
    return path;
}

// shares.txt, one share per line (# comments):
//   clsid {GUID}                      — COM class of the virtio-fs device host (default below; WSA's AppxManifest lists
//                                       three wsldevicehost.dll classes, 16479D2E-... is virtio-net)
//   dll <path of wsldevicehost.dll>   — load the class into this process instead of the package's DLL surrogate. The
//                                       surrogate outlives the host and stays bound to the previous VM: the next run
//                                       gets no RegisterDeviceHost and FlexibleIov fails with 0x8000ffff (12.10.2026)
//   <tag> files <Windows dir>         — a directory share, as WSA's shared_tmp/shared_anr/shared_tombstone
//   <tag> sections <size-MB>          — section-backed shared memory with a DAX window, as WSA's shared_mem; needs
//                                       HighMmioGapInMB in config.json to cover it
//   nic <HCN network id> [<ip>/<prefix> <gateway>]
//                                     — a network adapter on that HCN network (--networks lists them: WSA's own is
//                                       "WSLCore", "Default Switch" is the stock one), added the way WSA adds its NIC:
//                                       an HCN endpoint, then VirtualMachine/Devices/NetworkAdapters/{endpoint}; the
//                                       address goes to the guest's SystemApp as Settings.NetworkConfiguration, as WSA
//                                       does (without it SystemApp keeps the static address it stored in /data)
//   device <clsid> <emulator> <tag>   — any other virtio device served the same way: COM class <clsid> (activated out
//                                       of process; @<file> — unmarshaled from wsa-com-broker.exe, for classes that an
//                                       elevated process cannot activate), FlexibleIov emulator <emulator>. WSA adds its virtio-gpu so:
//                                       class and emulator a6727475-2a07-4acc-9fa6-58347fdf7a37 (CLSID_VirtioGpuDevice of
//                                       WsaClient.exe), as the Hyper-V-Compute operational log shows (12.10.2026)
static std::wstring g_sharedTmp;  // the Windows directory of the shared_tmp share (FileSystem channel)
static bool g_separateClient;     // "client separate": the desktop side is a separate --client process
static std::string g_nicNetwork;  // HCN network id of the "nic" line
static std::set<std::string> g_devHost;  // "devicehost ..." words: client, acl, impersonate (see AddShares)

static bool ParseIp(const std::string& s, unsigned& ip)
{
    unsigned a, b, c, d;
    if (std::sscanf(s.c_str(), "%u.%u.%u.%u", &a, &b, &c, &d) != 4) return false;
    ip = a << 24 | b << 16 | c << 8 | d;
    return true;
}

static std::string IpStr(unsigned ip)
{
    return std::to_string(ip >> 24) + "." + std::to_string(ip >> 16 & 255) + "." + std::to_string(ip >> 8 & 255) + "." + std::to_string(ip & 255);
}

// The subnet of an ICS network ("Default Switch") changes across reboots (172.31.240.0/20 on 12.10.2026,
// 192.168.80.0/20 on 15.10.2026), while the guest's address is static. Take the network's current subnet and gateway
// from HCN and move the configured address into it, keeping its host part.
static void ResolveNic()
{
    if (g_nicNetwork.empty()) return;
    GUID net{};
    CLSIDFromString(Widen("{" + g_nicNetwork + "}").c_str(), &net);
    HCN_NETWORK n = nullptr;
    PWSTR props = nullptr, err = nullptr;
    if (FAILED(HcnOpenNetwork(net, &n, &err)) || FAILED(HcnQueryNetworkProperties(n, L"{\"SchemaVersion\":{\"Major\":2,\"Minor\":0}}", &props, &err)))
    {
        std::printf("nic: cannot query network %s, keeping %s via %s\n", g_nicNetwork.c_str(), g_nicIp.c_str(), g_nicGateway.c_str());
        return;
    }
    std::string p = Narrow(props);
    HcnCloseNetwork(n);
    std::smatch m, h;
    unsigned base, gw, ip;
    if (!std::regex_search(p, m, std::regex("\"IpAddressPrefix\"\\s*:\\s*\"([0-9.]+)/([0-9]+)\"")) ||
        !std::regex_search(p, h, std::regex("\"NextHop\"\\s*:\\s*\"([0-9.]+)\"")) || !ParseIp(m[1].str(), base) || !ParseIp(h[1].str(), gw))
    {
        std::printf("nic: no subnet in %s\n", p.c_str());
        return;
    }
    unsigned prefix = (unsigned)std::stoul(m[2].str()), mask = prefix ? ~0u << (32 - prefix) : 0;
    if (!ParseIp(g_nicIp.substr(0, g_nicIp.find('/')), ip)) ip = 77;
    ip = (base & mask) | (ip & ~mask);
    if (ip == gw || (ip & ~mask) == 0 || (ip | mask) == ~0u) ip = (base & mask) | 77;
    g_nicIp = IpStr(ip) + "/" + std::to_string(prefix);
    g_nicGateway = IpStr(gw);
    std::printf("nic: network subnet %s/%u, guest %s via %s\n", m[1].str().c_str(), prefix, g_nicIp.c_str(), g_nicGateway.c_str());
}

// What both halves need from shares.txt without acting on it: the guest's address (Settings channel), the shared_tmp
// directory (FileSystem channel) and whether the client is separate.
static void ReadSharesSettings()
{
    struct Default { ~Default() { if (g_separateClient && !g_devHost.count("service")) g_devHost.insert("client"); } } byDefault;
    std::ifstream f(ConfigFile(L"shares.txt"));
    for (std::string line; std::getline(f, line);)
    {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        std::istringstream in(line);
        std::string first, kind, rest;
        if (!(in >> first) || first[0] == '#') continue;
        in >> kind >> std::ws;
        std::getline(in, rest);
        if (first == "client") g_separateClient = kind == "separate";
        else if (first == "devicehost") { std::istringstream r(kind + " " + rest); for (std::string w; r >> w;) g_devHost.insert(w); }
        else if (first == "nic") { g_nicNetwork = kind; std::istringstream r(rest); r >> g_nicIp >> g_nicGateway; }
        else if (first == "shared_tmp" && kind == "files") g_sharedTmp = Widen(rest);
    }
}

// The subsystem's resources and memory, as WSA's settings ("Advanced settings" → "Memory and performance"), in
// settings.txt next to the exe. What the stock does was read from WsaClient.exe 2407.40000.4.0 (15.10.2026): the values
// live in the package's LocalSettings (Settings\settings.dat), HKLM\Software\Microsoft\WSA overrides them as a policy.
//   lifecycle continuous|asneeded|partial — VMLifeCycleMode 0/1/2. Without the line: as WsaClient, by the machine's
//       memory (GlobalMemoryStatusEx) — under 16 GB "as needed", else "partially running". "continuous" and "partial"
//       start at sign-in (the package's startup tasks ContinuousVirtualMachine "/restart 0" and
//       PartiallyRunningVirtualMachine "/partiallyrunning"; here --lifecycle sets the Run key); "asneeded" starts with
//       an app (--launch). With no app window for the idle time WsaClient's IdleChecker shuts the VM down (reason
//       IdleCheck) — "asneeded" for good, "partial" to start a fresh one; "continuous" has no IdleChecker.
//   idle <minutes>  — the IdleChecker's timer: 10 minutes in WsaClient (fractions for tests)
//   memory <GB>|default — VMMemoryAllocation/VMMemorySizeGB. "default" as WsaClient: 6 GB from 12 GB of RAM, 4 from 8,
//       3 above 4, else 2. Without the line the "SizeInMB" of config.json stays. Read by the VM half.
//   clipboard on|off — the shared clipboard with Windows (WSA has it always on); off by default, and "clip on|off" on
//       the command port overrides it until the line changes. Read by the client.
enum { kContinuous = 0, kAsNeeded = 1, kPartial = 2 };  // WSA's VMLifeCycleMode values
static const char* const kLifecycleNames[] = {"continuous", "asneeded", "partial"};
static int g_lifecycle = kAsNeeded, g_memoryMb = 0;
static double g_idleMinutes = 10;
static int g_clipSetting = -1;  // settings.txt "clipboard": -1 no line, 0 off, 1 on

static void ReadSettings()
{
    MEMORYSTATUSEX ms{sizeof ms};
    GlobalMemoryStatusEx(&ms);
    const unsigned long long gb = 1ull << 30, ram = ms.ullTotalPhys;
    g_lifecycle = ram < 16 * gb ? kAsNeeded : kPartial;
    g_clipSetting = -1;
    std::ifstream f(UserFile(L"settings.txt"));
    for (std::string line; std::getline(f, line);)
    {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        std::istringstream in(line);
        std::string key, value;
        if (!(in >> key >> value) || key[0] == '#') continue;
        if (key == "lifecycle")
            for (int i = 0; i < 3; i++)
                if (value == kLifecycleNames[i]) g_lifecycle = i;
        if (key == "clipboard") g_clipSetting = value == "on";
        if (key == "idle") g_idleMinutes = (std::max)(0.05, std::atof(value.c_str()));
        if (key == "memory")
            g_memoryMb = value == "default" ? (ram >= 12 * gb ? 6144 : ram >= 8 * gb ? 4096 : ram > 4 * gb ? 3072 : 2048)
                                            : (std::clamp)(std::atoi(value.c_str()), 1, 128) * 1024;
    }
}
// The virtio device host under a LocalSystem service: HdvProxyDeviceHost answered E_ACCESSDENIED (15.10.2026) for the
// device host loaded into the service process, while WSL's LocalSystem service gets it working with the device host in
// a separate COM server running as the user (DeviceHostProxy::GetWslVm: impersonate the user, CoCreateInstance
// CLSCTX_LOCAL_SERVER | CLSCTX_ENABLE_CLOAKING). shares.txt "devicehost <words>" picks what to try:
//   client       — (the default with "client separate"; "service" keeps it in this process) the device host lives in the --client process (the user's): it loads the "dll" line's
//                  wsldevicehost.dll, creates one IPlan9FileSystem per files/sections share and marshals it to
//                  devhost-<tag>.objref; the service unmarshals them. As WSL: a device host process of the user's own.
//   acl          — grant the Virtual Machines group (S-1-5-83-0) and this VM's account full access to this process
//   impersonate  — create the devices impersonating the client's user (WTSQueryUserToken of its session)
// NOT the package's out-of-process class (tried 15.10.2026): both WSA packages register the same CLSID, and COM handed
// out the running surrogate of the owner's working WSA — the host's devices were created in that process.
static HANDLE g_userToken;
static void ProcessAclForVm()
{
    unsigned d[4];
    memcpy(d, &g_runtimeId, sizeof d);
    std::wstring sids[2] = {L"S-1-5-83-0", L"S-1-5-83-1-" + std::to_wstring(d[0]) + L"-" + std::to_wstring(d[1]) + L"-" + std::to_wstring(d[2]) + L"-" + std::to_wstring(d[3])};
    PACL dacl = nullptr, updated = nullptr;
    PSECURITY_DESCRIPTOR sd = nullptr;
    DWORD rc = GetSecurityInfo(GetCurrentProcess(), SE_KERNEL_OBJECT, DACL_SECURITY_INFORMATION, nullptr, nullptr, &dacl, nullptr, &sd);
    EXPLICIT_ACCESSW ea[2]{};
    PSID psid[2]{};
    for (int i = 0; i < 2; i++)
    {
        ConvertStringSidToSidW(sids[i].c_str(), &psid[i]);
        ea[i].grfAccessPermissions = PROCESS_ALL_ACCESS;
        ea[i].grfAccessMode = GRANT_ACCESS;
        ea[i].Trustee.TrusteeForm = TRUSTEE_IS_SID;
        ea[i].Trustee.ptstrName = (LPWSTR)psid[i];
    }
    if (!rc) rc = SetEntriesInAclW(2, ea, dacl, &updated);
    if (!rc) rc = SetSecurityInfo(GetCurrentProcess(), SE_KERNEL_OBJECT, DACL_SECURITY_INFORMATION, nullptr, nullptr, updated, nullptr);
    std::printf("devicehost acl: %ls, %ls: %lu\n", sids[0].c_str(), sids[1].c_str(), rc);
    for (PSID p : psid) if (p) LocalFree(p);
    if (updated) LocalFree(updated);
    if (sd) LocalFree(sd);
}

// Impersonation of the client's user for the device host calls; HCS itself is called as the service (AsSelf).
struct AsUser
{
    bool on = false;
    explicit AsUser(bool want) { on = want && g_userToken && ImpersonateLoggedOnUser(g_userToken); }
    ~AsUser() { if (on) RevertToSelf(); }
};
struct AsSelf
{
    bool was = false;
    AsSelf() { HANDLE t = nullptr; was = OpenThreadToken(GetCurrentThread(), TOKEN_QUERY, TRUE, &t); if (t) CloseHandle(t); if (was) RevertToSelf(); }
    ~AsSelf() { if (was) ImpersonateLoggedOnUser(g_userToken); }
};

static void AddShares()
{
    std::ifstream f(ConfigFile(L"shares.txt"));
    if (!f) return;
    CLSID clsid = {0x5A167A26, 0x2D39, 0x4456, {0xB8, 0x02, 0x61, 0xF1, 0xAB, 0xE8, 0x3D, 0x84}};
    std::vector<std::vector<std::string>> shares;
    IClassFactory* factory = nullptr;
    for (std::string line; std::getline(f, line);)
    {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        std::istringstream in(line);
        std::vector<std::string> w;
        std::string first;
        if (!(in >> first) || first[0] == '#') continue;
        if (first == "client" || first == "devicehost") continue;  // see ReadSharesSettings
        if (first == "clsid") { std::string g; in >> g; CLSIDFromString(Widen(g).c_str(), &clsid); continue; }
        if (first == "nic") { std::string n; in >> n; shares.push_back({"nic", "nic", n}); continue; }  // address: ResolveNic
        if (first == "device") { std::string c, e, t; in >> c >> e >> t; shares.push_back({t, "device", c, e}); continue; }
        if (first == "dll")
        {
            std::string path;
            std::getline(in >> std::ws, path);
            using Fn = HRESULT(STDAPICALLTYPE*)(REFCLSID, REFIID, LPVOID*);
            HMODULE m = LoadLibraryW(Widen(path).c_str());
            Fn get = m ? (Fn)GetProcAddress(m, "DllGetClassObject") : nullptr;
            HRESULT hr = get ? get(clsid, __uuidof(IClassFactory), (void**)&factory) : HRESULT_FROM_WIN32(GetLastError());
            std::printf("virtiofs: in-process %s: 0x%08lx\n", path.c_str(), hr);
            continue;
        }
        std::string kind, rest;
        in >> kind >> std::ws;
        std::getline(in, rest);
        shares.push_back({first, kind, rest});
        if (first == "shared_tmp" && kind == "files") g_sharedTmp = Widen(rest);
    }
    if (g_devHost.count("acl")) ProcessAclForVm();
    bool fromClient = g_devHost.count("client") > 0, impersonate = g_devHost.count("impersonate") > 0;
    if (impersonate && !g_userToken && g_clientSession != (DWORD)-1)
    {
        BOOL ok = WTSQueryUserToken(g_clientSession, &g_userToken);
        std::printf("devicehost: user token of session %lu: %s (%lu)\n", g_clientSession, ok ? "ok" : "failed", ok ? 0 : GetLastError());
    }
    if (fromClient && factory) { factory->Release(); factory = nullptr; }
    for (auto& s : shares)
    {
        AsUser user(impersonate && s[1] != "nic");
        if (s[1] == "nic")
        {
            // A fixed endpoint id: the endpoint of the previous run (killed process) is deleted first.
            static const GUID kEndpoint = {0x5f3c1a7e, 0x2b4d, 0x4e8a, {0x9c, 0x61, 0x0d, 0x7b, 0x2e, 0x4f, 0x9a, 0x14}};
            GUID net{};
            CLSIDFromString(Widen("{" + s[2] + "}").c_str(), &net);
            PWSTR err = nullptr;
            HcnDeleteEndpoint(kEndpoint, &err);
            HCN_NETWORK n = nullptr;
            HRESULT hr = HcnOpenNetwork(net, &n, &err);
            if (FAILED(hr)) { std::printf("nic: HcnOpenNetwork %s: 0x%08lx %s\n", s[2].c_str(), hr, Narrow(err).c_str()); continue; }
            std::string settings = "{\"SchemaVersion\":{\"Major\":2,\"Minor\":0},\"Owner\":\"wsa-reborn-host\",\"HostComputeNetwork\":\"" + s[2] + "\"}";
            HCN_ENDPOINT ep = nullptr;
            hr = HcnCreateEndpoint(n, kEndpoint, Widen(settings).c_str(), &ep, &err);
            if (FAILED(hr)) { std::printf("nic: HcnCreateEndpoint: 0x%08lx %s\n", hr, Narrow(err).c_str()); continue; }
            PWSTR props = nullptr;
            HcnQueryEndpointProperties(ep, L"{\"SchemaVersion\":{\"Major\":2,\"Minor\":0}}", &props, &err);
            std::string p = Narrow(props);
            std::printf("nic: endpoint %s\n", p.c_str());
            std::smatch mac;
            std::regex_search(p, mac, std::regex("\"MacAddress\"\\s*:\\s*\"([0-9A-Fa-f-]{17})\""));
            std::string id = GuidStr(kEndpoint).substr(1, 36);
            std::string req = "{\"ResourcePath\":\"VirtualMachine/Devices/NetworkAdapters/{" + id + "}\",\"RequestType\":\"Add\",\"Settings\":{\"EndpointId\":\"" + id +
                              "\",\"MacAddress\":\"" + mac[1].str() + "\",\"InstanceId\":\"" + id + "\"}}";
            HCS_OPERATION op = HcsCreateOperation(nullptr, nullptr);
            { AsSelf self; HcsModifyComputeSystem(g_system, op, Widen(req).c_str(), nullptr); }
            Wait(op, "nic: add NetworkAdapter");
            continue;
        }
        if (s[1] == "device")
        {
            CLSID dc{};
            GUID emu{};
            CLSIDFromString(Widen("{" + s[2] + "}").c_str(), &dc);
            CLSIDFromString(Widen("{" + s[3] + "}").c_str(), &emu);
            IUnknown* unk = nullptr;
            HRESULT hr;
            if (s[2][0] == '@')
            {
                // Marshaled by wsa-com-broker.exe running as the normal user.
                std::ifstream mf(s[2].substr(1), std::ios::binary);
                std::string b((std::istreambuf_iterator<char>(mf)), std::istreambuf_iterator<char>());
                IStream* st = SHCreateMemStream((const BYTE*)b.data(), (UINT)b.size());
                hr = st ? CoUnmarshalInterface(st, __uuidof(IUnknown), (void**)&unk) : E_OUTOFMEMORY;
                std::printf("device %s: CoUnmarshalInterface %s (%zu bytes): 0x%08lx\n", s[0].c_str(), s[2].c_str(), b.size(), hr);
            }
            else
            {
                hr = CoCreateInstance(dc, nullptr, CLSCTX_LOCAL_SERVER, __uuidof(IUnknown), (void**)&unk);
                std::printf("device %s: CoCreateInstance %s: 0x%08lx\n", s[0].c_str(), s[2].c_str(), hr);
            }
            if (FAILED(hr)) continue;
            IPlan9FileSystem* dev = nullptr;
            hr = unk->QueryInterface(__uuidof(IPlan9FileSystem), (void**)&dev);
            std::printf("device %s: QueryInterface(IPlan9FileSystem): 0x%08lx\n", s[0].c_str(), hr);
            if (FAILED(hr)) continue;
            GUID instance{};
            CoCreateGuid(&instance);
            g_deviceHost.Known(instance);
            hr = dev->CreateVirtioDevice(kVmId, static_cast<IVmDeviceHostSupport*>(&g_deviceHost), Widen(s[0]).c_str(), &instance);
            std::printf("device %s: CreateVirtioDevice %s: 0x%08lx\n", s[0].c_str(), GuidStr(instance).c_str(), hr);
            if (FAILED(hr)) continue;
            std::string req = "{\"ResourcePath\":\"VirtualMachine/Devices/FlexibleIov/" + GuidStr(instance).substr(1, 36) + "\",\"RequestType\":\"Add\",\"Settings\":{\"EmulatorId\":\"" +
                              s[3] + "\",\"HostingModel\":\"ExternalRestricted\"}}";
            HCS_OPERATION op = HcsCreateOperation(nullptr, nullptr);
            { AsSelf self; HcsModifyComputeSystem(g_system, op, Widen(req).c_str(), nullptr); }
            Wait(op, ("device " + s[0] + ": add FlexibleIov").c_str());
            continue;
        }
        std::wstring tag = Widen(s[0]), path;
        UINT32 flags;
        if (s[1] == "sections") { flags = (UINT32)std::stoul(s[2]) << 16 | kVirtioFsSections; path = SectionRoot(tag); }
        else { flags = kVirtioFsFiles; path = Widen(s[2]); CreateDirectoryW(path.c_str(), nullptr); if (path.back() != L'\\') path += L'\\'; }
        // One server per share, as WSL creates a device per AddSharePath.
        IPlan9FileSystem* fs = nullptr;
        HRESULT hr;
        if (fromClient)
        {
            std::ifstream mf(UserFile(Widen("devhost-" + s[0] + ".objref")), std::ios::binary);
            std::string b((std::istreambuf_iterator<char>(mf)), std::istreambuf_iterator<char>());
            IStream* st = b.empty() ? nullptr : SHCreateMemStream((const BYTE*)b.data(), (UINT)b.size());
            hr = st ? CoUnmarshalInterface(st, __uuidof(IPlan9FileSystem), (void**)&fs) : HRESULT_FROM_WIN32(ERROR_FILE_NOT_FOUND);
            if (st) st->Release();
            std::printf("virtiofs %s: device host of the client (%zu bytes): 0x%08lx\n", s[0].c_str(), b.size(), hr);
        }
        else
            hr = factory ? factory->CreateInstance(nullptr, __uuidof(IPlan9FileSystem), (void**)&fs)
                         : CoCreateInstance(clsid, nullptr, CLSCTX_LOCAL_SERVER, __uuidof(IPlan9FileSystem), (void**)&fs);
        if (FAILED(hr)) { std::printf("virtiofs %s: CoCreateInstance %s: 0x%08lx\n", s[0].c_str(), GuidStr(clsid).c_str(), hr); continue; }
        hr = fs->AddSharePath(tag.c_str(), path.c_str(), flags);
        std::printf("virtiofs %s: AddSharePath %ls flags 0x%x: 0x%08lx\n", s[0].c_str(), path.c_str(), flags, hr);
        if (FAILED(hr)) continue;
        GUID instance{};
        CoCreateGuid(&instance);
        g_deviceHost.Known(instance);
        hr = fs->CreateVirtioDevice(kVmId, static_cast<IVmDeviceHostSupport*>(&g_deviceHost), tag.c_str(), &instance);
        std::printf("virtiofs %s: CreateVirtioDevice %s: 0x%08lx\n", s[0].c_str(), GuidStr(instance).c_str(), hr);
        if (FAILED(hr)) continue;
        std::string id = GuidStr(instance).substr(1, 36), emu = GuidStr(kVirtioFsEmulator).substr(1, 36);
        std::string req = "{\"ResourcePath\":\"VirtualMachine/Devices/FlexibleIov/" + id + "\",\"RequestType\":\"Add\",\"Settings\":{\"EmulatorId\":\"" + emu +
                          "\",\"HostingModel\":\"ExternalRestricted\"}}";
        HCS_OPERATION op = HcsCreateOperation(nullptr, nullptr);
        { AsSelf self; HcsModifyComputeSystem(g_system, op, Widen(req).c_str(), nullptr); }
        Wait(op, ("virtiofs " + s[0] + ": add FlexibleIov").c_str());
    }
}

// ---- RCM (host side) -----------------------------------------------------------------------------------------
// The guest's librcm (docs/vendor-transport.md) connects to the host on vsock 35002 (control connection of the rcm
// service) and asks for channels "the other way round": it listens on an ephemeral vsock port and sends
// {u32 total_len, u32 type=1, u32 0, u32 listen_port, u32 name_len, u32 0, name\0}; the host connects back to that
// port. Inside a channel: u32 LE length + LatteApi.Common.ApiMessageHeader. Everything is logged to rcm.log; the only
// channels served so far: SysPrep (StartPhase1), Composition (one display), AudioControl (fixed format), AudioOut (played to Windows); every other call gets an empty Success
// reply. Port 35034 is logcat, see RcmControl — the host's StartPhase1 lets init past wait_for_prop vendor.sysprep.start_phase=1.
static std::ofstream g_rcmLog;

static void RcmLog(const std::string& s)
{
    std::lock_guard<std::mutex> l(g_logLock);
    g_rcmLog << GetTickCount64() << " " << s << "\n";
    g_rcmLog.flush();
}

static std::string Hex(const char* p, size_t n, size_t max = 256)
{
    std::string h;
    char b[4];
    for (size_t i = 0; i < n && i < max; i++) { std::snprintf(b, sizeof b, "%02x", (unsigned char)p[i]); h += b; }
    return h;
}

static GUID VsockService(unsigned port)
{
    GUID g = {port, 0xfacb, 0x11e6, {0xbd, 0x58, 0x64, 0x00, 0x6a, 0x79, 0x86, 0xd3}};
    return g;
}

// Frames u32 LE length + ApiMessageHeader{call_id=1 (field 1), payload (field 4) = SysPrepMessage{message_type}}.
static std::string SysPrepFrame(unsigned type)
{
    std::string body = {0x08, 0x01, 0x22, 0x02, 0x08, (char)type};
    unsigned n = (unsigned)body.size();
    return std::string((const char*)&n, 4) + body;
}

// Minimal protobuf for the channels the host serves: varint and length-delimited fields only.
static void PbVarint(std::string& o, unsigned long long v) { do { char b = v & 0x7f; v >>= 7; o += (char)(b | (v ? 0x80 : 0)); } while (v); }
static void PbInt(std::string& o, int field, unsigned long long v) { PbVarint(o, (unsigned)field << 3); PbVarint(o, v); }
static void PbBytes(std::string& o, int field, const std::string& b) { PbVarint(o, (unsigned)field << 3 | 2); PbVarint(o, b.size()); o += b; }

// Top-level varint/bytes fields of a message; other wire types are skipped.
static std::map<int, std::string> PbFields(const std::string& m, std::map<int, unsigned long long>* ints = nullptr)
{
    std::map<int, std::string> out;
    size_t i = 0;
    auto varint = [&](unsigned long long& v) { v = 0; for (int sh = 0; i < m.size(); sh += 7) { unsigned char b = m[i++]; v |= (unsigned long long)(b & 0x7f) << sh; if (!(b & 0x80)) return true; } return false; };
    while (i < m.size())
    {
        unsigned long long key, v;
        if (!varint(key)) break;
        int field = (int)(key >> 3), type = (int)(key & 7);
        if (type == 0) { if (!varint(v)) break; if (ints) (*ints)[field] = v; }
        else if (type == 2) { if (!varint(v) || i + v > m.size()) break; out[field] = m.substr(i, (size_t)v); i += (size_t)v; }
        else if (type == 1) i += 8;
        else if (type == 5) i += 4;
        else break;
    }
    return out;
}

static void SendFrame(SOCKET h, const std::string& body)
{
    unsigned n = (unsigned)body.size();
    std::string f = std::string((const char*)&n, 4) + body;
    send(h, f.data(), (int)f.size(), 0);
}

// Reply ApiMessageHeader{call_id, is_reply=true, return_code=0 (Success), payload}.
static std::string Reply(unsigned long long callId, const std::string& payload)
{
    std::string h;
    PbInt(h, 1, callId);
    PbInt(h, 2, 1);
    PbInt(h, 3, 0);
    if (!payload.empty()) PbBytes(h, 4, payload);
    return h;
}

// Composition (hwcomposer.windows): GetDisplayConfigs is answered with one display; other calls get an empty reply.
// Size and density — WSA_HOST_DISPLAY=<w>x<h>@<dpi> in the environment, default 1280x800@200 (androidboot.lcd_density).
static std::string CompositionAnswer(unsigned long long type)
{
    if (type != 1) return {};
    int w = 1280, hgt = 800, dpi = 200;
    if (const char* e = getenv("WSA_HOST_DISPLAY")) std::sscanf(e, "%dx%d@%d", &w, &hgt, &dpi);
    std::string cfg, list, msg;
    PbInt(cfg, 1, 0);
    PbInt(cfg, 2, w);
    PbInt(cfg, 3, hgt);
    PbInt(cfg, 4, 16666666);  // vsync period, ns (60 Hz)
    PbInt(cfg, 5, dpi);
    PbInt(cfg, 6, dpi);
    PbInt(cfg, 7, 1);
    PbBytes(list, 1, cfg);
    PbInt(msg, 1, 1);
    PbBytes(msg, 2, list);
    return msg;
}

// AudioControl frames carry a bare LatteApi.AudioControl.AudioControlMessage (no ApiMessageHeader): the guest's audio HAL
// asks {message_type=Request, request_id, request{control_code, arg}} and waits for {Response, request_id,
// response{control_code, error_code, result}} — without an answer audioserver dies and system_server's watchdog fires
// (12.10.2026). Answers: 48 kHz, stereo, PCM 16 bit; AudioOut is played (SoundFrame), AudioIn stays silent.
static std::string AudioControlAnswer(const std::string& body)
{
    std::map<int, unsigned long long> m, rq;
    auto parts = PbFields(body, &m);
    if (m[1] != 1 || !parts.count(3)) return {};
    PbFields(parts[3], &rq);
    unsigned long long code = rq[1], result = 0;
    switch (code)
    {
    case 1: result = 100; break;              // GetMasterVolume
    case 5: case 11: result = 48000; break;   // GetSampleRate, GetInputSampleRate
    case 7: result = 0x3; break;              // GetChannelMask: AUDIO_CHANNEL_OUT_STEREO
    case 13: result = 0x10; break;            // GetInputChannelMask: AUDIO_CHANNEL_IN_MONO
    case 8: case 14: result = 0x1; break;     // GetFormat, GetInputFormat: AUDIO_FORMAT_PCM_16_BIT
    case 9: result = 20; break;               // GetLatency, ms
    case 12: result = 3840; break;            // GetInputBufferSize, bytes
    }
    std::string resp, msg;
    PbInt(resp, 1, code);
    PbInt(resp, 2, 0);
    PbInt(resp, 3, result);
    PbInt(msg, 1, 2);
    PbInt(msg, 2, m[2]);
    PbBytes(msg, 4, resp);
    return msg;
}

// AudioOut: the HAL (ArtAudio::AudioChannel::sendAudio) sends each buffer as a frame {u64 1, u32 bytes} + PCM in the
// format AudioControlAnswer gave it, and paces itself (at most ~75 ms ahead of real time, usleep otherwise) — so the host
// only queues the PCM to waveOut on the default device (the wave mapper follows a change of the default). The queue is
// capped at 400 ms: what comes beyond it (the host stalled) is dropped rather than played late. The command port's
// "sound <0-100>" sets the volume of this output alone, "sound" prints the counters. 16.10.2026.
static std::mutex g_soundLock;
static HWAVEOUT g_waveOut = nullptr;            // under g_soundLock, as the rest
static std::deque<WAVEHDR*> g_waveQueue;
static DWORD g_waveVolume = 0xffffffff;         // waveOutSetVolume: low word left, high word right
static unsigned long long g_soundFrames = 0, g_soundBytes = 0, g_soundDropped = 0, g_soundPlayedBefore = 0;
static const unsigned kSoundRate = 48000, kSoundFrameBytes = 4, kSoundMaxQueued = kSoundRate * kSoundFrameBytes * 4 / 10;

static unsigned long long SoundPlayedLocked()
{
    MMTIME t{TIME_BYTES};
    return g_soundPlayedBefore + (g_waveOut && waveOutGetPosition(g_waveOut, &t, sizeof t) == MMSYSERR_NOERROR && t.wType == TIME_BYTES ? t.u.cb : 0);
}

static void SoundCloseLocked()
{
    if (!g_waveOut) return;
    g_soundPlayedBefore = SoundPlayedLocked();
    waveOutReset(g_waveOut);
    for (WAVEHDR* h : g_waveQueue) { waveOutUnprepareHeader(g_waveOut, h, sizeof *h); delete[] h->lpData; delete h; }
    g_waveQueue.clear();
    waveOutClose(g_waveOut);
    g_waveOut = nullptr;
}

static void SoundFrame(const std::string& body)
{
    if (body.size() < 12 || *(const unsigned long long*)body.data() != 1 || *(const unsigned*)(body.data() + 8) != body.size() - 12)
    {
        RcmLog("channel AudioOut: unexpected frame " + Hex(body.data(), body.size(), 32));
        return;
    }
    const size_t bytes = body.size() - 12;
    std::lock_guard<std::mutex> l(g_soundLock);
    g_soundFrames++, g_soundBytes += bytes;
    if (!g_waveOut)
    {
        WAVEFORMATEX f{WAVE_FORMAT_PCM, 2, kSoundRate, kSoundRate * kSoundFrameBytes, kSoundFrameBytes, 16, 0};
        MMRESULT r = waveOutOpen(&g_waveOut, WAVE_MAPPER, &f, 0, 0, CALLBACK_NULL);
        if (r != MMSYSERR_NOERROR) { g_waveOut = nullptr; g_soundDropped += bytes; if (g_soundFrames % 500 == 1) RcmLog("channel AudioOut: waveOutOpen failed " + std::to_string(r)); return; }
        waveOutSetVolume(g_waveOut, g_waveVolume);
        RcmLog("channel AudioOut: playing to the default device");
    }
    size_t queued = 0;
    while (!g_waveQueue.empty() && (g_waveQueue.front()->dwFlags & WHDR_DONE))
    {
        WAVEHDR* h = g_waveQueue.front();
        g_waveQueue.pop_front();
        waveOutUnprepareHeader(g_waveOut, h, sizeof *h);
        delete[] h->lpData;
        delete h;
    }
    for (WAVEHDR* h : g_waveQueue) queued += h->dwBufferLength;
    if (queued + bytes > kSoundMaxQueued) { g_soundDropped += bytes; return; }
    WAVEHDR* h = new WAVEHDR{};
    h->lpData = new char[bytes];
    memcpy(h->lpData, body.data() + 12, bytes);
    h->dwBufferLength = (DWORD)bytes;
    if (waveOutPrepareHeader(g_waveOut, h, sizeof *h) != MMSYSERR_NOERROR || waveOutWrite(g_waveOut, h, sizeof *h) != MMSYSERR_NOERROR)
    {
        // The device went away (unplugged, the default changed under an exclusive app): reopen on the next frame.
        waveOutUnprepareHeader(g_waveOut, h, sizeof *h);
        delete[] h->lpData;
        delete h;
        g_soundDropped += bytes;
        SoundCloseLocked();
        return;
    }
    g_waveQueue.push_back(h);
}

// SettingsMessage{message_type=NetworkConfiguration (18), activity_id, network_configuration{is_connected=true,
// is_metered=false, is_virtio=false, is_roaming=false, ipv4_address, gateway, prefix_length}} in an ApiMessageHeader.
static std::string NetworkConfigurationFrame(unsigned long long callId)
{
    size_t slash = g_nicIp.find('/');
    std::string ip = g_nicIp.substr(0, slash), act, cfg, msg, hdr;
    unsigned prefix = slash == std::string::npos ? 24 : (unsigned)std::stoul(g_nicIp.substr(slash + 1));
    PbBytes(act, 1, "wsa-reborn-host-network");
    PbInt(cfg, 1, 1);
    PbInt(cfg, 2, 0);
    PbInt(cfg, 3, 0);
    PbInt(cfg, 4, 0);
    PbBytes(cfg, 5, ip);
    PbBytes(cfg, 6, g_nicGateway);
    PbInt(cfg, 7, prefix);
    PbInt(msg, 1, 18);
    PbBytes(msg, 2, act);
    PbBytes(msg, 21, cfg);
    PbInt(hdr, 1, callId);
    PbBytes(hdr, 4, msg);
    return hdr;
}

// UserInput (host -> guest): the frame window's mouse and keyboard go to the guest's "UserInput" channel (the bridge,
// WsaShellBridge UserInputBridge, injects them; WsaShellBridge/proto/userinput.proto). Requests are ApiMessageHeader
// {callId of the host's own counter, payload = UserInputMessage}; the guest replies with an empty payload. 15.10.2026.
static std::mutex g_inputLock;
static SOCKET g_userInput = INVALID_SOCKET;
static unsigned long long g_inputCallId = 0;
static unsigned long long g_inputSent = 0;

static void SendUserInput(const std::string& userInputMessage)
{
    std::lock_guard<std::mutex> l(g_inputLock);
    if (g_userInput == INVALID_SOCKET) return;
    std::string hdr;
    PbInt(hdr, 1, ++g_inputCallId);
    PbBytes(hdr, 4, userInputMessage);
    SendFrame(g_userInput, hdr);
    g_inputSent++;
}

// Frames of the headless guest: our HWC2 (device/microsoft/wsa_x86_64/headless-hwc) connects to
// vsock 50100 and sends every presented client target — a 32-byte header ("WSAF", version, width, height, stride,
// DRM format, frame number) and height * stride bytes. Shown in a plain window, scaled to its client area. 15.10.2026.
static const unsigned kFramePort = 50100;
static std::mutex g_frameLock;
static std::vector<unsigned char> g_frame;  // BGRX rows of g_frameW * 4 bytes
static unsigned g_frameW = 0, g_frameH = 0;
static unsigned long long g_frameNo = 0, g_framesShown = 0;
static HWND g_frameWnd = nullptr;

#pragma pack(push, 1)
struct FrameHeader { unsigned magic, version, width, height, stride, format; unsigned long long frame; };
// Task windows (15.10.2026): the HWC2 also composes every task on its own (layers tagged com.microsoft.wsa.taskid by
// SurfaceFlinger's patch 0006) and sends a task list ("WSAL" + TaskRect each) and a picture per changed task ("WSAT").
// Each task gets its own window here; its input goes to the guest with targetTaskId (the bridge brings the task to
// front on Down), coordinates = the task's rectangle on the guest display + the point in the window.
struct TaskHeader { unsigned magic, version; int taskId, x, y; unsigned width, height, stride, format, reserved; unsigned long long frame; };
struct TaskListHeader { unsigned magic, version, count, reserved; unsigned long long frame; };
struct TaskRect { int taskId, x, y, width, height; };
#pragma pack(pop)
static const unsigned kMagicFrame = 0x46415357, kMagicTask = 0x54415357, kMagicList = 0x4C415357;
static const UINT WM_APP_SYNC_TASKS = WM_APP + 1;

struct TaskWin
{
    int x = 0, y = 0, w = 0, h = 0;            // rectangle on the guest display (latest list or picture)
    std::vector<unsigned char> bgrx;           // latest picture, pw * ph BGRX
    unsigned pw = 0, ph = 0;
    unsigned long long pictures = 0;
    HWND hwnd = nullptr;                       // created/resized/destroyed by the window thread (SyncTaskWindows)
    int shownW = 0, shownH = 0, shownX = 0, shownY = 0;
    bool listed = true;
};
static std::map<int, TaskWin> g_tasks;         // under g_frameLock

// Shell channel (WsaShellBridge ShellBridge, proto shell.proto, docs/wsa-shell-protocol.md): the guest announces the
// tasks that get a host window (CreateTopLevelWindow / UpdateClientWindow — standard activities only, so no window
// for the home task) and removes them (RemoveWindow); the host asks to close (RemoveWindow), move (UpdateAndroidWindow)
// and focus (SetFocusedWindow) a task. Once the channel is up, only announced tasks get a window. 15.10.2026.
struct ShellTask { std::string label, package; };
static std::map<int, ShellTask> g_shellTasks;  // under g_frameLock
static bool g_shellUp = false;                 // under g_frameLock
static std::mutex g_shellLock;
static SOCKET g_shell = INVALID_SOCKET;
static unsigned long long g_shellCallId = 0;
static const UINT WM_APP_MINIMIZE_TASK = WM_APP + 2;
static const UINT WM_APP_RESTORE_TASK = WM_APP + 5;

// Notification channel (WsaShellBridge NotificationBridge, proto notification.proto): an Android notification
// (SendNotification) or a Toast (SystemUI patch 0012 hands it to the bridge) is shown as a Windows toast (ShowToast
// below), or, when WinRT toasts are unavailable, as a balloon of the host's notification-area icon; a click on it goes
// back as Activation{action=Launch}, which sends the notification's PendingIntent. 15.10.2026.
static const UINT WM_APP_TRAY = WM_APP + 3;
static std::mutex g_notifLock;
static SOCKET g_notif = INVALID_SOCKET;
static unsigned long long g_notifCallId = 0;
struct NotifKey { std::string package, tag; long long id = 0; bool valid = false; };
static NotifKey g_lastNotif;  // the notification of the balloon on screen, under g_notifLock
static bool g_trayAdded = false;

// Intents channel, guest -> host LaunchUri (a link the guest has no app for, WsaShellBridge IntentsBridge): opened in
// Windows by its default handler. The URI comes from the guest, so only http, https and mailto are opened. The command
// port's "uris off" turns opening into a log line (self-tests without a browser popping up on the desktop). 15.10.2026.
static bool g_openUris = true;

// Clipboard channel (WsaShellBridge ClipboardBridge, proto clipboard.proto): the bytes go through named sections the
// guest sees as /mnt/shared_mem/<name> (the "shared_mem sections" share: object directory WSL\<vm id>\shared_mem of
// the session's BaseNamedObjects). Guest copy: RequestSharedMemory -> a fresh 16 MiB section; SetClientClipboardContent
// -> its Text/Html/Uri/Bitmap items into the Windows clipboard (CF_UNICODETEXT, "HTML Format", "PNG"). Windows copy
// (WM_CLIPBOARDUPDATE, not our own write): text, the HTML fragment, a picture (PNG, else CF_DIB encoded to PNG) and
// files (CF_HDROP, as File items "<guid>.<ext>"; the bytes go by the FileSystem channel's ProvideContentFile) into a
// fresh section, SetServerClipboardContent to the guest. The command port's "clipput" sends from a file the same way.
// Off until "clipboard on" in settings.txt or "clip on" on the command port — the host does not touch the owner's
// clipboard unasked. 15.10.2026.
static const UINT WM_APP_CLIP_SET = WM_APP + 4;
static const DWORD kClipSection = 16u << 20;
static bool g_clipOn = false;

// The "clipboard" line of settings.txt, applied when it changes (so "clip on|off" of the command port holds meanwhile).
static void ApplyClipSetting()
{
    static int applied = -1;
    if (g_clipSetting == applied) return;
    applied = g_clipSetting;
    if (g_clipSetting >= 0) g_clipOn = g_clipSetting == 1, std::printf("clipboard: %s (settings.txt)\n", g_clipOn ? "on" : "off");
}
static std::mutex g_clipLock;
static SOCKET g_clip = INVALID_SOCKET;
static unsigned long long g_clipCallId = 0, g_clipSections = 0;
static std::map<std::string, HANDLE> g_clipMaps;  // section name -> handle (the last few)
static DWORD g_clipOwnSeq = 0;                    // clipboard sequence number after our own write
struct ClipItems { std::string text, html, uri, png; };

static void SendShell(const std::string& shellMessage)
{
    std::lock_guard<std::mutex> l(g_shellLock);
    if (g_shell == INVALID_SOCKET) return;
    std::string hdr;
    PbInt(hdr, 1, 100000 + ++g_shellCallId);  // host call ids: away from the guest's own counter
    PbBytes(hdr, 4, shellMessage);
    SendFrame(g_shell, hdr);
}

static std::string ShellOf(int type, int field, const std::string& sub)
{
    std::string m;
    PbInt(m, 1, type);
    PbBytes(m, field, sub);
    return m;
}

static void ShellRemoveTask(int task)
{
    std::string r;
    PbInt(r, 1, (unsigned)task);
    PbInt(r, 2, 0);  // force=false: removeTask, not forceStopPackage
    SendShell(ShellOf(5, 6, r));
    RcmLog("shell: SENT RemoveWindow task " + std::to_string(task));
}

static void ShellFocusTask(int task)
{
    std::string f;
    PbInt(f, 1, (unsigned)task);
    PbInt(f, 2, 1);
    SendShell(ShellOf(3, 4, f));
    RcmLog("shell: SENT SetFocusedWindow task " + std::to_string(task));
}

// The user minimized (or restored) the task's window: the guest moves the task back (or to front), reorder(toTop=!minimized).
static void ShellMinimizedTask(int task, bool minimized)
{
    std::string m;
    PbInt(m, 1, (unsigned)task);
    PbInt(m, 2, minimized ? 1 : 0);
    SendShell(ShellOf(7, 8, m));
    RcmLog("shell: SENT OnWindowMinimized task " + std::to_string(task) + (minimized ? " minimized" : " restored"));
}

static void ShellMoveTask(int task, int left, int top, int w, int h)
{
    if (left < 0) left = 0;
    if (top < 0) top = 0;
    std::string b, wi, u;
    PbInt(b, 1, (unsigned)left);
    PbInt(b, 2, (unsigned)top);
    PbInt(b, 3, (unsigned)(left + w));
    PbInt(b, 4, (unsigned)(top + h));
    PbInt(wi, 1, (unsigned)task);
    PbBytes(wi, 3, b);
    PbInt(wi, 10, 1);  // Freeform
    PbBytes(u, 1, wi);
    SendShell(ShellOf(2, 3, u));
    RcmLog("shell: SENT UpdateAndroidWindow task " + std::to_string(task) + " " + std::to_string(left) + "," + std::to_string(top) + " " +
           std::to_string(w) + "x" + std::to_string(h));
}

// A guest request on the Shell channel: what it means for the task windows.
static void ShellFromGuest(const std::string& shellMessage)
{
    std::map<int, unsigned long long> ints;
    auto parts = PbFields(shellMessage, &ints);
    unsigned long long type = ints.count(1) ? ints[1] : 0;
    auto windowInfo = [](const std::string& wi, int& task, ShellTask& st) {
        std::map<int, unsigned long long> wints;
        auto w = PbFields(wi, &wints);
        task = wints.count(1) ? (int)wints[1] : -1;
        st.package = w[2];
        st.label = w[5];
    };
    int task = -1;
    ShellTask st;
    if (type == 1 || type == 6)  // CreateTopLevelWindow{windowInfo=1} at field 2, UpdateClientWindow{info=1} at field 7
        windowInfo(PbFields(parts[type == 1 ? 2 : 7])[1], task, st);
    else if (type == 3 || type == 5 || type == 9)  // SetFocusedWindow{taskId} at 4, RemoveWindow{taskId} at 6, MinimizeClientWindow{taskId} at 10
    {
        std::map<int, unsigned long long> r;
        PbFields(parts[type == 3 ? 4 : type == 5 ? 6 : 10], &r);
        task = r.count(1) ? (int)r[1] : -1;
    }
    if (task < 0) return;
    HWND minimize = nullptr, restore = nullptr;
    {
        std::lock_guard<std::mutex> l(g_frameLock);
        if (type == 1 || type == 6)
        {
            ShellTask& t = g_shellTasks[task];
            if (!st.label.empty()) t.label = st.label;
            if (!st.package.empty()) t.package = st.package;
        }
        else if (type == 5)
            g_shellTasks.erase(task);
        else if (type == 9 || type == 3)
        {
            auto it = g_tasks.find(task);
            if (it != g_tasks.end()) (type == 9 ? minimize : restore) = it->second.hwnd;
        }
    }
    if (type == 3)  // the task came to front in the guest: a minimized window comes back, without taking the focus
    {
        if (restore && IsIconic(restore)) PostMessageW(restore, WM_APP_RESTORE_TASK, 0, 0);
        return;
    }
    if (type != 9) std::printf("shell: %s task %d%s\n", type == 1 ? "create" : type == 6 ? "update" : "remove", task, st.label.empty() ? "" : (" \"" + st.label + "\"").c_str());
    if (minimize) PostMessageW(minimize, WM_APP_MINIMIZE_TASK, 0, 0);
    if (g_frameWnd) PostMessageW(g_frameWnd, WM_APP_SYNC_TASKS, 0, 0);
}

static NOTIFYICONDATAW TrayData()
{
    NOTIFYICONDATAW n{};
    n.cbSize = sizeof n;
    n.hWnd = g_frameWnd;
    n.uID = 1;
    return n;
}

// On the window thread, once: the icon the balloons come from.
static void TrayAdd()
{
    NOTIFYICONDATAW n = TrayData();
    n.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
    n.uCallbackMessage = WM_APP_TRAY;
    n.hIcon = LoadIcon(nullptr, IDI_APPLICATION);
    wcscpy_s(n.szTip, L"WSA (wsa-reborn-host)");
    g_trayAdded = Shell_NotifyIconW(NIM_ADD, &n) != FALSE;
    std::printf("notification: tray icon %s\n", g_trayAdded ? "added" : "failed");
    n.uVersion = NOTIFYICON_VERSION_4;
    Shell_NotifyIconW(NIM_SETVERSION, &n);
}

static void ShowBalloon(const std::string& title, const std::string& text)
{
    if (!g_trayAdded) return;
    NOTIFYICONDATAW n = TrayData();
    n.uFlags = NIF_INFO;
    n.dwInfoFlags = NIIF_INFO | NIIF_NOSOUND;
    wcsncpy_s(n.szInfoTitle, Widen(title.empty() ? "Android" : title).c_str(), _TRUNCATE);
    wcsncpy_s(n.szInfo, Widen(text.empty() ? " " : text).c_str(), _TRUNCATE);
    BOOL ok = Shell_NotifyIconW(NIM_MODIFY, &n);
    std::printf("notification: balloon %s\n", ok ? "shown" : "failed");
}

// The balloon of a notification was clicked: Activation{Launch} for it.
static void NotifActivate()
{
    std::lock_guard<std::mutex> l(g_notifLock);
    if (g_notif == INVALID_SOCKET || !g_lastNotif.valid) return;
    std::string aid, act, m, hdr;
    PbBytes(aid, 1, "host-" + std::to_string(GetTickCount64()));
    PbBytes(act, 1, "Launch");
    PbInt(m, 1, 2);  // Activation
    PbBytes(m, 2, aid);
    PbBytes(m, 3, g_lastNotif.package);
    PbInt(m, 4, (unsigned long long)g_lastNotif.id);
    PbBytes(m, 9, act);
    PbBytes(m, 11, g_lastNotif.tag);
    PbInt(hdr, 1, 200000 + ++g_notifCallId);
    PbBytes(hdr, 4, m);
    SendFrame(g_notif, hdr);
    RcmLog("notification: SENT Activation Launch " + g_lastNotif.package + " id " + std::to_string(g_lastNotif.id));
}

static void IntentFromGuest(const std::string& message)
{
    std::map<int, unsigned long long> ints;
    auto parts = PbFields(message, &ints);
    if (!ints.count(1) || ints[1] != 1) return;  // LaunchUri only
    std::string uri = PbFields(parts[4])[1];
    std::string lower = uri;
    for (char& ch : lower) ch = (char)tolower((unsigned char)ch);
    bool allowed = lower.rfind("http://", 0) == 0 || lower.rfind("https://", 0) == 0 || lower.rfind("mailto:", 0) == 0;
    const char* what = !allowed ? "refused (scheme)" : !g_openUris ? "not opened (uris off)" : "opened";
    if (allowed && g_openUris) ShellExecuteW(nullptr, L"open", Widen(uri).c_str(), nullptr, nullptr, SW_SHOWNORMAL);
    std::printf("intent: LaunchUri %s — %s\n", uri.c_str(), what);
    RcmLog("intent: LaunchUri " + uri + " " + what);
}


// Apps channel (WsaShellBridge AppsBridge, proto apps.proto, docs/wsa-shell-protocol.md "The Apps channel"): the guest
// reports apps installed, updated and removed (Status) and answers GetList, which the host asks for when the channel
// opens. A launchable app gets an icon (its PNG inside an .ico) and a shortcut in apps\ next to the host — not in the
// Start menu, which is the owner's; the shortcut runs "wsa-reborn-host --launch <package>", and that asks the running
// host to send Intents LaunchPackage{MainLaunch}. 15.10.2026.
static std::mutex g_appsLock;
static SOCKET g_apps = INVALID_SOCKET, g_intents = INVALID_SOCKET;  // under g_appsLock
static unsigned long long g_appsCallId = 0;
struct AppEntry { std::string label; bool launchable = false; };
static std::map<std::string, AppEntry> g_appList;  // under g_appsLock

// All occurrences of a bytes field (PbFields keeps only the last one of a repeated field).
static std::vector<std::string> PbRepeated(const std::string& m, int wanted)
{
    std::vector<std::string> out;
    size_t i = 0;
    auto varint = [&](unsigned long long& v) { v = 0; for (int sh = 0; i < m.size(); sh += 7) { unsigned char b = m[i++]; v |= (unsigned long long)(b & 0x7f) << sh; if (!(b & 0x80)) return true; } return false; };
    while (i < m.size())
    {
        unsigned long long key, v;
        if (!varint(key)) break;
        int field = (int)(key >> 3), type = (int)(key & 7);
        if (type == 0) { if (!varint(v)) break; }
        else if (type == 2) { if (!varint(v) || i + v > m.size()) break; if (field == wanted) out.push_back(m.substr(i, (size_t)v)); i += (size_t)v; }
        else if (type == 1) i += 8;
        else if (type == 5) i += 4;
        else break;
    }
    return out;
}

static bool SafePackage(const std::string& p)
{
    if (p.empty() || p.size() > 200) return false;
    for (char ch : p) if (!isalnum((unsigned char)ch) && ch != '.' && ch != '_') return false;
    return true;
}

static std::wstring FullPath(const std::wstring& rel)
{
    wchar_t full[MAX_PATH];
    return GetFullPathNameW(rel.c_str(), MAX_PATH, full, nullptr) ? std::wstring(full) : rel;
}

// A shortcut to this exe with the given arguments; icon empty — the exe's own.
static void SaveShortcut(const std::wstring& lnk, const std::wstring& args, const std::wstring& description, const std::wstring& icon, int iconIndex)
{
    wchar_t exe[MAX_PATH];
    GetModuleFileNameW(nullptr, exe, MAX_PATH);
    IShellLinkW* link = nullptr;
    if (FAILED(CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&link)))) { std::printf("apps: cannot create a shortcut\n"); return; }
    link->SetPath(exe);
    link->SetArguments(args.c_str());
    link->SetDescription(description.c_str());
    if (!icon.empty()) link->SetIconLocation(icon.c_str(), iconIndex);
    IPersistFile* file = nullptr;
    if (SUCCEEDED(link->QueryInterface(IID_PPV_ARGS(&file))))
    {
        if (FAILED(file->Save(lnk.c_str(), TRUE))) std::printf("apps: cannot save %ls\n", lnk.c_str());
        file->Release();
    }
    link->Release();
}

static void AppShortcut(const std::string& package, const std::string& label, const std::string& png, bool add)
{
    const std::wstring base = FullPath(L"apps\\" + Widen(package)), ico = base + L".ico", lnk = base + L".lnk";
    if (!add) { DeleteFileW(lnk.c_str()); DeleteFileW(ico.c_str()); return; }
    CreateDirectoryW(FullPath(L"apps").c_str(), nullptr);
    if (png.size() > 24)
    {
        // An .ico with one PNG image (Windows Vista and later): ICONDIR, one ICONDIRENTRY, the PNG as is.
        const unsigned char* ihdr = (const unsigned char*)png.data() + 16;
        unsigned w = ihdr[0] << 24 | ihdr[1] << 16 | ihdr[2] << 8 | ihdr[3], h = ihdr[4] << 24 | ihdr[5] << 16 | ihdr[6] << 8 | ihdr[7];
        std::string f;
        auto u16 = [&](unsigned v) { f += (char)(v & 255); f += (char)(v >> 8 & 255); };
        auto u32 = [&](unsigned v) { u16(v & 0xffff); u16(v >> 16); };
        u16(0), u16(1), u16(1);
        f += (char)(w >= 256 ? 0 : w), f += (char)(h >= 256 ? 0 : h), f += '\0', f += '\0';
        u16(1), u16(32), u32((unsigned)png.size()), u32(6 + 16);
        f += png;
        std::ofstream(ico, std::ios::binary).write(f.data(), (std::streamsize)f.size());
    }
    SaveShortcut(lnk, L"--launch " + Widen(package), Widen(label), png.size() > 24 ? ico : std::wstring(), 0);
}

// The settings window's shortcut next to the apps' ones (WSA has its settings app in Start), made by the client when
// it is missing; the icon is the gear of shell32.dll. 16.10.2026.
static void SettingsShortcut()
{
    const std::wstring lnk = FullPath(L"apps\\Subsystem settings.lnk");
    if (GetFileAttributesW(lnk.c_str()) != INVALID_FILE_ATTRIBUTES) return;
    CreateDirectoryW(FullPath(L"apps").c_str(), nullptr);
    wchar_t shell32[MAX_PATH];
    ExpandEnvironmentStringsW(L"%SystemRoot%\\System32\\shell32.dll", shell32, MAX_PATH);
    SaveShortcut(lnk, L"--settings", L"Memory and performance, clipboard, turning the subsystem off", shell32, 314);
}

// action: Status.Action (0 ADDED, 1 REMOVED, 2 REPLACED) or -1 for an entry of GetList.
static void AppFromGuest(const std::string& app, int action)
{
    std::map<int, unsigned long long> ints;
    auto f = PbFields(app, &ints);
    const std::string package = f[1], label = f.count(2) ? f[2] : f[1];
    const bool launchable = ints.count(6) && ints[6];
    if (!SafePackage(package)) { std::printf("apps: odd package name, ignored\n"); return; }
    const std::string png = f.count(5) ? PbFields(f[5])[4] : std::string();
    {
        std::lock_guard<std::mutex> l(g_appsLock);
        if (action == 1) g_appList.erase(package); else g_appList[package] = {label, launchable};
    }
    AppShortcut(package, label, png, action != 1 && launchable);
    const char* what = action == -1 ? "listed" : action == 0 ? "added" : action == 1 ? "removed" : "replaced";
    std::printf("apps: %s %s \"%s\"%s%s\n", what, package.c_str(), label.c_str(), action == 1 || launchable ? "" : " (not launchable)", png.empty() ? "" : (", icon " + std::to_string(png.size()) + " bytes").c_str());
}

static void AppsSend(int type, int field, const std::string& sub)
{
    std::string aid, m, hdr;
    PbBytes(aid, 1, "host-" + std::to_string(GetTickCount64()));
    PbInt(m, 1, type);
    PbBytes(m, 2, aid);
    if (field) PbBytes(m, field, sub);
    std::lock_guard<std::mutex> l(g_appsLock);
    if (g_apps == INVALID_SOCKET) return;
    PbInt(hdr, 1, 300000 + ++g_appsCallId);
    PbBytes(hdr, 4, m);
    SendFrame(g_apps, hdr);
    RcmLog("apps: SENT type " + std::to_string(type));
}

static void AppsFromGuest(SOCKET h, unsigned long long callId, bool reply, const std::string& message)
{
    std::map<int, unsigned long long> ints;
    auto parts = PbFields(message, &ints);
    const unsigned long long type = ints.count(1) ? ints[1] : 0;
    if (type == 1 && reply)  // GetList
    {
        auto apps = PbRepeated(parts[3], 1);
        for (auto& a : apps) AppFromGuest(a, -1);
        std::printf("apps: list of %zu apps\n", apps.size());
    }
    else if (type == 2 && !reply)  // Status
    {
        std::map<int, unsigned long long> si;
        auto st = PbFields(parts[4], &si);
        AppFromGuest(st[1], si.count(2) ? (int)si[2] : 0);
    }
    else if (type == 4 || (type == 3 && reply))  // UninstallResult, or the answer to UninstallRequest
    {
        std::map<int, unsigned long long> ri;
        PbFields(type == 4 ? PbFields(parts[7])[2] : parts[5], &ri);
        std::printf("apps: uninstall %s\n", ri.count(1) && ri[1] ? "succeeded" : "failed or pending");
    }
    // The reply to Status carries the compatibility settings for the app: none here, the message comes back as is.
    if (!reply) SendFrame(h, Reply(callId, message));
}

// Intents LaunchPackage{MainLaunch}: the guest starts the app's launcher activity.
static std::string AppLaunch(const std::string& package)
{
    if (!SafePackage(package)) return "error: bad package name\n";
    std::string aid, lp, m, hdr;
    PbBytes(aid, 1, "host-" + std::to_string(GetTickCount64()));
    PbBytes(lp, 1, package);
    PbInt(lp, 2, 0);
    PbInt(m, 1, 2);  // LaunchPackage
    PbBytes(m, 2, aid);
    PbBytes(m, 5, lp);
    std::lock_guard<std::mutex> l(g_appsLock);
    if (g_intents == INVALID_SOCKET) return "error: no Intents channel\n";
    PbInt(hdr, 1, 400000 + ++g_appsCallId);
    PbBytes(hdr, 4, m);
    SendFrame(g_intents, hdr);
    RcmLog("intent: SENT LaunchPackage " + package);
    return "ok\n";
}

// A fresh section for one exchange; the oldest of more than four is closed.
static std::string ClipNewSection()
{
    std::string name = "wsa-host-clip-" + std::to_string(GetCurrentProcessId()) + "-" + std::to_string(++g_clipSections);
    std::wstring full = std::wstring(L"Local\\WSL\\") + kVmId + L"\\shared_mem\\" + Widen(name);
    HANDLE h = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0, kClipSection, full.c_str());
    if (!h) { RcmLog("clipboard: CreateFileMapping " + name + " failed " + std::to_string(GetLastError())); return {}; }
    g_clipMaps[name] = h;
    while (g_clipMaps.size() > 4)
    {
        auto oldest = g_clipMaps.begin();
        for (auto it = g_clipMaps.begin(); it != g_clipMaps.end(); ++it)
            if (std::stoull(it->first.substr(it->first.rfind('-') + 1)) < std::stoull(oldest->first.substr(oldest->first.rfind('-') + 1))) oldest = it;
        CloseHandle(oldest->second);
        g_clipMaps.erase(oldest);
    }
    return name;
}

static std::string ClipSharedMemory(const std::string& name)
{
    std::string sm;
    PbBytes(sm, 1, name);
    PbInt(sm, 2, kClipSection);
    return sm;
}

// CF_HTML: a header of byte offsets in front of the fragment.
static std::string CfHtml(const std::string& fragment)
{
    const std::string pre = "<html><body><!--StartFragment-->", post = "<!--EndFragment--></body></html>";
    char header[200];
    const char* fmt = "Version:0.9\r\nStartHTML:%010zu\r\nEndHTML:%010zu\r\nStartFragment:%010zu\r\nEndFragment:%010zu\r\n";
    size_t hl = (size_t)std::snprintf(header, sizeof header, fmt, (size_t)0, (size_t)0, (size_t)0, (size_t)0);
    size_t startHtml = hl, startFrag = hl + pre.size(), endFrag = startFrag + fragment.size(), endHtml = endFrag + post.size();
    std::snprintf(header, sizeof header, fmt, startHtml, endHtml, startFrag, endFrag);
    return header + pre + fragment + post;
}

static void ClipPut(UINT format, const void* data, size_t size)
{
    HGLOBAL g = GlobalAlloc(GMEM_MOVEABLE, size);
    if (!g) return;
    memcpy(GlobalLock(g), data, size);
    GlobalUnlock(g);
    if (!SetClipboardData(format, g)) GlobalFree(g);
}

// On the window thread (the clipboard needs an owner window).
static void ClipSetWindows(const ClipItems& c)
{
    if (!OpenClipboard(g_frameWnd)) { RcmLog("clipboard: OpenClipboard failed"); return; }
    EmptyClipboard();
    std::string text = !c.text.empty() ? c.text : c.uri;
    if (!text.empty()) { std::wstring w = Widen(text); ClipPut(CF_UNICODETEXT, w.c_str(), (w.size() + 1) * sizeof(wchar_t)); }
    if (!c.html.empty()) { std::string h = CfHtml(c.html); ClipPut(RegisterClipboardFormatW(L"HTML Format"), h.c_str(), h.size() + 1); }
    if (!c.png.empty()) ClipPut(RegisterClipboardFormatW(L"PNG"), c.png.data(), c.png.size());
    CloseClipboard();
    g_clipOwnSeq = GetClipboardSequenceNumber();
}

static void ClipFromGuest(unsigned long long callId, const std::string& message)
{
    std::map<int, unsigned long long> ints;
    auto parts = PbFields(message, &ints);
    unsigned long long type = ints.count(1) ? ints[1] : 0;
    std::string reply;
    if (type == 3)  // RequestSharedMemory: a section to write to
    {
        std::lock_guard<std::mutex> l(g_clipLock);
        std::string name = g_clipOn ? ClipNewSection() : std::string();
        PbInt(reply, 1, 3);
        PbBytes(reply, 2, parts[2]);
        if (!name.empty())
        {
            std::string req;
            PbBytes(req, 1, ClipSharedMemory(name));
            PbBytes(reply, 4, req);
        }
        RcmLog("clipboard: RequestSharedMemory -> " + (name.empty() ? std::string("none (clip off)") : name));
    }
    else if (type == 2)  // SetClientClipboardContent: copied in Android
    {
        auto set = PbFields(parts[5]);
        std::string name = PbFields(set[1])[1];
        ClipItems c;
        bool ok = false;
        {
            std::lock_guard<std::mutex> l(g_clipLock);
            auto it = g_clipMaps.find(name);
            const unsigned char* base = it == g_clipMaps.end() ? nullptr : (const unsigned char*)MapViewOfFile(it->second, FILE_MAP_READ, 0, 0, 0);
            if (base && g_clipOn)
            {
                // ClipboardData{repeated item=1}: walk the repeated field by hand (PbFields keeps the last only).
                const std::string& data = set[2];
                size_t i = 0;
                while (i < data.size())
                {
                    unsigned long long key = 0, len = 0;
                    for (int sh = 0; i < data.size(); sh += 7) { unsigned char b = data[i++]; key |= (unsigned long long)(b & 0x7f) << sh; if (!(b & 0x80)) break; }
                    for (int sh = 0; i < data.size(); sh += 7) { unsigned char b = data[i++]; len |= (unsigned long long)(b & 0x7f) << sh; if (!(b & 0x80)) break; }
                    if ((key & 7) != 2 || i + len > data.size()) break;
                    std::map<int, unsigned long long> f;
                    PbFields(data.substr(i, (size_t)len), &f);
                    i += (size_t)len;
                    unsigned long long size = f[1], off = f[2], kind = f[3];
                    if (off + size > kClipSection) continue;
                    std::string bytes((const char*)base + off, (size_t)size);
                    if (kind == 1) c.text = bytes; else if (kind == 2) c.html = bytes; else if (kind == 3) c.uri = bytes; else if (kind == 4) c.png = bytes;
                }
                ok = true;
            }
            if (base) UnmapViewOfFile(base);
        }
        if (ok && g_frameWnd) SendMessageW(g_frameWnd, WM_APP_CLIP_SET, 0, (LPARAM)&c);
        reply = message;
        std::string result;
        PbInt(result, 1, ok);
        PbBytes(reply, 3, result);
        RcmLog("clipboard: SetClientClipboardContent " + name + (ok ? " -> Windows: text " + std::to_string(c.text.size()) + " B, html " + std::to_string(c.html.size()) +
                                                                          " B, uri " + std::to_string(c.uri.size()) + " B, png " + std::to_string(c.png.size()) + " B"
                                                                    : " ignored (clip off or unknown section)"));
        std::printf("clipboard: from Android%s\n", ok ? "" : " ignored");
    }
    else
        return;
    std::lock_guard<std::mutex> l(g_clipLock);
    if (g_clip != INVALID_SOCKET) SendFrame(g_clip, Reply(callId, reply));
}

// Windows clipboard changed (window thread): its text to the guest.
// A bitmap from the clipboard (CF_DIB, when nobody put "PNG") as PNG bytes, through GDI+.
static std::string DibToPng(const BITMAPINFO* bi, size_t size)
{
    static ULONG_PTR token = 0;
    if (!token) { Gdiplus::GdiplusStartupInput in; if (Gdiplus::GdiplusStartup(&token, &in, nullptr) != Gdiplus::Ok) return {}; }
    const BITMAPINFOHEADER& h = bi->bmiHeader;
    size_t colors = h.biClrUsed ? h.biClrUsed : (h.biBitCount <= 8 ? 1u << h.biBitCount : 0);
    size_t header = h.biSize + colors * sizeof(RGBQUAD) + (h.biCompression == BI_BITFIELDS && h.biSize == sizeof(BITMAPINFOHEADER) ? 12 : 0);
    if (header >= size) return {};
    Gdiplus::Bitmap bmp(bi, (BYTE*)bi + header);
    IStream* s = nullptr;
    if (bmp.GetLastStatus() != Gdiplus::Ok || FAILED(CreateStreamOnHGlobal(nullptr, TRUE, &s))) return {};
    static const CLSID kPng = {0x557cf406, 0x1a04, 0x11d3, {0x9a, 0x73, 0x00, 0x00, 0xf8, 0x1e, 0xf3, 0x2e}};
    std::string out;
    if (bmp.Save(s, &kPng, nullptr) == Gdiplus::Ok)
    {
        STATSTG st{};
        s->Stat(&st, STATFLAG_NONAME);
        out.resize((size_t)st.cbSize.QuadPart);
        LARGE_INTEGER zero{};
        s->Seek(zero, STREAM_SEEK_SET, nullptr);
        ULONG got = 0;
        s->Read(out.data(), (ULONG)out.size(), &got);
        out.resize(got);
    }
    s->Release();
    return out;
}

// The fragment of a CF_HTML ("HTML Format") block: between its StartFragment/EndFragment byte offsets.
static std::string CfHtmlFragment(const std::string& cf)
{
    auto num = [&](const char* key) { size_t i = cf.find(key); return i == std::string::npos ? -1LL : atoll(cf.c_str() + i + strlen(key)); };
    long long a = num("StartFragment:"), b = num("EndFragment:");
    if (a < 0 || b < a || (size_t)b > cf.size()) return cf;
    return cf.substr((size_t)a, (size_t)(b - a));
}

// Windows files offered to the guest by the clipboard (File items): "<guid>.<ext>" -> path, served by the FileSystem
// channel's ProvideContentFile. Under g_clipLock.
static std::map<std::string, std::wstring> g_clipFiles;

// One SetServerClipboardContent: the items one after another in a fresh section, in the order the bridge wants them
// (Html before Text — Text is dropped when Html is there, Html carries its plain text itself), files as File items.
static void ClipSendToGuest(const ClipItems& c, const std::vector<std::wstring>& files)
{
    std::vector<std::pair<int, std::string>> items;
    if (!c.html.empty()) items.push_back({2, c.html});
    if (!c.text.empty()) items.push_back({1, c.text});
    if (!c.png.empty()) items.push_back({4, c.png});
    std::lock_guard<std::mutex> l(g_clipLock);
    for (auto& path : files)
    {
        DWORD attr = GetFileAttributesW(path.c_str());
        if (attr == INVALID_FILE_ATTRIBUTES || (attr & FILE_ATTRIBUTE_DIRECTORY)) continue;
        GUID g;
        wchar_t s[64];
        CoCreateGuid(&g);
        StringFromGUID2(g, s, 64);
        std::wstring id(s + 1, wcslen(s) - 2);
        const wchar_t* ext = PathFindExtensionW(path.c_str());
        if (*ext && wcslen(ext) < 16) id += ext;
        for (wchar_t& ch : id) ch = towlower(ch);
        std::string name = Narrow(id.c_str());
        g_clipFiles[name] = path;
        items.push_back({5, name});
    }
    size_t total = 0;
    for (auto& it : items) total += it.second.size();
    if (items.empty() || total > kClipSection || g_clip == INVALID_SOCKET) return;
    std::string name = ClipNewSection();
    if (name.empty()) return;
    char* view = (char*)MapViewOfFile(g_clipMaps[name], FILE_MAP_WRITE, 0, 0, 0);
    if (!view) return;
    std::string data, set, aid, m, hdr, what;
    size_t off = 0;
    for (auto& it : items)
    {
        memcpy(view + off, it.second.data(), it.second.size());
        std::string item;
        PbInt(item, 1, it.second.size());
        PbInt(item, 2, off);
        PbInt(item, 3, it.first);
        PbBytes(data, 1, item);
        off += it.second.size();
        static const char* kinds[] = {"", "text", "html", "uri", "png", "file"};
        what += std::string(" ") + kinds[it.first] + " " + std::to_string(it.second.size()) + " B";
    }
    UnmapViewOfFile(view);
    PbBytes(set, 1, ClipSharedMemory(name));
    PbBytes(set, 2, data);
    PbBytes(aid, 1, "host-clip-" + std::to_string(g_clipSections));
    PbInt(m, 1, 1);  // SetServerClipboardContent
    PbBytes(m, 2, aid);
    PbBytes(m, 5, set);
    PbInt(hdr, 1, 300000 + ++g_clipCallId);
    PbBytes(hdr, 4, m);
    SendFrame(g_clip, hdr);
    RcmLog("clipboard: SENT SetServerClipboardContent " + name + what);
    std::printf("clipboard: to Android,%s\n", what.c_str());
}

// The Windows clipboard changed (not by our own write): text, HTML, a picture (PNG, else CF_DIB) and files (CF_HDROP).
static void ClipToGuest()
{
    if (!g_clipOn || GetClipboardSequenceNumber() == g_clipOwnSeq) return;
    ClipItems c;
    std::vector<std::wstring> files;
    if (!OpenClipboard(g_frameWnd)) return;
    auto bytes = [](UINT format) {
        std::string out;
        if (HANDLE h = GetClipboardData(format))
            if (const char* p = (const char*)GlobalLock(h)) { out.assign(p, GlobalSize(h)); GlobalUnlock(h); }
        return out;
    };
    if (HANDLE h = GetClipboardData(CF_UNICODETEXT))
    {
        if (const wchar_t* w = (const wchar_t*)GlobalLock(h)) { c.text = Narrow(w); GlobalUnlock(h); }
    }
    if (UINT f = RegisterClipboardFormatW(L"HTML Format"); IsClipboardFormatAvailable(f))
    {
        std::string cf = bytes(f);
        cf.resize(strnlen(cf.c_str(), cf.size()));
        c.html = CfHtmlFragment(cf);
    }
    if (UINT f = RegisterClipboardFormatW(L"PNG"); IsClipboardFormatAvailable(f)) c.png = bytes(f);
    else if (IsClipboardFormatAvailable(CF_DIB))
    {
        std::string dib = bytes(CF_DIB);
        if (dib.size() > sizeof(BITMAPINFOHEADER)) c.png = DibToPng((const BITMAPINFO*)dib.data(), dib.size());
    }
    if (HANDLE h = GetClipboardData(CF_HDROP))
    {
        HDROP drop = (HDROP)h;
        UINT n = DragQueryFileW(drop, 0xFFFFFFFF, nullptr, 0);
        for (UINT i = 0; i < n && i < 32; i++)
        {
            wchar_t path[MAX_PATH * 4];
            if (DragQueryFileW(drop, i, path, ARRAYSIZE(path))) files.push_back(path);
        }
    }
    CloseClipboard();
    ClipSendToGuest(c, files);
}

// Windows toasts (WinRT, 15.10.2026): the balloon has neither buttons nor a reply line, so notifications go as real
// toasts under the host's own AppUserModelID (registered in HKCU\Software\Classes\AppUserModelId — what an unpackaged
// app needs to show toasts). Buttons become toast buttons, a button with RemoteInput gets a text box; a click comes
// back while the host runs (ToastNotification.Activated) as Activation{action=<button title>, replyKey, replyData}, the
// body — Activation{Launch}; swiping the toast away (Dismissed, UserCanceled) — Dismissal, and Android removes the
// notification. Unlike WsaClient, the toast arguments carry only our own index ("t=<toast tag>&a=<button>"), so a
// non-ASCII button title cannot get lost (docs/wsa-shell-protocol.md, "Кнопки уведомления"). CancelNotification from the
// guest removes the toast; the toasts of a previous run are cleared at the first notification. Without the WinRT
// notifier (registration failed) the balloon remains. To undo the registration: delete that registry key.
struct NotifEntry { std::string package, tag; long long id = 0; std::vector<std::pair<std::string, std::string>> actions; };  // (title, inputKey)
static std::map<std::string, NotifEntry> g_notifs;  // by toast tag, under g_notifLock
static std::string g_lastToast;                     // tag of the last notification shown, under g_notifLock
static const wchar_t kToastAumid[] = L"WsaReborn.Host";
static winrt::Windows::UI::Notifications::ToastNotifier g_toaster{nullptr};

static void ComThread()
{
    thread_local bool done = false;
    if (!done) { CoInitializeEx(nullptr, COINIT_MULTITHREADED); done = true; }
}

static bool ToastInit()
{
    static int state = 0;  // 0 not tried, 1 ok, -1 failed
    if (state) return state > 0;
    ComThread();
    HKEY k;
    std::wstring key = std::wstring(L"Software\\Classes\\AppUserModelId\\") + kToastAumid;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, key.c_str(), 0, nullptr, 0, KEY_SET_VALUE, nullptr, &k, nullptr) == ERROR_SUCCESS)
    {
        const wchar_t name[] = L"WSA (own host)";
        RegSetValueExW(k, L"DisplayName", 0, REG_SZ, (const BYTE*)name, sizeof name);
        RegCloseKey(k);
    }
    try
    {
        g_toaster = winrt::Windows::UI::Notifications::ToastNotificationManager::CreateToastNotifier(kToastAumid);
        // Toasts of a previous run stay in the notification center, but nobody answers their clicks any more.
        // History().Clear(aumid) leaves them (seen 15.10.2026); removing each by tag and group works (ours always have
        // the group "wsa"; an empty group is E_INVALIDARG). Cleanup failures must not cost the toasts themselves.
        auto history = winrt::Windows::UI::Notifications::ToastNotificationManager::History();
        try
        {
            for (const auto& t : history.GetHistory(kToastAumid))
                if (!t.Group().empty()) history.Remove(t.Tag(), t.Group(), kToastAumid);
        }
        catch (const winrt::hresult_error&) {}
        state = 1;
    }
    catch (const winrt::hresult_error& e)
    {
        std::printf("notification: no WinRT toasts (0x%08lx), balloons instead\n", (unsigned long)e.code());
        state = -1;
    }
    RcmLog(std::string("notification: WinRT toasts ") + (state > 0 ? "on" : "OFF"));
    return state > 0;
}

static std::wstring XmlEscape(const std::string& s)
{
    std::wstring out;
    for (wchar_t ch : Widen(s))
    {
        if (ch == L'&') out += L"&amp;";
        else if (ch == L'<') out += L"&lt;";
        else if (ch == L'>') out += L"&gt;";
        else if (ch == L'"') out += L"&quot;";
        else if (ch < 32 && ch != L'\n' && ch != L'\t') out += L' ';
        else out += ch;
    }
    return out;
}

// Activation{action, replyKey, replyData} to the guest; button -1 — the body (action=Launch).
static void NotifSendActivation(const NotifEntry& e, int button, const std::string& reply)
{
    std::string aid, act, m, hdr, action = "Launch", key;
    if (button >= 0 && button < (int)e.actions.size()) { action = e.actions[button].first; key = e.actions[button].second; }
    PbBytes(aid, 1, "host-" + std::to_string(GetTickCount64()));
    PbBytes(act, 1, action);
    if (!key.empty()) { PbBytes(act, 2, key); PbBytes(act, 3, reply); }
    PbInt(m, 1, 2);  // Activation
    PbBytes(m, 2, aid);
    PbBytes(m, 3, e.package);
    PbInt(m, 4, (unsigned long long)e.id);
    PbBytes(m, 9, act);
    PbBytes(m, 11, e.tag);
    PbInt(hdr, 1, 200000 + ++g_notifCallId);
    PbBytes(hdr, 4, m);
    if (g_notif != INVALID_SOCKET) SendFrame(g_notif, hdr);
    RcmLog("notification: SENT Activation \"" + action + "\"" + (key.empty() ? "" : " reply " + std::to_string(reply.size()) + " B") + " " + e.package + " id " + std::to_string(e.id));
}

static void NotifSendDismissal(const NotifEntry& e)
{
    std::string aid, m, hdr;
    PbBytes(aid, 1, "host-" + std::to_string(GetTickCount64()));
    PbInt(m, 1, 3);  // Dismissal
    PbBytes(m, 2, aid);
    PbBytes(m, 3, e.package);
    PbInt(m, 4, (unsigned long long)e.id);
    PbBytes(m, 11, e.tag);
    PbInt(hdr, 1, 200000 + ++g_notifCallId);
    PbBytes(hdr, 4, m);
    if (g_notif != INVALID_SOCKET) SendFrame(g_notif, hdr);
    RcmLog("notification: SENT Dismissal " + e.package + " id " + std::to_string(e.id));
}

// A click on the toast (or the command port's "naction"): "t=<tag>&a=<button>".
static void ToastActivated(const std::string& args, const std::string& reply)
{
    std::string tag;
    int button = -1;
    std::istringstream in(args);
    for (std::string kv; std::getline(in, kv, '&');)
    {
        if (kv.rfind("t=", 0) == 0) tag = kv.substr(2);
        else if (kv.rfind("a=", 0) == 0) button = atoi(kv.c_str() + 2);
    }
    std::lock_guard<std::mutex> l(g_notifLock);
    auto it = g_notifs.find(tag);
    if (it == g_notifs.end()) { RcmLog("notification: activation of unknown toast " + args); return; }
    NotifSendActivation(it->second, button, reply);
}

static void ToastDismissed(const std::string& tag)
{
    std::lock_guard<std::mutex> l(g_notifLock);
    auto it = g_notifs.find(tag);
    if (it == g_notifs.end()) return;
    NotifSendDismissal(it->second);
    g_notifs.erase(it);
}

// Shows (or replaces, same tag) a toast. toastMs > 0 — an Android Toast: no actions, expires after that time.
// extra — more elements for the toast's binding (pictures, progress); update — the same notification again: replace the
// toast silently, without popping it up once more (a progress bar updates often).
static bool ShowToast(const std::string& tag, const NotifEntry& e, const std::string& title, const std::string& text, int toastMs,
                      const std::wstring& extra = L"", bool update = false)
{
    using namespace winrt::Windows::UI::Notifications;
    if (!ToastInit()) return false;
    ComThread();
    std::wstring wtag = Widen(tag);
    std::wstring xml = L"<toast launch=\"t=" + wtag + L"&amp;a=-1\"><visual><binding template=\"ToastGeneric\"><text>" + XmlEscape(title) + L"</text>";
    if (!text.empty()) xml += L"<text>" + XmlEscape(text) + L"</text>";
    xml += extra + L"</binding></visual>";
    if (!e.actions.empty() && toastMs <= 0)
    {
        xml += L"<actions>";
        bool input = false;
        for (auto& a : e.actions)
            if (!a.second.empty() && !input) { xml += L"<input id=\"reply\" type=\"text\" placeHolderContent=\"" + XmlEscape(a.first) + L"\"/>"; input = true; }
        for (size_t i = 0; i < e.actions.size() && i < 5; i++)
            xml += L"<action content=\"" + XmlEscape(e.actions[i].first) + L"\" arguments=\"t=" + wtag + L"&amp;a=" + std::to_wstring(i) + L"\"" +
                   (e.actions[i].second.empty() ? L"" : L" hint-inputId=\"reply\"") + L"/>";
        xml += L"</actions>";
    }
    xml += L"<audio silent=\"true\"/></toast>";
    try
    {
        winrt::Windows::Data::Xml::Dom::XmlDocument doc;
        doc.LoadXml(xml);
        ToastNotification n(doc);
        n.Tag(wtag);
        n.Group(L"wsa");
        n.SuppressPopup(update);
        if (toastMs > 0) n.ExpirationTime(winrt::clock::now() + std::chrono::milliseconds(toastMs + 2000));
        else
        {
            n.Activated([](const ToastNotification& t, const winrt::Windows::Foundation::IInspectable& o) {
                auto a = o.as<ToastActivatedEventArgs>();
                std::string reply;
                if (auto ui = a.UserInput(); ui && ui.HasKey(L"reply")) reply = Narrow(winrt::unbox_value<winrt::hstring>(ui.Lookup(L"reply")).c_str());
                ToastActivated(Narrow(a.Arguments().c_str()), reply);
            });
            n.Dismissed([](const ToastNotification& t, const ToastDismissedEventArgs& a) {
                if (a.Reason() == ToastDismissalReason::UserCanceled) ToastDismissed(Narrow(t.Tag().c_str()));
            });
        }
        g_toaster.Show(n);
        return true;
    }
    catch (const winrt::hresult_error& err)
    {
        RcmLog("notification: toast failed 0x" + std::to_string((unsigned long)err.code()));
        return false;
    }
}

static void HideToast(const std::string& tag)
{
    if (!ToastInit()) return;
    ComThread();
    try { winrt::Windows::UI::Notifications::ToastNotificationManager::History().Remove(Widen(tag), L"wsa", kToastAumid); }
    catch (const winrt::hresult_error&) {}
}

// A guest request on the Notification channel. The toast tag is a short hash of the notification key, so an update of the
// same notification replaces its toast.
static void NotifFromGuest(const std::string& message)
{
    std::map<int, unsigned long long> ints, dints;
    auto parts = PbFields(message, &ints);
    unsigned long long type = ints.count(1) ? ints[1] : 0;
    if (type != 0 && type != 4 && type != 1) return;
    NotifEntry e;
    e.package = parts[3];
    e.tag = parts[11];
    e.id = ints.count(4) ? (long long)(int)ints[4] : 0;
    char hash[24];
    std::snprintf(hash, sizeof hash, "n%016llx", (unsigned long long)std::hash<std::string>()(e.package + "|" + std::to_string(e.id) + "|" + e.tag));
    std::string tag = type == 4 ? "toast" + std::to_string(GetTickCount64()) : hash;
    if (type == 1)  // CancelNotification: the toast goes away too
    {
        {
            std::lock_guard<std::mutex> l(g_notifLock);
            if (g_lastNotif.valid && g_lastNotif.package == e.package && g_lastNotif.id == e.id && g_lastNotif.tag == e.tag) g_lastNotif.valid = false;
            g_notifs.erase(tag);
        }
        HideToast(tag);
        RcmLog("notification: cancel " + e.package + " id " + std::to_string(e.id));
        return;
    }
    auto detail = PbFields(parts[6], &dints);
    std::string app = detail[1], title = detail[2], text = detail[3];
    for (auto& a : PbRepeated(message, 8))
    {
        auto f = PbFields(a);
        auto keys = PbRepeated(a, 2);
        e.actions.push_back({f[1], keys.empty() ? std::string() : keys[0]});
    }
    // Pictures go to notif\ next to the host (a toast takes images by file path); progress — a progress bar.
    std::wstring extra;
    auto image = PbFields(parts[10]);
    for (int f = 1; f <= 2; f++)
    {
        std::string png = PbFields(image[f])[4];
        if (png.size() < 8 || png.compare(1, 3, "PNG")) continue;
        CreateDirectoryW(FullPath(L"notif").c_str(), nullptr);
        std::wstring path = FullPath(L"notif\\" + Widen(tag) + (f == 1 ? L"-icon.png" : L"-image.png"));
        std::ofstream(path, std::ios::binary).write(png.data(), png.size());
        std::wstring uri = L"file:///" + path;
        for (wchar_t& ch : uri) if (ch == L'\\') ch = L'/';
        extra += f == 1 ? L"<image placement=\"appLogoOverride\" src=\"" + uri + L"\"/>" : L"<image src=\"" + uri + L"\"/>";
    }
    if (parts.count(7))
    {
        std::map<int, unsigned long long> pi;
        PbFields(parts[7], &pi);
        long long max = (int)pi[1], cur = (int)pi[2];
        if (pi[3]) extra += L"<progress value=\"indeterminate\" status=\"\"/>";
        else if (max > 0)
        {
            double v = cur < 0 ? 0 : cur > max ? 1 : (double)cur / max;
            wchar_t b[96];
            swprintf(b, 96, L"<progress value=\"%.3f\" valueStringOverride=\"%d%%\" status=\"\"/>", v, (int)(v * 100 + 0.5));
            extra += b;
        }
    }
    bool update = false;
    if (type == 0)
    {
        std::lock_guard<std::mutex> l(g_notifLock);
        update = g_notifs.count(tag) != 0;
        g_notifs[tag] = e;
        g_lastToast = tag;
        g_lastNotif = NotifKey{e.package, e.tag, e.id, true};
    }
    std::string head = type == 4 ? (app.empty() ? e.package : app) : (title.empty() ? (app.empty() ? e.package : app) : title);
    int toastMs = type == 4 ? (ints.count(12) && ints[12] ? (int)ints[12] : 2000) : 0;
    bool toast = ShowToast(tag, e, head, text, toastMs, extra, update);
    if (!toast) ShowBalloon(head, text);
    std::printf("notification: %s %s: %s (%s, %zu buttons)\n", type == 4 ? "toast" : "show", e.package.c_str(), text.c_str(), toast ? "WinRT toast" : "balloon", e.actions.size());
    RcmLog("notification: " + std::string(type == 4 ? "Toast " : update ? "update " : "show ") + e.package + " id " + std::to_string(e.id) + " -> " + (toast ? "WinRT toast " + tag : "balloon") + ", " + std::to_string(e.actions.size()) + " buttons" + (extra.empty() ? "" : ", " + Narrow(extra.c_str())));
}

// FileSystem channel (WsaShellBridge FileSystemBridge, proto filesystem.proto, docs/wsa-shell-protocol.md "The
// FileSystem channel"), 15.10.2026:
// - FileTransferRequest, a file the guest sends to Windows in chunks: written to downloads\ next to the host, not to the
//   owner's Downloads; every chunk is acknowledged by a separate FileTransferResult with its cookie, the next one comes
//   only after it. Files Windows would run (exe, bat, ...) are refused, as WsaClient does. launch=true opens the file
//   with its default program only after "files open" on the command port — by default it is a log line.
// - CreateSharedTempFile (synchronous): an empty file with a GUID name in <shared_tmp share>\<subfolder>.
// - PackageVerificationRequest: the apks the guest copied into shared_tmp\quarantine go through AMSI (the Windows
//   antivirus), as on stock WSA; the decision goes back as PackageVerificationResult, the copies are deleted. The
//   command port's "verify deny <package>" rejects that package regardless (the stock host has registry test hooks
//   for the same).
// - ProvideContentFile(Details) (synchronous): a Windows file the clipboard offered as a File item — its name and size,
//   or a copy into <shared_tmp>\content\<guid>.<ext>, where the guest reads it.
#include <amsi.h>
#pragma comment(lib, "amsi.lib")
static std::mutex g_fsLock;
static SOCKET g_fs = INVALID_SOCKET;  // under g_fsLock
static unsigned long long g_fsCallId = 0;
static bool g_openFiles = false;
static std::string g_verifyDeny;      // under g_fsLock
struct FsTransfer { HANDLE file = INVALID_HANDLE_VALUE; std::wstring path; std::string name; };
static std::map<unsigned long long, FsTransfer> g_fsTransfers;  // by cookie, used by the channel thread only

static void FsSend(int type, int field, const std::string& sub)
{
    std::string m, aid, hdr;
    PbInt(m, 1, type);
    PbBytes(aid, 1, "host-fs-" + std::to_string(g_fsCallId + 1));
    PbBytes(m, 2, aid);
    if (field) PbBytes(m, field, sub);
    std::lock_guard<std::mutex> l(g_fsLock);
    if (g_fs == INVALID_SOCKET) return;
    PbInt(hdr, 1, 400000 + ++g_fsCallId);
    PbBytes(hdr, 4, m);
    SendFrame(g_fs, hdr);
}

static void FsChunkResult(unsigned long long cookie, bool ok, const std::string& error)
{
    std::string r;
    PbInt(r, 1, cookie);
    PbInt(r, 2, ok);
    if (!ok) { PbInt(r, 3, 1); PbBytes(r, 4, error); }
    FsSend(2, 4, r);  // FileTransferResult
}

// A Windows file name from the guest's: last path component, no characters Windows forbids, no trailing dots/spaces.
static std::string FsSafeName(std::string n)
{
    size_t slash = n.find_last_of("/\\");
    if (slash != std::string::npos) n.erase(0, slash + 1);
    std::string out;
    for (char ch : n) out += ((unsigned char)ch < 32 || strchr("<>:\"|?*", ch)) ? '_' : ch;
    while (!out.empty() && (out.back() == '.' || out.back() == ' ')) out.pop_back();
    return out.empty() ? "file" : out;
}

static bool FsExecutable(const std::string& name)
{
    static const char* kExt[] = {"appref-ms", "bat", "cmd", "com", "cpl", "exe", "hta", "inf", "jar", "js", "jse", "lnk", "msc", "msi", "msix", "msp", "pif",
                                 "ps1", "psm1", "reg", "scf", "scr", "url", "vb", "vbe", "vbs", "ws", "wsc", "wsf", "wsh"};
    size_t dot = name.rfind('.');
    if (dot == std::string::npos) return false;
    std::string ext = name.substr(dot + 1);
    for (char& ch : ext) ch = (char)tolower((unsigned char)ch);
    for (const char* e : kExt) if (ext == e) return true;
    return false;
}

static void FsTransferChunk(const std::string& req)
{
    std::map<int, unsigned long long> f;
    auto p = PbFields(req, &f);
    unsigned long long cookie = f[1], offset = f[4], size = f[5];
    bool complete = f[6] != 0, error = f[7] != 0, launch = f[8] != 0;
    auto it = g_fsTransfers.find(cookie);
    if (it == g_fsTransfers.end())
    {
        std::string name = FsSafeName(p[2]);
        if (FsExecutable(name)) { RcmLog("files: refused " + name + " (runs on Windows)"); FsChunkResult(cookie, false, "file type not allowed"); return; }
        if (complete || error || offset) { FsChunkResult(cookie, false, "unknown transfer"); return; }
        CreateDirectoryW(FullPath(L"downloads").c_str(), nullptr);
        std::wstring base = FullPath(L"downloads\\" + Widen(name)), path = base;
        size_t dot = base.rfind(L'.'), bs = base.rfind(L'\\');
        std::wstring stem = dot != std::wstring::npos && dot > bs ? base.substr(0, dot) : base, ext = dot != std::wstring::npos && dot > bs ? base.substr(dot) : L"";
        for (int n = 2; GetFileAttributesW(path.c_str()) != INVALID_FILE_ATTRIBUTES; n++) path = stem + L" (" + std::to_wstring(n) + L")" + ext;
        HANDLE h = CreateFileW(path.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (h == INVALID_HANDLE_VALUE) { FsChunkResult(cookie, false, "cannot create file"); return; }
        it = g_fsTransfers.emplace(cookie, FsTransfer{h, path, name}).first;
        RcmLog("files: receiving " + name + " (" + std::to_string(size) + " B) -> " + Narrow(path.c_str()));
    }
    FsTransfer& t = it->second;
    const std::string& data = p[3];
    LARGE_INTEGER at;
    at.QuadPart = (LONGLONG)offset;
    DWORD written = 0;
    bool ok = !error && SetFilePointerEx(t.file, at, nullptr, FILE_BEGIN) && (data.empty() || (WriteFile(t.file, data.data(), (DWORD)data.size(), &written, nullptr) && written == data.size()));
    if (!ok || complete)
    {
        LARGE_INTEGER got{};
        GetFileSizeEx(t.file, &got);
        CloseHandle(t.file);
        if (!ok) DeleteFileW(t.path.c_str());
        bool open = ok && launch && g_openFiles;
        RcmLog("files: " + t.name + (ok ? " received, " + std::to_string(got.QuadPart) + " B" + (launch ? (open ? ", opened" : ", not opened (files off)") : "") : " FAILED, deleted"));
        std::printf("files: %s %s\n", t.name.c_str(), ok ? "received" : "failed");
        if (open) ShellExecuteW(nullptr, L"open", t.path.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
        g_fsTransfers.erase(it);
    }
    FsChunkResult(cookie, ok, ok ? "" : "write failed");
}

// CreateSharedTempFile: the reply's tempFileName is a fresh GUID; empty when the subfolder or the share is unusable.
static std::string FsCreateTemp(const std::string& req)
{
    std::string sub = PbFields(req)[1], name;
    bool okSub = !sub.empty() && sub.size() < 64;
    for (char ch : sub) okSub = okSub && (isalnum((unsigned char)ch) || ch == '_' || ch == '-');
    if (okSub && !g_sharedTmp.empty())
    {
        std::wstring dir = g_sharedTmp + L"\\" + Widen(sub);
        CreateDirectoryW(dir.c_str(), nullptr);
        GUID g;
        wchar_t s[64];
        CoCreateGuid(&g);
        StringFromGUID2(g, s, 64);
        std::wstring w(s + 1, wcslen(s) - 2);
        for (wchar_t& ch : w) ch = towlower(ch);
        HANDLE h = CreateFileW((dir + L"\\" + w).c_str(), GENERIC_WRITE, 0, nullptr, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (h != INVALID_HANDLE_VALUE) { CloseHandle(h); name = Narrow(w.c_str()); }
    }
    RcmLog("files: CreateSharedTempFile " + sub + " -> " + (name.empty() ? std::string("none") : name));
    std::string body;
    PbBytes(body, 1, sub);
    if (!name.empty()) PbBytes(body, 2, name);
    return body;
}

// AMSI over one file; true — clean. Any failure counts as not clean.
static bool FsAmsiClean(HAMSICONTEXT ctx, const std::wstring& path, const std::wstring& label, std::string& why)
{
    HANDLE h = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, 0, nullptr);
    if (h == INVALID_HANDLE_VALUE) { why = "cannot open"; return false; }
    LARGE_INTEGER size{};
    GetFileSizeEx(h, &size);
    std::vector<unsigned char> bytes((size_t)size.QuadPart);
    DWORD got = 0;
    bool read = size.QuadPart < 0x7fffffff && (bytes.empty() || (ReadFile(h, bytes.data(), (DWORD)bytes.size(), &got, nullptr) && got == bytes.size()));
    CloseHandle(h);
    if (!read) { why = "cannot read"; return false; }
    if (bytes.empty()) { why = "empty"; return false; }
    AMSI_RESULT r = AMSI_RESULT_CLEAN;
    HRESULT hr = AmsiScanBuffer(ctx, bytes.data(), (ULONG)bytes.size(), label.c_str(), nullptr, &r);
    if (FAILED(hr)) { char b[32]; std::snprintf(b, sizeof b, "AmsiScanBuffer 0x%08lx", hr); why = b; return false; }
    why = "AMSI " + std::to_string((int)r) + ", " + std::to_string(bytes.size()) + " B";
    return !AmsiResultIsMalware(r);
}

static void FsVerify(std::string req)
{
    std::map<int, unsigned long long> f;
    auto p = PbFields(req, &f);
    unsigned long long id = f[1];
    std::string package = p[3];
    auto apks = PbRepeated(req, 2);
    bool allow = !apks.empty() && !g_sharedTmp.empty();
    std::string log;
    {
        std::lock_guard<std::mutex> l(g_fsLock);
        if (!g_verifyDeny.empty() && g_verifyDeny == package) { allow = false; log = " denied by \"verify deny\";"; }
    }
    HAMSICONTEXT ctx = nullptr;
    HRESULT hr = AmsiInitialize(L"wsa-reborn-host", &ctx);
    if (FAILED(hr)) { allow = false; log += " AmsiInitialize failed;"; }
    for (auto& a : apks)
    {
        bool safe = !a.empty() && a.size() < 128 && a.find("..") == std::string::npos;
        for (char ch : a) safe = safe && (isalnum((unsigned char)ch) || ch == '-' || ch == '_' || ch == '.' || ch == '{' || ch == '}');
        if (!safe) { allow = false; log += " bad name;"; continue; }
        std::wstring path = g_sharedTmp + L"\\quarantine\\" + Widen(a);
        std::string why;
        if (ctx && !FsAmsiClean(ctx, path, Widen(package + "/" + a), why)) allow = false;
        log += " " + a + ": " + why + ";";
        DeleteFileW(path.c_str());
    }
    if (ctx) AmsiUninitialize(ctx);
    std::string r;
    PbInt(r, 1, id);
    PbInt(r, 2, allow);
    FsSend(10, 11, r);  // PackageVerificationResult
    RcmLog("files: PackageVerification " + std::to_string(id) + " " + package + " -> " + (allow ? "ALLOW" : "REJECT") + log);
    std::printf("verify: %s -> %s\n", package.c_str(), allow ? "allow" : "reject");
}

static void FsFromGuest(SOCKET h, unsigned long long callId, const std::string& message)
{
    std::map<int, unsigned long long> ints;
    auto parts = PbFields(message, &ints);
    unsigned long long type = ints.count(1) ? ints[1] : 0;
    std::string reply;
    if (type == 1) FsTransferChunk(parts[3]);
    else if (type == 8)  // CreateSharedTempFile
    {
        PbInt(reply, 1, 8);
        PbBytes(reply, 9, FsCreateTemp(parts[9]));
    }
    else if (type == 9) std::thread(FsVerify, parts[10]).detach();
    else if (type == 11 || type == 13)  // ProvideContentFile(Details): a file the clipboard offered as a File item
    {
        std::string guid = PbFields(parts[type == 11 ? 12 : 14])[1], r;
        std::wstring path;
        {
            std::lock_guard<std::mutex> l(g_clipLock);
            auto it = g_clipFiles.find(guid);
            if (it != g_clipFiles.end()) path = it->second;
        }
        WIN32_FILE_ATTRIBUTE_DATA fa{};
        bool known = !path.empty() && GetFileAttributesExW(path.c_str(), GetFileExInfoStandard, &fa);
        if (type == 13)
        {
            PbBytes(r, 1, guid);
            if (known)
            {
                PbBytes(r, 2, Narrow(PathFindFileNameW(path.c_str())));
                PbInt(r, 3, (unsigned long long)fa.nFileSizeHigh << 32 | fa.nFileSizeLow);
            }
            PbInt(reply, 1, 13);
            PbBytes(reply, 14, r);
            RcmLog("files: ProvideContentFileDetails " + guid + (known ? " -> " + Narrow(path.c_str()) : " -> unknown"));
        }
        else
        {
            bool ok = known && !g_sharedTmp.empty();
            if (ok)
            {
                CreateDirectoryW((g_sharedTmp + L"\\content").c_str(), nullptr);
                ok = CopyFileW(path.c_str(), (g_sharedTmp + L"\\content\\" + Widen(guid)).c_str(), FALSE) != 0;
            }
            PbInt(r, 1, ok);
            PbInt(reply, 1, 12);
            PbBytes(reply, 13, r);
            RcmLog("files: ProvideContentFile " + guid + (ok ? " -> shared_tmp\\content" : " FAILED"));
        }
    }
    else if (type == 7) RcmLog("files: OpenSharedFile — the host shares no user folder, ignored");
    else if (type == 6) RcmLog("files: guest finished scanning external storage");
    SendFrame(h, Reply(callId, reply));
}

static void RcmChannel(std::string name, unsigned port)
{
    SOCKET h = socket(AF_HYPERV, SOCK_STREAM, HV_PROTOCOL_RAW);
    SOCKADDR_HV hv{};
    hv.Family = AF_HYPERV;
    hv.VmId = g_runtimeId;
    hv.ServiceId = VsockService(port);
    if (connect(h, (sockaddr*)&hv, sizeof hv)) { RcmLog("channel " + name + ": connect to guest port " + std::to_string(port) + " failed " + std::to_string(WSAGetLastError())); closesocket(h); return; }
    RcmLog("channel " + name + ": connected to guest port " + std::to_string(port));
    if (name == "UserInput") { std::lock_guard<std::mutex> l(g_inputLock); g_userInput = h; }
    if (name == "Notification") { std::lock_guard<std::mutex> l(g_notifLock); g_notif = h; }
    if (name == "Clipboard") { std::lock_guard<std::mutex> l(g_clipLock); g_clip = h; }
    if (name == "Intents") { std::lock_guard<std::mutex> l(g_appsLock); g_intents = h; }
    if (name == "FileSystem") { std::lock_guard<std::mutex> l(g_fsLock); g_fs = h; }
    if (name == "Apps")
    {
        { std::lock_guard<std::mutex> l(g_appsLock); g_apps = h; }
        AppsSend(1, 0, "");  // GetList
    }
    if (name == "Shell")
    {
        { std::lock_guard<std::mutex> l(g_shellLock); g_shell = h; }
        { std::lock_guard<std::mutex> l(g_frameLock); g_shellUp = true; }
    }
    if (name.find("SysPrep") != std::string::npos)
    {
        std::string f = SysPrepFrame(2);
        send(h, f.data(), (int)f.size(), 0);
        RcmLog("channel " + name + ": SENT StartPhase1 " + Hex(f.data(), f.size()));
    }
    if (name == "Settings" && !g_nicIp.empty())
    {
        std::string f = NetworkConfigurationFrame(1000);
        SendFrame(h, f);
        RcmLog("channel Settings: SENT NetworkConfiguration " + g_nicIp + " via " + g_nicGateway + " " + Hex(f.data(), f.size()));
    }
    char tmp[65536];
    std::string buf;
    unsigned long frames = 0;
    for (int n; (n = recv(h, tmp, sizeof tmp, 0)) > 0;)
    {
        buf.append(tmp, n);
        while (buf.size() >= 4)
        {
            unsigned len = *(unsigned*)buf.data();
            if (buf.size() < 4ull + len) break;
            std::string body = buf.substr(4, len);
            buf.erase(0, 4ull + len);
            if (name == "AudioControl")
            {
                std::string a = AudioControlAnswer(body);
                if (++frames <= 40) RcmLog("channel AudioControl: RECV " + Hex(body.data(), body.size(), 64) + (a.empty() ? "" : " SENT " + Hex(a.data(), a.size())));
                if (!a.empty()) SendFrame(h, a);
                continue;
            }
            if (name == "AudioOut") { if (++frames <= 3) RcmLog("channel AudioOut: RECV " + Hex(body.data(), body.size(), 32) + " (" + std::to_string(body.size()) + " bytes)"); SoundFrame(body); continue; }
            if (name == "AudioIn") continue;
            std::map<int, unsigned long long> hdr, msg;
            auto parts = PbFields(body, &hdr);
            PbFields(parts[4], &msg);
            unsigned long long type = msg.count(1) ? msg[1] : 0;
            // Present repeats every frame — log only the first few.
            if (name == "UserInput") { if (++frames <= 20 || frames % 500 == 0) RcmLog("channel UserInput: RECV " + Hex(body.data(), body.size(), 32) + " (" + std::to_string(frames) + " so far)"); if (hdr[2]) continue; }
            else if (++frames <= 20 || type != 2)
                RcmLog("channel " + name + ": RECV call " + std::to_string(hdr[1]) + (hdr[2] ? " reply" : "") + " type " + std::to_string(type) + " " + std::to_string(len) + " bytes " + Hex(body.data(), body.size(), 64));
            if (name == "Apps") { AppsFromGuest(h, hdr[1], hdr[2] != 0, parts[4]); continue; }
            if (hdr[2] || name == "SysPrepChannel") continue;
            if (name == "Shell") ShellFromGuest(parts[4]);
            if (name == "Notification") NotifFromGuest(parts[4]);
            if (name == "Intents") IntentFromGuest(parts[4]);
            if (name == "Clipboard") { ClipFromGuest(hdr[1], parts[4]); continue; }
            if (name == "FileSystem") { FsFromGuest(h, hdr[1], parts[4]); continue; }
            std::string payload = name == "Composition" ? CompositionAnswer(type) : std::string();
            SendFrame(h, Reply(hdr[1], payload));
            if (frames <= 20 || type != 2) RcmLog("channel " + name + ": SENT reply to call " + std::to_string(hdr[1]) + (payload.empty() ? "" : " " + Hex(payload.data(), payload.size())));
        }
    }
    RcmLog("channel " + name + ": closed (" + std::to_string(WSAGetLastError()) + ")");
    if (name == "AudioOut") { std::lock_guard<std::mutex> l(g_soundLock); SoundCloseLocked(); }
    if (name == "UserInput") { std::lock_guard<std::mutex> l(g_inputLock); g_userInput = INVALID_SOCKET; }
    if (name == "Shell") { std::lock_guard<std::mutex> l(g_shellLock); g_shell = INVALID_SOCKET; }
    if (name == "Notification") { std::lock_guard<std::mutex> l(g_notifLock); g_notif = INVALID_SOCKET; }
    if (name == "Clipboard") { std::lock_guard<std::mutex> l(g_clipLock); g_clip = INVALID_SOCKET; }
    if (name == "FileSystem") { std::lock_guard<std::mutex> l(g_fsLock); g_fs = INVALID_SOCKET; }
    if (name == "Apps" || name == "Intents")
    {
        std::lock_guard<std::mutex> l(g_appsLock);
        if (g_apps == h) g_apps = INVALID_SOCKET;
        if (g_intents == h) g_intents = INVALID_SOCKET;
    }
    closesocket(h);
}

static void RcmControl(SOCKET s, unsigned listenPort, int conn)
{
    std::string tag = "control " + std::to_string(listenPort) + "#" + std::to_string(conn);
    std::string buf;
    char tmp[65536];
    // 35034 is not RCM: the guest's logcat_forwarder streams plain logcat text there (WSA writes it to
    // LocalState/diagnostics/logcat).
    // 22469/22470: the guest's gskserver (windows.services.graphics) talks to the host GSKServer of WsaClient here.
    // Its protocol is not known; the host only accepts and logs what the guest sends (gsk-<port>.log).
    if (listenPort == 22469 || listenPort == 22470)
    {
        std::ofstream out("gsk-" + std::to_string(listenPort) + ".log", std::ios::binary | std::ios::app);
        size_t total = 0;
        for (int n; (n = recv(s, tmp, sizeof tmp, 0)) > 0;)
        {
            if (total < 4096) RcmLog(tag + ": RECV " + std::to_string(n) + " " + Hex(tmp, n, 128));
            total += n;
            out.write(tmp, n);
            out.flush();
        }
        RcmLog(tag + ": closed after " + std::to_string(total) + " bytes (" + std::to_string(WSAGetLastError()) + ")");
        closesocket(s);
        return;
    }
    if (listenPort == 35034)
    {
        std::ofstream out("logcat.log", std::ios::binary | std::ios::app);
        for (int n; (n = recv(s, tmp, sizeof tmp, 0)) > 0;) { out.write(tmp, n); out.flush(); }
        RcmLog(tag + ": logcat closed (" + std::to_string(WSAGetLastError()) + ")");
        closesocket(s);
        return;
    }
    for (int n; (n = recv(s, tmp, sizeof tmp, 0)) > 0;)
    {
        buf.append(tmp, n);
        while (buf.size() >= 4)
        {
            unsigned total = *(unsigned*)buf.data();
            if (total < 4 || total > 65536) { RcmLog(tag + ": bad frame " + Hex(buf.data(), buf.size())); buf.clear(); break; }
            if (buf.size() < total) break;
            const unsigned* w = (const unsigned*)buf.data();
            std::string name = total >= 24 ? std::string(buf.data() + 24, strnlen(buf.data() + 24, total - 24)) : "";
            RcmLog(tag + ": RECV " + std::to_string(total) + " type=" + std::to_string(total >= 8 ? w[1] : 0) + " port=" + std::to_string(total >= 16 ? w[3] : 0) +
                   " name=" + name + " " + Hex(buf.data(), total));
            if (total >= 24 && w[1] == 1) std::thread(RcmChannel, name, w[3]).detach();
            buf.erase(0, total);
        }
    }
    RcmLog(tag + ": closed (" + std::to_string(WSAGetLastError()) + ")");
    closesocket(s);
}

static void RcmListen(unsigned port)
{
    SOCKET l = socket(AF_HYPERV, SOCK_STREAM, HV_PROTOCOL_RAW);
    SOCKADDR_HV hv{};
    hv.Family = AF_HYPERV;
    hv.VmId = g_runtimeId;
    hv.ServiceId = VsockService(port);
    if (bind(l, (sockaddr*)&hv, sizeof hv) || listen(l, 16)) { std::printf("rcm: cannot listen on hvsocket %u (%d)\n", port, WSAGetLastError()); return; }
    std::thread([l, port] {
        int conn = 0;
        for (SOCKET c; (c = accept(l, nullptr, nullptr)) != INVALID_SOCKET; conn++)
        {
            RcmLog("control " + std::to_string(port) + "#" + std::to_string(conn) + ": accepted");
            std::thread(RcmControl, c, port, conn).detach();
        }
    }).detach();
}


// Window mouse/keyboard -> UserInputMessage. Coordinates: client area -> guest display px (the frame is stretched to
// the whole client area). Mouse goes as a real mouse (emulateTouch=false), targetTaskId=-1 for the display window (no
// task to bring forward: the window shows the whole display, the guest's own focus logic decides) and the task's id
// for a task window (property WsaTask, GWLP_USERDATA = task id). Pseudo tasks -<window type> (toast, soft keyboard,
// system alerts/dialogs, overlays — windows without a task) get a popup window; their input goes with targetTaskId=-1.
static std::string InputModifiers()
{
    std::string m;
    PbInt(m, 1, (GetKeyState(VK_CONTROL) & 0x8000) != 0);
    PbInt(m, 2, (GetKeyState(VK_SHIFT) & 0x8000) != 0);
    PbInt(m, 3, (GetKeyState(VK_MENU) & 0x8000) != 0);
    PbInt(m, 4, GetKeyState(VK_CAPITAL) & 1);
    PbInt(m, 5, GetKeyState(VK_NUMLOCK) & 1);
    PbInt(m, 6, GetKeyState(VK_SCROLL) & 1);
    PbInt(m, 7, ((GetKeyState(VK_LWIN) | GetKeyState(VK_RWIN)) & 0x8000) != 0);
    return m;
}

static void PbFloat(std::string& o, int field, float v) { PbVarint(o, (unsigned)field << 3 | 5); o.append((const char*)&v, 4); }

// action: 0 Down, 1 Up, 2 DragMove, 3 HoverMove, 4 Scroll; button: 0 None, 1 Left, 2 Right, 3 Middle.
static bool IsTaskWindow(HWND w) { return GetPropW(w, L"WsaTask") != nullptr; }
static int WindowTask(HWND w) { return IsTaskWindow(w) ? (int)GetWindowLongPtrW(w, GWLP_USERDATA) : -1; }
static int TargetTask(HWND w) { int t = WindowTask(w); return t > 0 ? t : -1; }

// Client point of the display window or of a task window -> guest display px; false while there is nothing to map to.
static bool GuestPoint(HWND w, int cx, int cy, float& x, float& y)
{
    RECT rc;
    GetClientRect(w, &rc);
    if (rc.right <= 0 || rc.bottom <= 0) return false;
    std::lock_guard<std::mutex> l(g_frameLock);
    int task = WindowTask(w);
    if (IsTaskWindow(w))
    {
        auto it = g_tasks.find(task);
        if (it == g_tasks.end() || !it->second.w || !it->second.h) return false;
        x = it->second.x + (cx + 0.5f) * it->second.w / rc.right;
        y = it->second.y + (cy + 0.5f) * it->second.h / rc.bottom;
        return true;
    }
    if (!g_frameW || !g_frameH) return false;
    x = (cx + 0.5f) * g_frameW / rc.right;
    y = (cy + 0.5f) * g_frameH / rc.bottom;
    return true;
}

static void SendMouse(HWND w, int cx, int cy, int action, int button, WPARAM keys, float vscroll = 0, float hscroll = 0)
{
    float x, y;
    if (!GuestPoint(w, cx, cy, x, y)) return;
    int task = TargetTask(w);
    std::string bs, mm, msg;
    PbInt(bs, 1, (keys & MK_LBUTTON) != 0);
    PbInt(bs, 2, (keys & MK_RBUTTON) != 0);
    PbInt(bs, 3, (keys & MK_MBUTTON) != 0);
    PbInt(mm, 1, 0);  // wsaDisplayId: the bridge maps the display address port, 0 for the headless HWC2's display
    PbInt(mm, 2, 0);
    PbInt(mm, 3, action);
    PbFloat(mm, 4, x);
    PbFloat(mm, 5, y);
    PbInt(mm, 6, button);
    PbBytes(mm, 7, bs);
    PbBytes(mm, 8, InputModifiers());
    if (action == 4) { PbFloat(mm, 9, vscroll); PbFloat(mm, 10, hscroll); }
    PbInt(mm, 11, (unsigned long long)(long long)task);
    PbInt(msg, 1, 1);
    PbBytes(msg, 2, mm);
    SendUserInput(msg);
    if (action <= 1) RcmLog("input: mouse " + std::string(action ? "Up" : "Down") + " button " + std::to_string(button) + " at " + std::to_string((int)x) + "," + std::to_string((int)y) + (task >= 0 ? " task " + std::to_string(task) : ""));
}

static void SendKey(WPARAM vk, LPARAM lp, bool down, int task)
{
    std::string km, msg;
    PbInt(km, 1, (unsigned)vk);
    PbInt(km, 2, down);
    PbBytes(km, 3, InputModifiers());
    PbInt(km, 4, down && (lp & (1 << 30)) ? 1 : 0);  // previous key state set: auto-repeat
    PbInt(km, 5, (lp >> 16) & 0xff);
    PbInt(km, 6, (unsigned long long)(long long)task);
    PbInt(msg, 1, 3);
    PbBytes(msg, 4, km);
    SendUserInput(msg);
}

static bool FrameInput(HWND w, UINT msg, WPARAM wp, LPARAM lp)
{
    int cx = (short)LOWORD(lp), cy = (short)HIWORD(lp);
    switch (msg)
    {
    case WM_LBUTTONDOWN: case WM_RBUTTONDOWN: case WM_MBUTTONDOWN:
        SetCapture(w);
        SendMouse(w, cx, cy, 0, msg == WM_LBUTTONDOWN ? 1 : msg == WM_RBUTTONDOWN ? 2 : 3, wp);
        return true;
    case WM_LBUTTONUP: case WM_RBUTTONUP: case WM_MBUTTONUP:
        SendMouse(w, cx, cy, 1, msg == WM_LBUTTONUP ? 1 : msg == WM_RBUTTONUP ? 2 : 3, wp);
        if (!(wp & (MK_LBUTTON | MK_RBUTTON | MK_MBUTTON))) ReleaseCapture();
        return true;
    case WM_MOUSEMOVE:
        SendMouse(w, cx, cy, wp & (MK_LBUTTON | MK_RBUTTON | MK_MBUTTON) ? 2 : 3, 0, wp);
        return true;
    case WM_MOUSEWHEEL: case WM_MOUSEHWHEEL:
    {
        POINT p{cx, cy};  // wheel messages carry screen coordinates
        ScreenToClient(w, &p);
        float d = (float)GET_WHEEL_DELTA_WPARAM(wp) / WHEEL_DELTA;
        SendMouse(w, p.x, p.y, 4, 0, GET_KEYSTATE_WPARAM(wp), msg == WM_MOUSEWHEEL ? d : 0, msg == WM_MOUSEHWHEEL ? d : 0);
        return true;
    }
    case WM_KEYDOWN: case WM_SYSKEYDOWN:
        if (msg == WM_SYSKEYDOWN && wp == VK_F4) return false;  // Alt+F4 stays with Windows
        SendKey(wp, lp, true, TargetTask(w));
        return true;
    case WM_KEYUP: case WM_SYSKEYUP:
        SendKey(wp, lp, false, TargetTask(w));
        return true;
    }
    return false;
}

static void SyncTaskWindows();

static void PaintBgrx(HDC dc, const RECT& rc, const unsigned char* bits, unsigned w, unsigned h)
{
    BITMAPINFO bi{};
    bi.bmiHeader.biSize = sizeof bi.bmiHeader;
    bi.bmiHeader.biWidth = (LONG)w;
    bi.bmiHeader.biHeight = -(LONG)h;  // top-down
    bi.bmiHeader.biPlanes = 1;
    bi.bmiHeader.biBitCount = 32;
    bi.bmiHeader.biCompression = BI_RGB;
    SetStretchBltMode(dc, HALFTONE);
    StretchDIBits(dc, 0, 0, rc.right, rc.bottom, 0, 0, w, h, bits, &bi, DIB_RGB_COLORS, SRCCOPY);
}

static LRESULT CALLBACK TaskWndProc(HWND w, UINT msg, WPARAM wp, LPARAM lp)
{
    if (FrameInput(w, msg, wp, lp)) return 0;
    if (msg == WM_PAINT)
    {
        PAINTSTRUCT ps;
        HDC dc = BeginPaint(w, &ps);
        RECT rc;
        GetClientRect(w, &rc);
        {
            std::lock_guard<std::mutex> l(g_frameLock);
            auto it = g_tasks.find(WindowTask(w));
            if (it != g_tasks.end() && it->second.pw)
                PaintBgrx(dc, rc, it->second.bgrx.data(), it->second.pw, it->second.ph);
            else
                FillRect(dc, &rc, (HBRUSH)GetStockObject(BLACK_BRUSH));
        }
        EndPaint(w, &ps);
        return 0;
    }
    if (msg == WM_ERASEBKGND) return 1;
    // Closing asks the guest to remove the task (Shell RemoveWindow); the window goes away with the task. Without the
    // Shell channel it is only minimized.
    if (msg == WM_CLOSE)
    {
        bool up;
        { std::lock_guard<std::mutex> l(g_frameLock); up = g_shellUp; }
        if (up && WindowTask(w) > 0) ShellRemoveTask(WindowTask(w)); else if (WindowTask(w) > 0) ShowWindow(w, SW_MINIMIZE);
        return 0;
    }
    // A moved window moves its task in the guest (Shell UpdateAndroidWindow): guest px = screen px from the work area's
    // corner, the same mapping the window was placed by — so overlaps in the guest follow the windows on the desktop.
    if (msg == WM_EXITSIZEMOVE)
    {
        POINT p{0, 0};
        ClientToScreen(w, &p);
        RECT wa{};
        SystemParametersInfoW(SPI_GETWORKAREA, 0, &wa, 0);
        int tw = 0, th = 0, task = WindowTask(w);
        {
            std::lock_guard<std::mutex> l(g_frameLock);
            auto it = g_tasks.find(task);
            if (it != g_tasks.end()) tw = it->second.w, th = it->second.h;
        }
        if (task > 0 && tw > 0 && th > 0) ShellMoveTask(task, p.x - wa.left, p.y - wa.top, tw, th);
    }
    // The user activated the window: the task goes to front in the guest before any click lands.
    if (msg == WM_ACTIVATE && LOWORD(wp) != WA_INACTIVE && WindowTask(w) > 0) ShellFocusTask(WindowTask(w));
    if (msg == WM_MOUSEACTIVATE && WindowTask(w) < 0) return MA_NOACTIVATE;  // popups never take the focus
    // Minimize/restore by the user goes to the guest (OnWindowMinimized); the ones the guest asked for do not echo back.
    if (msg == WM_APP_MINIMIZE_TASK || msg == WM_APP_RESTORE_TASK)
    {
        SetPropW(w, L"WsaGuestMin", (HANDLE)1);
        ShowWindow(w, msg == WM_APP_MINIMIZE_TASK ? SW_SHOWMINNOACTIVE : SW_SHOWNOACTIVATE);
        RemovePropW(w, L"WsaGuestMin");
        SetPropW(w, L"WsaMin", (HANDLE)(INT_PTR)(msg == WM_APP_MINIMIZE_TASK));
        return 0;
    }
    if (msg == WM_SIZE && WindowTask(w) > 0 && !GetPropW(w, L"WsaGuestMin"))
    {
        const bool was = GetPropW(w, L"WsaMin") != nullptr, now = wp == SIZE_MINIMIZED;
        if (was != now && (now || wp == SIZE_RESTORED || wp == SIZE_MAXIMIZED))
        {
            SetPropW(w, L"WsaMin", (HANDLE)(INT_PTR)now);
            if (!now) SetPropW(w, L"WsaRestoredAt", (HANDLE)(UINT_PTR)(GetTickCount() | 1));
            bool up;
            { std::lock_guard<std::mutex> l(g_frameLock); up = g_shellUp; }
            if (up) ShellMinimizedTask(WindowTask(w), now);
        }
    }
    return DefWindowProcW(w, msg, wp, lp);
}

static LRESULT CALLBACK FrameWndProc(HWND w, UINT msg, WPARAM wp, LPARAM lp)
{
    if (msg == WM_APP_SYNC_TASKS) { SyncTaskWindows(); return 0; }
    if (msg == WM_APP_CLIP_SET) { ClipSetWindows(*(const ClipItems*)lp); return 0; }
    if (msg == WM_CLIPBOARDUPDATE) { ClipToGuest(); return 0; }
    if (msg == WM_APP_TRAY) { if (LOWORD(lp) == NIN_BALLOONUSERCLICK) NotifActivate(); return 0; }
    if (FrameInput(w, msg, wp, lp)) return 0;
    if (msg == WM_PAINT)
    {
        PAINTSTRUCT ps;
        HDC dc = BeginPaint(w, &ps);
        RECT rc;
        GetClientRect(w, &rc);
        std::lock_guard<std::mutex> l(g_frameLock);
        if (g_frameW && g_frameH)
            PaintBgrx(dc, rc, g_frame.data(), g_frameW, g_frameH);
        else
            FillRect(dc, &rc, (HBRUSH)GetStockObject(BLACK_BRUSH));
        EndPaint(w, &ps);
        return 0;
    }
    if (msg == WM_ERASEBKGND) return 1;
    if (msg == WM_CLOSE) { ShowWindow(w, SW_MINIMIZE); return 0; }  // the VM lives on; Ctrl+C in the console stops it
    return DefWindowProcW(w, msg, wp, lp);
}

// On the window thread: a window per listed task (client area = the task's size, placed at its guest position from the
// work area's corner on first show, moved by the user afterwards), resized when the task's size changes, destroyed
// with the task; repainted when a picture came.
static void SyncTaskWindows()
{
    std::vector<HWND> destroy, repaint;
    {
        std::lock_guard<std::mutex> l(g_frameLock);
        RECT wa{};
        SystemParametersInfoW(SPI_GETWORKAREA, 0, &wa, 0);
        for (auto it = g_tasks.begin(); it != g_tasks.end();)
        {
            TaskWin& t = it->second;
            // A task the guest still has but no longer draws went back (minimized here, or moved back in the guest — the
            // list may come before MinimizeClientWindow): its window stays minimized and goes with the guest's RemoveWindow.
            if (!t.listed && t.hwnd && it->first > 0 && g_shellUp && g_shellTasks.count(it->first))
            {
                // ...but not right after the user restored it: the guest brings the task front and draws it again a
                // little later, and minimizing it in between undid the restore (seen 15.10.2026, trestore 2 s after tmin).
                const UINT_PTR restoredAt = (UINT_PTR)GetPropW(t.hwnd, L"WsaRestoredAt");
                const bool justRestored = restoredAt && (DWORD)(GetTickCount() - (DWORD)restoredAt) < 3000;
                if (!IsIconic(t.hwnd) && !justRestored) PostMessageW(t.hwnd, WM_APP_MINIMIZE_TASK, 0, 0);
                ++it;
                continue;
            }
            if (!t.listed)
            {
                if (t.hwnd) destroy.push_back(t.hwnd);
                std::printf("tasks: task %d gone\n", it->first);
                it = g_tasks.erase(it);
                continue;
            }
            auto shellIt = g_shellTasks.find(it->first);
            const bool overlay = it->first < 0;
            if (overlay && t.hwnd && (t.shownX != t.x || t.shownY != t.y))  // popups follow the guest
            {
                SetWindowPos(t.hwnd, nullptr, wa.left + t.x, wa.top + t.y, 0, 0, SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
                t.shownX = t.x, t.shownY = t.y;
            }
            if (!overlay && g_shellUp && shellIt == g_shellTasks.end())  // not announced (home task) or removed by the guest
            {
                if (t.hwnd) { destroy.push_back(t.hwnd); t.hwnd = nullptr; t.shownW = t.shownH = 0; }
                ++it;
                continue;
            }
            if (t.w > 0 && t.h > 0 && (!t.hwnd || t.shownW != t.w || t.shownH != t.h))
            {
                const DWORD style = overlay ? WS_POPUP : WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX;
                RECT r{0, 0, t.w, t.h};
                AdjustWindowRect(&r, style, FALSE);
                if (!t.hwnd)
                {
                    std::wstring title = L"WSA task " + std::to_wstring(it->first);
                    if (shellIt != g_shellTasks.end() && !shellIt->second.label.empty())
                    {
                        const std::string& u = shellIt->second.label;
                        std::wstring wl(MultiByteToWideChar(CP_UTF8, 0, u.data(), (int)u.size(), nullptr, 0), L'\0');
                        MultiByteToWideChar(CP_UTF8, 0, u.data(), (int)u.size(), wl.data(), (int)wl.size());
                        title = wl;
                    }
                    if (overlay) title = L"WSA window type " + std::to_wstring(-it->first);
                    t.hwnd = CreateWindowExW(overlay ? WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW : 0, L"WsaTaskWindow", title.c_str(), style,
                                             wa.left + t.x + r.left, wa.top + t.y + r.top, r.right - r.left, r.bottom - r.top, nullptr, nullptr,
                                             GetModuleHandleW(nullptr), nullptr);
                    SetPropW(t.hwnd, L"WsaTask", (HANDLE)1);
                    SetWindowLongPtrW(t.hwnd, GWLP_USERDATA, (LONG_PTR)it->first);
                    t.shownX = t.x, t.shownY = t.y;
                    // Under the other windows: the owner may be working at the desktop.
                    SetWindowPos(t.hwnd, HWND_BOTTOM, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_SHOWWINDOW);
                    std::printf("tasks: window for task %d (%dx%d at %d,%d)\n", it->first, t.w, t.h, t.x, t.y);
                }
                else
                    SetWindowPos(t.hwnd, nullptr, 0, 0, r.right - r.left, r.bottom - r.top, SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE);
                t.shownW = t.w;
                t.shownH = t.h;
            }
            if (t.hwnd) repaint.push_back(t.hwnd);
            ++it;
        }
    }
    for (HWND h : destroy) DestroyWindow(h);
    for (HWND h : repaint) InvalidateRect(h, nullptr, FALSE);
}

static void FrameWindowThread()
{
    WNDCLASSW tc{};
    tc.lpfnWndProc = TaskWndProc;
    tc.hInstance = GetModuleHandleW(nullptr);
    tc.hCursor = LoadCursor(nullptr, IDC_ARROW);
    tc.lpszClassName = L"WsaTaskWindow";
    RegisterClassW(&tc);
    WNDCLASSW wc{};
    wc.lpfnWndProc = FrameWndProc;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
    wc.lpszClassName = L"WsaHeadlessFrame";
    RegisterClassW(&wc);
    RECT r{0, 0, 1280, 800};
    AdjustWindowRect(&r, WS_OVERLAPPEDWINDOW, FALSE);
    g_frameWnd = CreateWindowW(wc.lpszClassName, L"WSA headless (wsa-reborn-host)", WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT,
                               r.right - r.left, r.bottom - r.top, nullptr, nullptr, wc.hInstance, nullptr);
    SetWindowPos(g_frameWnd, HWND_BOTTOM, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_SHOWWINDOW);
    TrayAdd();
    AddClipboardFormatListener(g_frameWnd);
    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) { TranslateMessage(&msg); DispatchMessageW(&msg); }
}

// The frame the window shows, as frame.bmp next to the exe (the window belongs to an elevated process: other
// programs cannot PrintWindow it, and it may sit under other windows).
static void SaveBmp(const std::string& name, const std::vector<unsigned char>& bgrx, unsigned w, unsigned h)
{
    BITMAPINFOHEADER bi{};
    bi.biSize = sizeof bi;
    bi.biWidth = (LONG)w;
    bi.biHeight = -(LONG)h;
    bi.biPlanes = 1;
    bi.biBitCount = 32;
    bi.biCompression = BI_RGB;
    BITMAPFILEHEADER fh{};
    fh.bfType = 0x4d42;
    fh.bfOffBits = sizeof fh + sizeof bi;
    fh.bfSize = fh.bfOffBits + (DWORD)bgrx.size();
    std::ofstream f(name, std::ios::binary);
    f.write((const char*)&fh, sizeof fh);
    f.write((const char*)&bi, sizeof bi);
    f.write((const char*)bgrx.data(), bgrx.size());
}

// Also task-<id>.bmp per task window.
static void SaveFrameBmp()
{
    std::lock_guard<std::mutex> l(g_frameLock);
    if (g_frameW && g_frameH) SaveBmp("frame.bmp", g_frame, g_frameW, g_frameH);
    for (auto& [id, t] : g_tasks)
        if (t.pw) SaveBmp("task-" + std::to_string(id) + ".bmp", t.bgrx, t.pw, t.ph);
}

// AB24/XB24 (bytes R,G,B,A) -> BGRX; AR24/XR24 copied.
static void ToBgrx(const unsigned char* raw, unsigned stride, unsigned w, unsigned h, unsigned format, std::vector<unsigned char>& out)
{
    bool swap = format == 0x34324241 || format == 0x34324258;
    out.resize((size_t)w * 4 * h);
    for (unsigned y = 0; y < h; y++)
    {
        const unsigned char* src = raw + (size_t)y * stride;
        unsigned char* dst = out.data() + (size_t)y * w * 4;
        if (!swap) { memcpy(dst, src, (size_t)w * 4); continue; }
        for (unsigned x = 0; x < w; x++, src += 4, dst += 4) { dst[0] = src[2]; dst[1] = src[1]; dst[2] = src[0]; dst[3] = 255; }
    }
}

static bool RecvAll(SOCKET s, void* p, size_t n)
{
    char* c = (char*)p;
    while (n)
    {
        int r = recv(s, c, n > (1u << 30) ? (1 << 30) : (int)n, 0);
        if (r <= 0) return false;
        c += r;
        n -= r;
    }
    return true;
}

static void FrameConn(SOCKET c)
{
    std::printf("frames: guest connected\n");
    std::vector<unsigned char> raw;
    ULONGLONG t0 = GetTickCount64();
    unsigned long long n0 = 0, n = 0;
    for (unsigned magic; RecvAll(c, &magic, sizeof magic);)
    {
        if (magic == kMagicList)
        {
            TaskListHeader lh{magic};
            if (!RecvAll(c, (char*)&lh + 4, sizeof lh - 4) || lh.version != 1 || lh.count > 1024) { std::printf("frames: bad task list, closing\n"); break; }
            std::vector<TaskRect> list(lh.count);
            if (lh.count && !RecvAll(c, list.data(), list.size() * sizeof(TaskRect))) break;
            std::string ids;
            {
                std::lock_guard<std::mutex> l(g_frameLock);
                for (auto& [id, t] : g_tasks) t.listed = false;
                for (const TaskRect& r : list)
                {
                    TaskWin& t = g_tasks[r.taskId];
                    t.listed = true;
                    t.x = r.x, t.y = r.y, t.w = r.width, t.h = r.height;
                    ids += " " + std::to_string(r.taskId) + "(" + std::to_string(r.width) + "x" + std::to_string(r.height) + "@" + std::to_string(r.x) + "," + std::to_string(r.y) + ")";
                }
            }
            std::printf("tasks: list of %u:%s\n", lh.count, ids.c_str());
            if (g_frameWnd) PostMessageW(g_frameWnd, WM_APP_SYNC_TASKS, 0, 0);
            continue;
        }
        if (magic == kMagicTask)
        {
            TaskHeader th{magic};
            if (!RecvAll(c, (char*)&th + 4, sizeof th - 4) || th.version != 1 || !th.width || !th.height || th.stride < th.width * 4 ||
                th.height > 8192 || th.stride > 65536)
            { std::printf("frames: bad task header, closing\n"); break; }
            raw.resize((size_t)th.stride * th.height);
            if (!RecvAll(c, raw.data(), raw.size())) break;
            {
                std::lock_guard<std::mutex> l(g_frameLock);
                auto it = g_tasks.find(th.taskId);
                if (it != g_tasks.end())  // the list comes first; a picture of an unlisted task is late
                {
                    TaskWin& t = it->second;
                    ToBgrx(raw.data(), th.stride, th.width, th.height, th.format, t.bgrx);
                    t.pw = th.width, t.ph = th.height;
                    t.x = th.x, t.y = th.y, t.w = (int)th.width, t.h = (int)th.height;
                    t.pictures++;
                }
            }
            if (g_frameWnd) PostMessageW(g_frameWnd, WM_APP_SYNC_TASKS, 0, 0);
            continue;
        }
        FrameHeader h{magic};
        if (!RecvAll(c, (char*)&h + 4, sizeof h - 4)) break;
        if (h.magic != kMagicFrame || h.version != 1 || !h.width || !h.height || h.stride < h.width * 4 || h.height > 8192 || h.stride > 65536)
        { std::printf("frames: bad header, closing\n"); break; }
        raw.resize((size_t)h.stride * h.height);
        if (!RecvAll(c, raw.data(), raw.size())) break;
        {
            std::lock_guard<std::mutex> l(g_frameLock);
            ToBgrx(raw.data(), h.stride, h.width, h.height, h.format, g_frame);
            g_frameW = h.width;
            g_frameH = h.height;
            g_frameNo = h.frame;
            g_framesShown++;
        }
        if (g_frameWnd) InvalidateRect(g_frameWnd, nullptr, FALSE);
        n++;
        if (GetTickCount64() - t0 >= 10000)
        {
            std::printf("frames: %llu in 10 s (%ux%u, guest frame %llu)\n", n - n0, h.width, h.height, h.frame);
            SaveFrameBmp();
            t0 = GetTickCount64();
            n0 = n;
        }
    }
    std::printf("frames: guest went away (%d)\n", WSAGetLastError());
    closesocket(c);
}

static void FrameListen()
{
    SOCKET l = socket(AF_HYPERV, SOCK_STREAM, HV_PROTOCOL_RAW);
    SOCKADDR_HV hv{};
    hv.Family = AF_HYPERV;
    hv.VmId = g_runtimeId;
    hv.ServiceId = VsockService(kFramePort);
    if (bind(l, (sockaddr*)&hv, sizeof hv) || listen(l, 4)) { std::printf("frames: cannot listen on hvsocket %u (%d)\n", kFramePort, WSAGetLastError()); return; }
    std::thread(FrameWindowThread).detach();
    std::thread([l] {
        for (SOCKET c; (c = accept(l, nullptr, nullptr)) != INVALID_SOCKET;) std::thread(FrameConn, c).detach();
    }).detach();
}

// Self-test of the input path without touching the user's desktop: 127.0.0.1:<adb port + 10> takes text lines
//   click <x> <y>          left click at guest display px (posted to the frame window as WM_LBUTTONDOWN/UP, so the
//                          whole window -> UserInput path runs)
//   wheel <x> <y> <notches> vertical wheel at guest px;  key <vk>  key press (Windows VK, decimal)
//   tasks                  the task windows: "<task> <x> <y> <w> <h> <pictures> <shown|none>" per line
//   tclick <task> <x> <y>  left click at px of the task's own picture, posted to its window
//   tkey <task> <vk>       key press into the task's window
//   clip on|off            clipboard exchange with the guest (off by default: the owner's clipboard)
//   sound [<0-100>]        the volume of the guest's sound (this client's output only); without it — the counters
//   uris on|off            open the guest's links in Windows (default) or only log them
//   clipput text|html|png|file <path>  send a file's content to the guest as a Windows copy would (no clipboard touched)
//   files open|off         open files the guest sends with launch=true (off by default: only downloads\ + a log line)
//   verify deny <package>|amsi  reject that package's installs on top of AMSI, or AMSI alone (default)
//   nclick                 as a click on the last notification (Activation{Launch} to the guest)
//   naction <i> [reply]    as button <i> of the last notification's toast, with the reply typed into its text box
//   ndismiss               as swiping the last notification's toast away (Dismissal to the guest)
//   tclose <task>          close the task's window (WM_CLOSE: Shell RemoveWindow to the guest)
//   tmove <task> <x> <y>   move the task's window so its client area starts at work-area px x,y, then end of a move
//                          (WM_EXITSIZEMOVE: Shell UpdateAndroidWindow to the guest)
// and answers "ok <requests sent so far>" or an error. The window must not be minimized (client area = scale).
static void InputControl(unsigned short port)
{
    SOCKET l = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    a.sin_port = htons(port);
    if (bind(l, (sockaddr*)&a, sizeof a) || listen(l, 4)) { std::printf("input control: cannot listen on %u (%d)\n", port, WSAGetLastError()); return; }
    std::printf("input control: 127.0.0.1:%u (click x y | wheel x y n | key vk)\n", port);
    std::thread([l] {
        for (SOCKET c; (c = accept(l, nullptr, nullptr)) != INVALID_SOCKET; closesocket(c))
        {
            char buf[256];
            std::string line;
            for (int n; line.find('\n') == std::string::npos && (n = recv(c, buf, sizeof buf, 0)) > 0;) line.append(buf, n);
            std::istringstream in(line);
            std::string cmd, answer;
            float x = 0, y = 0, d = 0;
            in >> cmd;
            RECT rc{};
            unsigned fw, fh;
            { std::lock_guard<std::mutex> lk(g_frameLock); fw = g_frameW; fh = g_frameH; }
            if (g_frameWnd) GetClientRect(g_frameWnd, &rc);
            auto client = [&](float gx, float gy) { return MAKELPARAM((int)(gx * rc.right / fw), (int)(gy * rc.bottom / fh)); };
            if (cmd == "clip")
            {
                std::string v;
                in >> v;
                g_clipOn = v == "on";
                answer = std::string("ok clip ") + (g_clipOn ? "on" : "off") + "\n";
                send(c, answer.data(), (int)answer.size(), 0);
                continue;
            }
            if (cmd == "sound")
            {
                int v = -1;
                in >> v;
                std::lock_guard<std::mutex> lk(g_soundLock);
                if (v >= 0 && v <= 100)
                {
                    DWORD w = (DWORD)(v * 0xffff / 100);
                    g_waveVolume = w | w << 16;
                    if (g_waveOut) waveOutSetVolume(g_waveOut, g_waveVolume);
                }
                char a[200];
                std::snprintf(a, sizeof a, "ok sound volume %u frames %llu bytes %llu played %llu dropped %llu\n", (unsigned)((g_waveVolume & 0xffff) * 100 / 0xffff), g_soundFrames, g_soundBytes, SoundPlayedLocked(), g_soundDropped);
                answer = a;
                send(c, answer.data(), (int)answer.size(), 0);
                continue;
            }
            if (cmd == "clipput")
            {
                // clipput text|html|png|file <Windows path>: what a copy in Windows would send, from a file
                std::string kind, path;
                in >> kind >> std::ws;
                std::getline(in, path);
                ClipItems ci;
                std::vector<std::wstring> files;
                std::ifstream f(Widen(path), std::ios::binary);
                std::string data((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
                if (kind == "file") files.push_back(FullPath(Widen(path)));
                else if (!f) kind = "?";
                else if (kind == "text") ci.text = data;
                else if (kind == "html") ci.html = data;
                else if (kind == "png") ci.png = data;
                else kind = "?";
                if (kind != "?") ClipSendToGuest(ci, files);
                answer = kind == "?" ? "error: clipput text|html|png|file <path>\n" : "ok\n";
                send(c, answer.data(), (int)answer.size(), 0);
                continue;
            }
            if (cmd == "files")
            {
                std::string v;
                in >> v;
                g_openFiles = v == "open";
                answer = std::string("ok files ") + (g_openFiles ? "open" : "off") + "\n";
                send(c, answer.data(), (int)answer.size(), 0);
                continue;
            }
            if (cmd == "verify")
            {
                std::string v, package;
                in >> v >> package;
                {
                    std::lock_guard<std::mutex> l(g_fsLock);
                    g_verifyDeny = v == "deny" ? package : std::string();
                    answer = "ok verify " + (g_verifyDeny.empty() ? std::string("amsi only") : "deny " + g_verifyDeny) + "\n";
                }
                send(c, answer.data(), (int)answer.size(), 0);
                continue;
            }
            if (cmd == "uris")
            {
                std::string v;
                in >> v;
                g_openUris = v != "off";
                answer = std::string("ok uris ") + (g_openUris ? "on" : "off") + "\n";
                send(c, answer.data(), (int)answer.size(), 0);
                continue;
            }
            if (cmd == "apps" || cmd == "applist" || cmd == "launch" || cmd == "uninstall")
            {
                std::string package;
                in >> package;
                if (cmd == "launch") answer = AppLaunch(package);
                else if (cmd == "applist") { AppsSend(1, 0, ""); answer = "ok\n"; }
                else if (cmd == "uninstall")
                {
                    std::string app, req;
                    PbBytes(app, 1, package);
                    PbBytes(req, 1, app);
                    if (SafePackage(package)) AppsSend(3, 6, req);
                    answer = SafePackage(package) ? "ok\n" : "error: bad package name\n";
                }
                else
                {
                    std::lock_guard<std::mutex> l(g_appsLock);
                    for (auto& [pkg, a] : g_appList) answer += pkg + (a.launchable ? " launchable " : " - ") + a.label + "\n";
                    if (answer.empty()) answer = "no apps\n";
                }
                send(c, answer.data(), (int)answer.size(), 0);
                continue;
            }
            if (cmd == "nclick" || cmd == "naction" || cmd == "ndismiss")
            {
                // As a click on the last notification's toast (its body, or button <i> with an optional reply typed in),
                // or as swiping it away; without toasts nclick clicks the balloon.
                int button = -1;
                std::string reply, last;
                if (cmd == "naction") { in >> button >> std::ws; std::getline(in, reply); }
                {
                    std::lock_guard<std::mutex> l(g_notifLock);
                    last = g_lastToast;
                }
                if (last.empty() && cmd == "nclick") { if (g_frameWnd) PostMessageW(g_frameWnd, WM_APP_TRAY, 0, MAKELPARAM(NIN_BALLOONUSERCLICK, 0)); }
                else if (cmd == "ndismiss") ToastDismissed(last);
                else ToastActivated("t=" + last + "&a=" + std::to_string(button), reply);
                answer = last.empty() && cmd != "nclick" ? "error: no notification toast\n" : "ok\n";
                send(c, answer.data(), (int)answer.size(), 0);
                continue;
            }
            if (cmd == "tasks" || cmd == "tclick" || cmd == "tkey" || cmd == "tclose" || cmd == "tmove" || cmd == "tmin" || cmd == "trestore")
            {
                int task = 0;
                HWND tw = nullptr;
                int tw_ = 0, th_ = 0;
                if (cmd != "tasks") in >> task;
                {
                    std::lock_guard<std::mutex> lk(g_frameLock);
                    for (auto& [id, t] : g_tasks)
                    {
                        if (cmd == "tasks")
                            answer += std::to_string(id) + " " + std::to_string(t.x) + " " + std::to_string(t.y) + " " + std::to_string(t.w) + " " +
                                      std::to_string(t.h) + " " + std::to_string(t.pictures) + (!t.hwnd ? " none\n" : IsIconic(t.hwnd) ? " minimized\n" : " shown\n");
                        if (id == task) tw = t.hwnd, tw_ = t.w, th_ = t.h;
                    }
                }
                if (cmd == "tasks") { if (answer.empty()) answer = "no tasks\n"; send(c, answer.data(), (int)answer.size(), 0); continue; }
                RECT trc{};
                if (tw) GetClientRect(tw, &trc);
                if (tw && cmd == "tmin") PostMessageW(tw, WM_SYSCOMMAND, SC_MINIMIZE, 0);
                else if (tw && cmd == "trestore") ShowWindowAsync(tw, SW_SHOWNOACTIVATE);  // as the user would, but without the focus
                else if (!tw || IsIconic(tw) || trc.right <= 0 || !tw_) answer = "error: no window for task " + std::to_string(task);
                else if (cmd == "tclick" && (in >> x >> y))
                {
                    LPARAM p = MAKELPARAM((int)(x * trc.right / tw_), (int)(y * trc.bottom / th_));
                    PostMessageW(tw, WM_LBUTTONDOWN, MK_LBUTTON, p);
                    PostMessageW(tw, WM_LBUTTONUP, 0, p);
                }
                else if (cmd == "tkey" && (in >> d))
                {
                    PostMessageW(tw, WM_KEYDOWN, (WPARAM)d, 1);
                    PostMessageW(tw, WM_KEYUP, (WPARAM)d, 1 | (1u << 30) | (1u << 31));
                }
                else if (cmd == "tclose") PostMessageW(tw, WM_CLOSE, 0, 0);
                else if (cmd == "tmove" && (in >> x >> y))
                {
                    RECT wa{}, wr{};
                    SystemParametersInfoW(SPI_GETWORKAREA, 0, &wa, 0);
                    GetWindowRect(tw, &wr);
                    POINT c{0, 0};
                    ClientToScreen(tw, &c);
                    SetWindowPos(tw, nullptr, wa.left + (int)x - (c.x - wr.left), wa.top + (int)y - (c.y - wr.top), 0, 0,
                                 SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
                    PostMessageW(tw, WM_EXITSIZEMOVE, 0, 0);
                }
                else answer = "error: tclick task x y | tkey task vk | tclose task | tmove task x y | tmin task | trestore task";
            }
            else if (!g_frameWnd || IsIconic(g_frameWnd) || !fw || rc.right <= 0) answer = "error: no frame or window minimized";
            else if (cmd == "click" && (in >> x >> y))
            {
                PostMessageW(g_frameWnd, WM_LBUTTONDOWN, MK_LBUTTON, client(x, y));
                PostMessageW(g_frameWnd, WM_LBUTTONUP, 0, client(x, y));
            }
            else if (cmd == "wheel" && (in >> x >> y >> d))
            {
                POINT p{GET_X_LPARAM(client(x, y)), GET_Y_LPARAM(client(x, y))};
                ClientToScreen(g_frameWnd, &p);
                PostMessageW(g_frameWnd, WM_MOUSEWHEEL, MAKEWPARAM(0, (short)(d * WHEEL_DELTA)), MAKELPARAM(p.x, p.y));
            }
            else if (cmd == "key" && (in >> d))
            {
                PostMessageW(g_frameWnd, WM_KEYDOWN, (WPARAM)d, 1);
                PostMessageW(g_frameWnd, WM_KEYUP, (WPARAM)d, 1 | (1u << 30) | (1u << 31));
            }
            else answer = "error: click x y | wheel x y n | key vk | tasks | tclick task x y | tkey task vk | tclose task | tmove task x y";
            if (answer.empty()) { Sleep(200); answer = "ok " + std::to_string(g_inputSent) + (g_userInput == INVALID_SOCKET ? " (no UserInput channel yet)" : ""); }
            answer += "\n";
            send(c, answer.data(), (int)answer.size(), 0);
        }
    }).detach();
}

// The desktop half: RCM channels (with logcat and GSK), frames and task windows, the command port.
static void GuestSide(unsigned short port)
{
    // The guest's logcat of this run only; the previous run's is kept aside (it grew to 800 MB over 31 boots).
    MoveFileExW(L"logcat.log", L"logcat.prev.log", MOVEFILE_REPLACE_EXISTING);
    g_rcmLog.open("rcm.log", std::ios::binary);
    RcmListen(35002);
    RcmListen(35034);
    RcmListen(22469);
    RcmListen(22470);
    FrameListen();
    InputControl(port + 10);
}

static BOOL WINAPI OnCtrl(DWORD)
{
    if (g_trayAdded) { NOTIFYICONDATAW n = TrayData(); Shell_NotifyIconW(NIM_DELETE, &n); }
    if (g_system) Wait([] { HCS_OPERATION op = HcsCreateOperation(nullptr, nullptr); HcsTerminateComputeSystem(g_system, op, nullptr); return op; }(), "terminate");
    ExitProcess(0);
}

// ---- the Windows service ---------------------------------------------------------------------------------------
static const wchar_t* kSvcName = L"WsaRebornHost";
static const wchar_t* kVmMarker = L"Global\\WsaRebornHostVm";
static SERVICE_STATUS_HANDLE g_svc;
static std::wstring g_svcDir;
static int RunVm(const wchar_t* config, unsigned short port, std::string log);

static void SvcReport(DWORD state, DWORD exitCode = 0)
{
    SERVICE_STATUS st{SERVICE_WIN32_OWN_PROCESS, state, state == SERVICE_RUNNING ? SERVICE_ACCEPT_STOP | SERVICE_ACCEPT_SHUTDOWN : 0u,
                      exitCode ? (DWORD)ERROR_SERVICE_SPECIFIC_ERROR : 0u, exitCode, 0, state == SERVICE_RUNNING || state == SERVICE_STOPPED ? 0u : 30000u};
    SetServiceStatus(g_svc, &st);
}

static DWORD WINAPI SvcCtrl(DWORD control, DWORD, void*, void*)
{
    if (control != SERVICE_CONTROL_STOP && control != SERVICE_CONTROL_SHUTDOWN) return control == SERVICE_CONTROL_INTERROGATE ? NO_ERROR : ERROR_CALL_NOT_IMPLEMENTED;
    SvcReport(SERVICE_STOP_PENDING);
    // Answer the service manager first: exiting inside the handler breaks ControlService with ERROR_BROKEN_PIPE.
    std::thread([] {
        if (g_system) Wait([] { HCS_OPERATION op = HcsCreateOperation(nullptr, nullptr); HcsTerminateComputeSystem(g_system, op, nullptr); return op; }(), "terminate (service stop)");
        SvcReport(SERVICE_STOPPED);
        ExitProcess(0);
    }).detach();
    return NO_ERROR;
}

static void WINAPI SvcMain(DWORD, LPWSTR*)
{
    g_svc = RegisterServiceCtrlHandlerExW(kSvcName, SvcCtrl, nullptr);
    SvcReport(SERVICE_START_PENDING);
    // See ConfigFile: configuration from the exe's folder, own files in %ProgramData%\WsaReborn (SYSTEM and
    // administrators write, users read — the default ProgramData ACL would let users plant files there), the user's
    // folder read only.
    wchar_t self[MAX_PATH], state[MAX_PATH];
    GetModuleFileNameW(nullptr, self, MAX_PATH);
    g_configDir.assign(self, wcsrchr(self, L'\\'));
    g_userDir = g_svcDir;
    ExpandEnvironmentStringsW(L"%ProgramData%\\WsaReborn", state, MAX_PATH);
    PSECURITY_DESCRIPTOR sd = nullptr;
    ConvertStringSecurityDescriptorToSecurityDescriptorW(L"D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;FRFX;;;AU)", SDDL_REVISION_1, &sd, nullptr);
    SECURITY_ATTRIBUTES sa{sizeof sa, sd, FALSE};
    if (!CreateDirectoryW(state, &sa) && GetLastError() == ERROR_ALREADY_EXISTS)
    {
        PACL dacl = nullptr;
        BOOL present, defaulted;
        GetSecurityDescriptorDacl(sd, &present, &dacl, &defaulted);
        SetNamedSecurityInfoW(state, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION, nullptr, nullptr, dacl, nullptr);
    }
    if (sd) LocalFree(sd);
    if (!SetCurrentDirectoryW(state)) { SvcReport(SERVICE_STOPPED, 2); return; }
    // The log a console run prints goes to out.txt, as the agent redirects it.
    // Shared for reading while the service runs (_wfreopen_s opens it exclusively).
    FILE* out = nullptr;
    _wfreopen_s(&out, L"NUL", L"w", stdout);
    HANDLE h = CreateFileW(L"out.txt", GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, CREATE_ALWAYS, 0, nullptr);
    if (out && h != INVALID_HANDLE_VALUE) _dup2(_open_osfhandle((intptr_t)h, _O_WRONLY), _fileno(stdout));
    setvbuf(stdout, nullptr, _IONBF, 0);
    SvcReport(SERVICE_RUNNING);
    int rc = RunVm(L"config.json", 58600, "console.log");
    SvcReport(SERVICE_STOPPED, rc ? rc : 1);
}

static SC_HANDLE OpenVmService(DWORD access)
{
    SC_HANDLE scm = OpenSCManagerW(nullptr, nullptr, SC_MANAGER_CONNECT);
    SC_HANDLE s = scm ? OpenServiceW(scm, kSvcName, access) : nullptr;
    if (scm) CloseServiceHandle(scm);
    return s;
}

// The process id of the running service, 0 if it is not running (or not installed).
static DWORD ServicePid()
{
    SC_HANDLE s = OpenVmService(SERVICE_QUERY_STATUS);
    if (!s) return 0;
    SERVICE_STATUS_PROCESS st{};
    DWORD n;
    BOOL ok = QueryServiceStatusEx(s, SC_STATUS_PROCESS_INFO, (BYTE*)&st, sizeof st, &n);
    CloseServiceHandle(s);
    return ok && st.dwCurrentState == SERVICE_RUNNING ? st.dwProcessId : 0;
}

// A VM half (the agent's or the service's) is running or starting. Created by an elevated process, the marker may be
// closed to this user — refused access still means it exists.
static bool VmHalfRunning()
{
    HANDLE h = OpenMutexW(SYNCHRONIZE, FALSE, kVmMarker);
    if (h) { CloseHandle(h); return true; }
    return GetLastError() == ERROR_ACCESS_DENIED;
}

// true when started or when there is no service to start (false: try again — e.g. it is still stopping).
static bool StartVmService()
{
    SC_HANDLE s = OpenVmService(SERVICE_START);
    if (!s) { std::printf("client: no service %ls installed (%lu) — waiting for a VM started otherwise\n", kSvcName, GetLastError()); return true; }
    BOOL ok = StartServiceW(s, 0, nullptr);
    std::printf("client: starting the service %ls: %s (%lu)\n", kSvcName, ok ? "ok" : "failed", ok ? 0 : GetLastError());
    CloseServiceHandle(s);
    return ok;
}

static int StopService()
{
    SC_HANDLE s = OpenVmService(SERVICE_STOP);
    SERVICE_STATUS st{};
    BOOL ok = s && ControlService(s, SERVICE_CONTROL_STOP, &st);
    std::printf("stop %ls: %s (%lu)\n", kSvcName, ok ? "ok" : "failed", ok ? 0 : GetLastError());
    if (s) CloseServiceHandle(s);
    return ok ? 0 : 1;
}

static int Install(const wchar_t* folder)
{
    wchar_t self[MAX_PATH], pf[MAX_PATH];
    GetModuleFileNameW(nullptr, self, MAX_PATH);
    std::wstring dir = folder ? folder : std::wstring(self, wcsrchr(self, L'\\'));
    if (GetFileAttributesW((dir + L"\\config.json").c_str()) == INVALID_FILE_ATTRIBUTES) { std::printf("no config.json in %ls\n", dir.c_str()); return 1; }
    // The binary goes where only administrators write: a LocalSystem service must not run an exe from the user's temp.
    ExpandEnvironmentStringsW(L"%ProgramFiles%\\WsaReborn", pf, MAX_PATH);
    CreateDirectoryW(pf, nullptr);
    std::wstring exe = std::wstring(pf) + L"\\wsa-reborn-host.exe";
    if (_wcsicmp(exe.c_str(), self) && !CopyFileW(self, exe.c_str(), FALSE)) { std::printf("cannot copy to %ls (%lu)\n", exe.c_str(), GetLastError()); return 1; }
    // What the service trusts goes next to the exe (see ConfigFile); edits in the folder take effect with --install again.
    for (const wchar_t* name : {L"config.json", L"shares.txt"})
        if (_wcsicmp(dir.c_str(), pf) && !CopyFileW((dir + L"\\" + name).c_str(), (std::wstring(pf) + L"\\" + name).c_str(), FALSE) && GetLastError() != ERROR_FILE_NOT_FOUND)
        {
            std::printf("cannot copy %ls to %ls (%lu)\n", name, pf, GetLastError());
            return 1;
        }
    std::wstring cmd = L"\"" + exe + L"\" --service \"" + dir + L"\"";
    SC_HANDLE scm = OpenSCManagerW(nullptr, nullptr, SC_MANAGER_CREATE_SERVICE);
    if (!scm) { std::printf("OpenSCManager: %lu (run as administrator)\n", GetLastError()); return 1; }
    SC_HANDLE s = CreateServiceW(scm, kSvcName, L"WSA-Reborn host (VM)", SERVICE_ALL_ACCESS, SERVICE_WIN32_OWN_PROCESS, SERVICE_DEMAND_START,
                                 SERVICE_ERROR_NORMAL, cmd.c_str(), nullptr, nullptr, nullptr, nullptr, nullptr);
    if (!s && GetLastError() == ERROR_SERVICE_EXISTS && (s = OpenServiceW(scm, kSvcName, SERVICE_ALL_ACCESS)))
        ChangeServiceConfigW(s, SERVICE_NO_CHANGE, SERVICE_DEMAND_START, SERVICE_NO_CHANGE, cmd.c_str(), nullptr, nullptr, nullptr, nullptr, nullptr, nullptr);
    CloseServiceHandle(scm);
    if (!s) { std::printf("CreateService: %lu\n", GetLastError()); return 1; }
    // Start, stop and query for the installing user without elevation (RP start, WP stop, LC/LO/RC query).
    std::wstring sddl = L"D:(A;;CCLCSWRPWPDTLOCRRC;;;SY)(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;BA)(A;;CCLCSWLOCRRC;;;IU)(A;;CCLCSWRPWPDTLOCRRC;;;" + Widen(UserSid()) + L")";
    PSECURITY_DESCRIPTOR sd = nullptr;
    BOOL ok = ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.c_str(), SDDL_REVISION_1, &sd, nullptr) && SetServiceObjectSecurity(s, DACL_SECURITY_INFORMATION, sd);
    std::printf("service %ls: %ls\n  folder %ls, user start/stop: %s\n  config.json and shares.txt copied to %ls (run --install again after editing them)\n",
                kSvcName, cmd.c_str(), dir.c_str(), ok ? "ok" : "FAILED", pf);
    if (sd) LocalFree(sd);
    CloseServiceHandle(s);
    return ok ? 0 : 1;
}

static int Uninstall()
{
    SC_HANDLE s = OpenVmService(SERVICE_STOP | DELETE | SERVICE_QUERY_STATUS);
    if (!s) { std::printf("OpenService: %lu\n", GetLastError()); return 1; }
    SERVICE_STATUS st{};
    ControlService(s, SERVICE_CONTROL_STOP, &st);
    for (int i = 0; i < 50 && QueryServiceStatus(s, &st) && st.dwCurrentState != SERVICE_STOPPED; i++) Sleep(200);
    BOOL ok = DeleteService(s);
    CloseServiceHandle(s);
    wchar_t exe[MAX_PATH];
    ExpandEnvironmentStringsW(L"%ProgramFiles%\\WsaReborn\\wsa-reborn-host.exe", exe, MAX_PATH);
    BOOL gone = DeleteFileW(exe) || GetLastError() == ERROR_FILE_NOT_FOUND;
    for (const wchar_t* name : {L"config.json", L"shares.txt"})
        DeleteFileW((std::wstring(exe, wcsrchr(exe, L'\\')) + L"\\" + name).c_str());
    std::printf("service %ls removed: %s, %ls deleted: %s\n", kSvcName, ok ? "ok" : "failed", exe, gone ? "ok" : "no (still running?)");
    return ok ? 0 : 1;
}

static bool ProcessAlive(HANDLE p)
{
    DWORD code;
    DWORD w = WaitForSingleObject(p, 0);
    if (w != WAIT_FAILED) return w == WAIT_TIMEOUT;
    return GetExitCodeProcess(p, &code) && code == STILL_ACTIVE;
}

// "devicehost client": this process hosts wsldevicehost.dll for the service — one IPlan9FileSystem per files/sections
// share, marshaled to devhost-<tag>.objref. The objects stay alive with the process (the VM's shares go with it).
static void ClientDeviceHost()
{
    std::ifstream f("shares.txt");
    CLSID clsid = {0x5A167A26, 0x2D39, 0x4456, {0xB8, 0x02, 0x61, 0xF1, 0xAB, 0xE8, 0x3D, 0x84}};
    IClassFactory* factory = nullptr;
    std::vector<std::string> tags;
    for (std::string line; std::getline(f, line);)
    {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        std::istringstream in(line);
        std::string first, kind;
        if (!(in >> first) || first[0] == '#') continue;
        if (first == "clsid") { std::string g; in >> g; CLSIDFromString(Widen(g).c_str(), &clsid); continue; }
        if (first == "dll")
        {
            std::string path;
            std::getline(in >> std::ws, path);
            using Fn = HRESULT(STDAPICALLTYPE*)(REFCLSID, REFIID, LPVOID*);
            HMODULE m = LoadLibraryW(Widen(path).c_str());
            Fn get = m ? (Fn)GetProcAddress(m, "DllGetClassObject") : nullptr;
            HRESULT hr = get ? get(clsid, __uuidof(IClassFactory), (void**)&factory) : HRESULT_FROM_WIN32(GetLastError());
            std::printf("client device host: %s: 0x%08lx\n", path.c_str(), hr);
            continue;
        }
        if (in >> kind && (kind == "files" || kind == "sections")) tags.push_back(first);
    }
    if (!factory) { std::printf("client device host: no \"dll\" line — the service gets no shares\n"); return; }
    for (auto& tag : tags)
    {
        IPlan9FileSystem* fs = nullptr;
        HRESULT hr = factory->CreateInstance(nullptr, __uuidof(IPlan9FileSystem), (void**)&fs);
        IStream* st = nullptr;
        if (SUCCEEDED(hr)) hr = CreateStreamOnHGlobal(nullptr, TRUE, &st);
        if (SUCCEEDED(hr)) hr = CoMarshalInterface(st, __uuidof(IPlan9FileSystem), fs, MSHCTX_LOCAL, nullptr, MSHLFLAGS_NORMAL);
        if (SUCCEEDED(hr))
        {
            HGLOBAL mem;
            GetHGlobalFromStream(st, &mem);
            STATSTG stat;
            st->Stat(&stat, STATFLAG_NONAME);
            std::string name = "devhost-" + tag + ".objref";
            { std::ofstream(name + ".tmp", std::ios::binary).write((const char*)GlobalLock(mem), (std::streamsize)stat.cbSize.QuadPart); }
            GlobalUnlock(mem);
            MoveFileExW(Widen(name + ".tmp").c_str(), Widen(name).c_str(), MOVEFILE_REPLACE_EXISTING);
        }
        if (st) st->Release();
        std::printf("client device host: %s: 0x%08lx\n", tag.c_str(), hr);
    }
}

// Starts "<this exe> --client" in the exe's folder with only the given output handles inherited: sockets are
// inheritable by default, and a client restarted with every handle kept the old one's hvsocket listeners — its own
// bind failed with WSAEADDRINUSE and the guest never got its host (seen 15.10.2026). Console handles are not passed.
static bool SpawnClient(HANDLE out, HANDLE err, DWORD flags, DWORD* pid = nullptr)
{
    wchar_t self[MAX_PATH];
    GetModuleFileNameW(nullptr, self, MAX_PATH);
    std::wstring dir(self, wcsrchr(self, L'\\'));
    std::vector<HANDLE> list;
    for (HANDLE h : {out, err})
        if (h && h != INVALID_HANDLE_VALUE && GetFileType(h) != FILE_TYPE_CHAR && std::find(list.begin(), list.end(), h) == list.end())
        {
            SetHandleInformation(h, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT);
            list.push_back(h);
        }
    STARTUPINFOEXW si{{sizeof si}};
    if (!list.empty())
    {
        si.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
        si.StartupInfo.hStdOutput = out, si.StartupInfo.hStdError = err;
    }
    SIZE_T size = 0;
    InitializeProcThreadAttributeList(nullptr, 1, 0, &size);
    std::vector<char> attrs(size);
    si.lpAttributeList = (LPPROC_THREAD_ATTRIBUTE_LIST)attrs.data();
    InitializeProcThreadAttributeList(si.lpAttributeList, 1, 0, &size);
    if (!list.empty()) UpdateProcThreadAttribute(si.lpAttributeList, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, list.data(), list.size() * sizeof(HANDLE), nullptr, nullptr);
    PROCESS_INFORMATION pi{};
    std::wstring cmd = L"\"" + std::wstring(self) + L"\" --client";
    BOOL ok = CreateProcessW(nullptr, cmd.data(), nullptr, nullptr, !list.empty(), flags | EXTENDED_STARTUPINFO_PRESENT, nullptr, dir.c_str(), &si.StartupInfo, &pi);
    DeleteProcThreadAttributeList(si.lpAttributeList);
    if (!ok) return false;
    if (pid) *pid = pi.dwProcessId;
    CloseHandle(pi.hThread), CloseHandle(pi.hProcess);
    return true;
}

// The VM's life by the resources mode (ReadSettings), as WsaClient's IdleChecker: no app window for the idle time —
// "asneeded" stops the VM, "partial" stops it and this client starts again (a fresh VM without apps, as WsaClient
// starts a new partially running instance), "continuous" keeps it. Minimized windows count as open, as in WSA.
// settings.txt is read again every round.
static std::atomic<bool> g_restartClient{false};
static HANDLE g_clientMutex;
static void IdleWatch(bool viaService)
{
    ULONGLONG last = GetTickCount64();
    for (;;)
    {
        Sleep(5000);
        // A changed mode applies at once, as WsaClient resets its IdleChecker when the setting changes.
        ReadSettings();
        ApplyClipSetting();
        if (g_lifecycle == kContinuous) { last = GetTickCount64(); continue; }
        bool windows = false;
        {
            std::lock_guard<std::mutex> l(g_frameLock);
            for (auto& [id, t] : g_tasks) windows |= id > 0 && t.hwnd;
        }
        if (windows) { last = GetTickCount64(); continue; }
        if (GetTickCount64() - last < (ULONGLONG)(g_idleMinutes * 60000)) continue;
        std::printf("lifecycle %s: no app window for %g minutes — %s\n", kLifecycleNames[g_lifecycle], g_idleMinutes,
                    g_lifecycle == kPartial && viaService ? "restarting the VM" : "stopping the VM");
        if (!viaService)  // the agent's VM: the same request as agent-run.sh stop; "partial" cannot start it again
        {
            wchar_t req[MAX_PATH];
            ExpandEnvironmentStringsW(L"%TEMP%\\wsa-reborn-host-agent\\request.txt", req, MAX_PATH);
            std::ofstream(req) << "stop\n";
            return;
        }
        g_restartClient = g_lifecycle == kPartial;
        StopService();
        return;
    }
}

// The separate client (see the header): no HCS, no elevation.
static int ClientMain(unsigned short port)
{
    setvbuf(stdout, nullptr, _IONBF, 0);
    // Started at sign-in (--lifecycle) or by --launch from a shortcut: the host folder is the exe's.
    wchar_t self[MAX_PATH];
    GetModuleFileNameW(nullptr, self, MAX_PATH);
    *wcsrchr(self, L'\\') = 0;
    SetCurrentDirectoryW(self);
    // One client per user session: --launch and the sign-in start may race.
    g_clientMutex = CreateMutexW(nullptr, FALSE, L"Local\\WsaRebornHostClient");
    if (GetLastError() == ERROR_ALREADY_EXISTS) { std::printf("client: already running\n"); return 1; }
    ReadSettings();
    ApplyClipSetting();
    std::printf("client: lifecycle %s, idle %g minutes\n", kLifecycleNames[g_lifecycle], g_idleMinutes);
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    // As the service: the VM worker calls back into the device host ("devicehost client"), and the default COM access
    // permissions of a user process refuse its account (HdvProxyDeviceHost 0x80070005).
    CoInitializeSecurity(nullptr, -1, nullptr, nullptr, RPC_C_AUTHN_LEVEL_DEFAULT, RPC_C_IMP_LEVEL_IMPERSONATE, nullptr, EOAC_STATIC_CLOAKING, nullptr);
    SettingsShortcut();
    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);
    SetConsoleCtrlHandler(OnCtrl, TRUE);
    ReadSharesSettings();
    // runtime.txt of a killed service stays behind: take it only while its process is alive.
    HANDLE service = nullptr;
    std::string id;
    DWORD svcPid = 0;
    bool started = false;
    for (int i = 0; !service && !svcPid; i++)
    {
        if (i == 1200) { std::printf("no running service (runtime.txt) after 10 minutes\n"); return 1; }
        // No running VM after a few seconds: start the installed service, as WsaClient asks WsaService.
        if (i % 20 == 6 && !started && !VmHalfRunning()) started = StartVmService();  // again while a stopping service refuses the start
        // The agent's VM half writes it here, the service's in its own folder (see ConfigFile); a dead one is skipped.
        wchar_t svcRuntime[MAX_PATH];
        ExpandEnvironmentStringsW(L"%ProgramData%\\WsaReborn\\runtime.txt", svcRuntime, MAX_PATH);
        std::ifstream f(i % 2 ? svcRuntime : L"runtime.txt");
        DWORD pid = 0;
        std::string ip, gw;
        if (f >> id >> pid && FAILED(CLSIDFromString(Widen(id).c_str(), &g_runtimeId))) pid = 0;
        f >> ip >> gw;
        // An elevated process may refuse one of the two rights to the plain user; either is enough.
        static const DWORD kAccess[] = {SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION, SYNCHRONIZE, PROCESS_QUERY_LIMITED_INFORMATION};
        for (DWORD access : kAccess)
            if (pid && !service) service = OpenProcess(access, FALSE, pid);
        if (pid && !service && i % 20 == 0) std::printf("client: cannot open the service process %lu (%lu)\n", pid, GetLastError());
        if (service && !ProcessAlive(service)) { CloseHandle(service); service = nullptr; }
        // A LocalSystem service does not open its process to the user: ask the service manager instead.
        if (pid && !service && ServicePid() == pid) svcPid = pid;
        // The live VM half's address for the guest (ResolveNic) wins over shares.txt.
        if ((service || svcPid) && !ip.empty() && ip != "-") g_nicIp = ip, g_nicGateway = gw;
        if (!service && !svcPid) Sleep(500);
    }
    std::printf("client: runtime id %s, guest address %s via %s\n", id.c_str(), g_nicIp.c_str(), g_nicGateway.c_str());
    GuestSide(port);
    if (g_devHost.count("client")) { if (g_devHost.count("acl")) ProcessAclForVm(); ClientDeviceHost(); }
    DWORD session = 0;
    ProcessIdToSessionId(GetCurrentProcessId(), &session);
    std::ofstream("client.ready.tmp") << GetCurrentProcessId() << " " << session << " " << UserSid() << "\n";
    MoveFileExW(L"client.ready.tmp", L"client.ready", MOVEFILE_REPLACE_EXISTING);
    std::thread(IdleWatch, svcPid != 0 || ServicePid() != 0).detach();
    if (service)
        while (ProcessAlive(service)) WaitForSingleObject(service, 1000) == WAIT_FAILED && (Sleep(1000), true);
    else
        while (ServicePid() == svcPid) Sleep(1000);
    std::printf("client: the service has exited\n");
    if (g_trayAdded) { NOTIFYICONDATAW n = TrayData(); Shell_NotifyIconW(NIM_DELETE, &n); }
    if (g_restartClient)
    {
        // A new client with the same output (the default port only): it starts the service again a few seconds later.
        CloseHandle(g_clientMutex);
        DWORD pid = 0;
        bool ok = SpawnClient(GetStdHandle(STD_OUTPUT_HANDLE), GetStdHandle(STD_ERROR_HANDLE), 0, &pid);
        std::printf("client: started a new client: %s (%lu)\n", ok ? "ok" : "failed", ok ? pid : GetLastError());
    }
    return 0;
}

// "--launch <package>" (the target of the shortcuts in apps\): ask the running client to start the app. With no client
// (the VM is off — "asneeded" after the idle time, or not started yet) start one, as a WSA shortcut starts WsaClient
// and the VM, and ask again until the guest's Intents channel is up.
static int LaunchApp(const wchar_t* package)
{
    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);
    std::string line = "launch " + Narrow(package) + "\n";
    bool spawned = false;
    for (ULONGLONG start = GetTickCount64(); GetTickCount64() - start < 240000; Sleep(2000))
    {
        SOCKET c = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        sockaddr_in a{};
        a.sin_family = AF_INET;
        a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        a.sin_port = htons(58610);
        char buf[256];
        int n = -1;
        if (!connect(c, (sockaddr*)&a, sizeof a))
        {
            send(c, line.data(), (int)line.size(), 0);
            n = recv(c, buf, sizeof buf - 1, 0);
            if (n > 0) buf[n] = 0;
        }
        closesocket(c);
        if (n > 0 && (!strncmp(buf, "ok", 2) || !strstr(buf, "no Intents channel"))) { std::printf("%s", buf); return strncmp(buf, "ok", 2) ? 1 : 0; }
        if (n < 0 && !spawned)
        {
            // The client of the exe's folder, its log in client-out.txt there.
            wchar_t self[MAX_PATH];
            GetModuleFileNameW(nullptr, self, MAX_PATH);
            std::wstring log = std::wstring(self, wcsrchr(self, L'\\')) + L"\\client-out.txt";
            HANDLE out = CreateFileW(log.c_str(), GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, CREATE_ALWAYS, 0, nullptr);
            spawned = out != INVALID_HANDLE_VALUE && SpawnClient(out, out, CREATE_NO_WINDOW);
            std::printf("the host is not running — starting the client: %s\n", spawned ? "ok" : "failed");
            if (out != INVALID_HANDLE_VALUE) CloseHandle(out);
            if (!spawned) return 1;
        }
    }
    std::printf("the app was not launched in 4 minutes\n");
    return 1;
}

// "--config <install-dir> <data-dir> [mem-MB=4096] [cpus=4]": config.json in the current folder without WSL — the same
// document as make-config.sh (kept in step with it): WSA's kernel and initrd (or the folder's own, see below), six SCSI disks in WSA's LUN order
// (system, vendor, system_ext, product, userdata.2, metadata.2), the consoles on named pipes, hvsockets for SYSTEM,
// administrators and the current user, the high MMIO gap for shared_mem's DAX window.
static std::string JsonStr(const std::string& v)
{
    std::string o = "\"";
    for (char c : v) o += c == '\\' || c == '"' ? std::string("\\") + c : std::string(1, c);
    return o + "\"";
}

static int MakeConfig(const std::string& inst, const std::string& data, int mem, int cpus)
{
    // The headless guest's own parts, when they lie in the current folder: initrd-own.img (guest-init/build.sh, instead
    // of wsainit and its host protocol — then without WSL's initrd=/WSL_ROOT_INIT) and vendor-headless.vhdx
    // (bin/headless-vendor.sh, instead of the install's vendor.vhdx).
    wchar_t cwd[MAX_PATH];
    GetCurrentDirectoryW(MAX_PATH, cwd);
    const std::string here = Narrow(cwd), ownInitrd = here + "\\initrd-own.img", ownVendor = here + "\\vendor-headless.vhdx";
    const bool own = GetFileAttributesW(Widen(ownInitrd).c_str()) != INVALID_FILE_ATTRIBUTES;
    const bool headless = GetFileAttributesW(Widen(ownVendor).c_str()) != INVALID_FILE_ATTRIBUTES;
    const std::string cmdline = std::string(own ? "" : "initrd=\\initrd.img WSL_ROOT_INIT=1 ") + "panic=-1 nr_cpus=" + std::to_string(cpus) +
        " bonding.max_bonds=0 dummy.numdummies=0 fb_tunnels=none console=hvc0 debug earlycon=uart8250,io,0x3f8,115200 pty.legacy_count=0"
        " androidboot.hardware=windows_x86_64 androidboot.veritymode=enforcing androidboot.verifiedbootstate=green loglevel=7"
        " transparent_hugepage=never swiotlb=noforce androidboot.lcd_density=200 androidboot.fstab_suffix=2.windows scsi_mod.scan=async";
    // system.vhdx writable, as WSA attaches it (see make-config.sh).
    const std::pair<std::string, bool> disks[] = {{inst + "\\system.vhdx", false}, {headless ? ownVendor : inst + "\\vendor.vhdx", true}, {inst + "\\system_ext.vhdx", true},
                                                  {inst + "\\product.vhdx", true}, {data + "\\userdata.2.vhdx", false}, {data + "\\metadata.2.vhdx", false}};
    for (auto& [path, ro] : disks)
        if (GetFileAttributesW(Widen(path).c_str()) == INVALID_FILE_ATTRIBUTES) { std::printf("no %s\n", path.c_str()); return 1; }
    const std::string sddl = "D:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;FA;;;" + UserSid() + ")";
    std::string scsi;
    for (int i = 0; i < 6; i++)
        scsi += std::string(i ? "," : "") + "\"" + std::to_string(i) + "\":{\"Type\":\"VirtualDisk\",\"Path\":" + JsonStr(disks[i].first) +
                ",\"ReadOnly\":" + (disks[i].second ? "true" : "false") + ",\"SupportCompressedVolumes\":true,\"AlwaysAllowSparseFiles\":true,\"SupportEncryptedFiles\":true}";
    std::string doc = "{\"Owner\":\"wsa-reborn-host\",\"SchemaVersion\":{\"Major\":2,\"Minor\":3},\"ShouldTerminateOnLastHandleClosed\":true,"
        "\"VirtualMachine\":{\"StopOnReset\":true,\"Chipset\":{\"UseUtc\":true,\"LinuxKernelDirect\":{\"KernelFilePath\":" + JsonStr(inst + "\\Tools\\kernel") +
        ",\"InitRdPath\":" + JsonStr(own ? ownInitrd : inst + "\\Tools\\initrd.img") + ",\"KernelCmdLine\":" + JsonStr(cmdline) + "}},"
        "\"ComputeTopology\":{\"Memory\":{\"SizeInMB\":" + std::to_string(mem) + ",\"AllowOvercommit\":true,\"EnableDeferredCommit\":true,"
        "\"HighMmioGapInMB\":" + std::to_string(16384 + 1024) + ",\"HighMmioBaseInMB\":" + std::to_string(65536 - (16384 + 1024)) + "},"
        "\"Processor\":{\"Count\":" + std::to_string(cpus) + "}},"
        "\"Devices\":{\"Scsi\":{\"0\":{\"Attachments\":{" + scsi + "}}},"
        "\"ComPorts\":{\"0\":{\"NamedPipe\":" + JsonStr(Narrow(kComPipe)) + "}},"
        "\"VirtioSerial\":{\"Ports\":{\"0\":{\"Name\":\"hvc0\",\"NamedPipe\":" + JsonStr(Narrow(kHvcPipe)) + ",\"ConsoleSupport\":true}}},"
        "\"HvSocket\":{\"HvSocketConfig\":{\"DefaultBindSecurityDescriptor\":" + JsonStr(sddl) + ",\"DefaultConnectSecurityDescriptor\":" + JsonStr(sddl) + "}}}}}\n";
    std::ofstream("config.json", std::ios::binary) << doc;
    std::printf("config.json: %s, %s, %d MB, %d processors%s%s\n", inst.c_str(), data.c_str(), mem, cpus,
                own ? ", initrd-own.img" : "", headless ? ", vendor-headless.vhdx" : "");
    return 0;
}

// "--networks" (administrator, as HCN wants): the HCN networks a "nic" line of shares.txt can name — id, name, type,
// subnet and gateway. "Default Switch" (c08cb7b8-9b3c-408e-8e30-5e16a3aeb444 on every machine) works without anything
// else; WSA's own is "WSLCore".
static int ListNetworks()
{
    PWSTR nets = nullptr, err = nullptr;
    HRESULT hr = HcnEnumerateNetworks(L"{\"SchemaVersion\":{\"Major\":2,\"Minor\":0}}", &nets, &err);
    if (FAILED(hr))
    {
        std::printf("HcnEnumerateNetworks: 0x%08lx %s%s\n", hr, err ? Narrow(err).c_str() : "",
                    hr == E_ACCESSDENIED ? "- run it as an administrator" : "");
        return 1;
    }
    std::string list = Narrow(nets);
    std::smatch m;
    for (auto it = list.cbegin(); std::regex_search(it, list.cend(), m, std::regex("[0-9A-Fa-f-]{36}")); it = m.suffix().first)
    {
        GUID g;
        if (FAILED(CLSIDFromString(Widen("{" + m.str() + "}").c_str(), &g))) continue;
        HCN_NETWORK n = nullptr;
        PWSTR props = nullptr, perr = nullptr;
        if (FAILED(HcnOpenNetwork(g, &n, &perr))) continue;
        HcnQueryNetworkProperties(n, L"{\"SchemaVersion\":{\"Major\":2,\"Minor\":0}}", &props, &perr);
        HcnCloseNetwork(n);
        std::string p = props ? Narrow(props) : "";
        auto field = [&](const char* re) { std::smatch f; return std::regex_search(p, f, std::regex(re)) ? f[1].str() : std::string("-"); };
        std::printf("%s  %-24s %-10s %-18s gateway %s\n", m.str().c_str(), field("\"Name\"\\s*:\\s*\"([^\"]*)\"").c_str(),
                    field("\"Type\"\\s*:\\s*\"([^\"]*)\"").c_str(), field("\"IpAddressPrefix\"\\s*:\\s*\"([^\"]*)\"").c_str(),
                    field("\"NextHop\"\\s*:\\s*\"([^\"]*)\"").c_str());
    }
    return 0;
}

// settings.txt next to the exe, rewritten with the given keys: their active lines are replaced in place (the first
// one; repeats are dropped), missing keys are appended, comments and other lines stay. An empty value removes the key.
static std::wstring ExeDir()
{
    wchar_t self[MAX_PATH];
    GetModuleFileNameW(nullptr, self, MAX_PATH);
    return std::wstring(self, wcsrchr(self, L'\\'));
}

static bool WriteSettings(const std::map<std::string, std::string>& values)
{
    const std::wstring path = ExeDir() + L"\\settings.txt";
    std::vector<std::string> lines;
    std::set<std::string> done;
    {
        std::ifstream f(path);
        for (std::string l; std::getline(f, l);)
        {
            if (!l.empty() && l.back() == '\r') l.pop_back();
            std::istringstream in(l);
            std::string key;
            in >> key;
            auto v = values.find(key);
            if (v == values.end()) { lines.push_back(l); continue; }
            if (done.insert(key).second && !v->second.empty()) lines.push_back(key + " " + v->second);
        }
    }
    for (auto& [key, value] : values)
        if (!done.count(key) && !value.empty()) lines.push_back(key + " " + value);
    std::ofstream f(path);
    for (auto& l : lines) f << l << "\n";
    return (bool)f;
}

// The start at sign-in that "continuous" and "partial" have in WSA (the package's startup tasks): a Run value of the
// current user that starts the client.
static LSTATUS SetSignInStart(bool on)
{
    HKEY run;
    LSTATUS rc = RegOpenKeyExW(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\CurrentVersion\\Run", 0, KEY_SET_VALUE, &run);
    if (rc != ERROR_SUCCESS) return rc;
    wchar_t self[MAX_PATH];
    GetModuleFileNameW(nullptr, self, MAX_PATH);
    const std::wstring cmd = L"\"" + std::wstring(self) + L"\" --client";
    rc = on ? RegSetValueExW(run, L"WsaRebornHost", 0, REG_SZ, (const BYTE*)cmd.c_str(), (DWORD)(cmd.size() + 1) * sizeof(wchar_t))
            : RegDeleteValueW(run, L"WsaRebornHost");
    if (rc == ERROR_FILE_NOT_FOUND) rc = ERROR_SUCCESS;
    RegCloseKey(run);
    return rc;
}

// "--lifecycle continuous|asneeded|partial": the "lifecycle" line of settings.txt and the start at sign-in.
static int SetLifecycle(const wchar_t* mode)
{
    int m = -1;
    for (int i = 0; i < 3; i++)
        if (Narrow(mode) == kLifecycleNames[i]) m = i;
    if (m < 0) { std::printf("lifecycle: continuous, asneeded or partial\n"); return 2; }
    bool written = WriteSettings({{"lifecycle", kLifecycleNames[m]}});
    LSTATUS rc = SetSignInStart(m != kAsNeeded);
    std::printf("lifecycle %s: settings.txt %s, start at sign-in %s: %s\n", kLifecycleNames[m], written ? "written" : "NOT written",
                m == kAsNeeded ? "removed" : "set", rc ? "FAILED" : "ok");
    return rc || !written ? 1 : 0;
}

// "--settings": the settings window, as WSA's "Advanced settings" → "Memory and performance" (the owner's wish of
// 15.10.2026): the resources mode, the idle time, the memory, the shared clipboard, the subsystem's state with "Turn
// off". It edits settings.txt next to the exe through WriteSettings — what the client re-reads every 5 seconds — and the
// start at sign-in as --lifecycle does. "--settings-test <lifecycle> <idle> <memory> <clipboard>" runs the same window
// hidden, prints what it loaded, sets the controls, presses Save and prints the file: a check without a window on the
// desktop. 16.10.2026.
enum { kIdRadio = 100, kIdIdle = 110, kIdMemory = 111, kIdClip = 112, kIdState = 113, kIdTurnOff = 114, kIdSave = IDOK, kIdClose = IDCANCEL };
static const wchar_t* const kLifecycleLabels[] = {
    L"Continuous — the subsystem runs from sign-in and is always ready",
    L"As needed — starts with an app, turns off when no app is open",
    L"Partially running — starts at sign-in, restarts when no app is open"};
static const int kMemoryGb[] = {2, 3, 4, 6, 8, 12, 16};  // the combo: [0] from config.json, [1] default, then these
static bool g_settingsTest;  // --settings-test: messages to stdout, no message boxes

static void SettingsMessage(HWND w, const wchar_t* text, UINT icon)
{
    if (g_settingsTest) std::printf("message: %ls\n", text);
    else MessageBoxW(w, text, L"Settings", icon);
}

static int DefaultMemoryGb()
{
    MEMORYSTATUSEX ms{sizeof ms};
    GlobalMemoryStatusEx(&ms);
    const unsigned long long gb = 1ull << 30, ram = ms.ullTotalPhys;
    return ram >= 12 * gb ? 6 : ram >= 8 * gb ? 4 : ram > 4 * gb ? 3 : 2;
}

// The settings.txt lines the window shows (ReadSettings fills the effective values, these the written ones).
static std::map<std::string, std::string> ReadSettingLines()
{
    std::map<std::string, std::string> v;
    std::ifstream f(ExeDir() + L"\\settings.txt");
    for (std::string line; std::getline(f, line);)
    {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        std::istringstream in(line);
        std::string key, value;
        if ((in >> key >> value) && key[0] != '#' && !v.count(key)) v[key] = value;
    }
    return v;
}

static void SettingsLoad(HWND w)
{
    g_userDir = ExeDir();
    ReadSettings();
    auto lines = ReadSettingLines();
    for (int i = 0; i < 3; i++) Button_SetCheck(GetDlgItem(w, kIdRadio + i), g_lifecycle == i ? BST_CHECKED : BST_UNCHECKED);
    wchar_t idle[32];
    swprintf_s(idle, L"%g", g_idleMinutes);
    SetDlgItemTextW(w, kIdIdle, idle);
    int sel = 0;
    if (lines.count("memory"))
    {
        sel = 1;
        if (lines["memory"] != "default")
            for (int i = 0; i < (int)std::size(kMemoryGb); i++)
                if (std::atoi(lines["memory"].c_str()) == kMemoryGb[i]) sel = 2 + i;
    }
    ComboBox_SetCurSel(GetDlgItem(w, kIdMemory), sel);
    Button_SetCheck(GetDlgItem(w, kIdClip), g_clipSetting == 1 ? BST_CHECKED : BST_UNCHECKED);
}

static bool SettingsSave(HWND w)
{
    int m = kAsNeeded;
    for (int i = 0; i < 3; i++)
        if (Button_GetCheck(GetDlgItem(w, kIdRadio + i)) == BST_CHECKED) m = i;
    wchar_t idle[32] = L"";
    GetDlgItemTextW(w, kIdIdle, idle, 32);
    double minutes = _wtof(idle);
    if (minutes <= 0) { SettingsMessage(w, L"The idle time is a number of minutes above 0.", MB_ICONWARNING); return false; }
    char idleText[32];
    std::snprintf(idleText, sizeof idleText, "%g", minutes);
    int sel = ComboBox_GetCurSel(GetDlgItem(w, kIdMemory));
    std::string memory = sel <= 0 ? "" : sel == 1 ? "default" : std::to_string(kMemoryGb[sel - 2]);
    bool ok = WriteSettings({{"lifecycle", kLifecycleNames[m]}, {"idle", idleText}, {"memory", memory},
                             {"clipboard", Button_GetCheck(GetDlgItem(w, kIdClip)) == BST_CHECKED ? "on" : "off"}});
    LSTATUS rc = SetSignInStart(m != kAsNeeded);
    std::printf("settings: settings.txt %s, start at sign-in %s: %s\n", ok ? "written" : "NOT written", m == kAsNeeded ? "removed" : "set", rc ? "FAILED" : "ok");
    if (!ok || rc) SettingsMessage(w, ok ? L"Cannot set the start at sign-in." : L"Cannot write settings.txt.", MB_ICONERROR);
    return ok && !rc;
}

static void SettingsState(HWND w)
{
    const bool running = VmHalfRunning(), service = ServicePid() != 0;
    SetDlgItemTextW(w, kIdState, running ? (service ? L"The subsystem is running." : L"The subsystem is running (not as the service).")
                                         : L"The subsystem is off.");
    EnableWindow(GetDlgItem(w, kIdTurnOff), service);
}

static LRESULT CALLBACK SettingsProc(HWND w, UINT msg, WPARAM wp, LPARAM lp)
{
    switch (msg)
    {
    case WM_COMMAND:
        switch (LOWORD(wp))
        {
        case kIdSave: if (SettingsSave(w)) SettingsLoad(w); return 0;
        case kIdClose: DestroyWindow(w); return 0;
        case kIdTurnOff: StopService(); SettingsState(w); return 0;
        }
        break;
    case WM_TIMER: SettingsState(w); return 0;
    case WM_CLOSE: DestroyWindow(w); return 0;
    case WM_DESTROY: PostQuitMessage(0); return 0;
    }
    return DefWindowProcW(w, msg, wp, lp);
}

static HWND SettingsCreate(bool visible)
{
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_SYSTEM_AWARE);
    const int dpi = (int)GetDpiForSystem();
    auto px = [dpi](int v) { return MulDiv(v, dpi, 96); };
    NONCLIENTMETRICSW nm{sizeof nm};
    SystemParametersInfoW(SPI_GETNONCLIENTMETRICS, sizeof nm, &nm, 0);
    HFONT font = CreateFontIndirectW(&nm.lfMessageFont);
    WNDCLASSW wc{};
    wc.lpfnWndProc = SettingsProc;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.hCursor = LoadCursorW(nullptr, MAKEINTRESOURCEW(32512));
    wc.hbrBackground = (HBRUSH)(COLOR_BTNFACE + 1);
    wc.hIcon = LoadIconW(nullptr, MAKEINTRESOURCEW(32512));
    wc.lpszClassName = L"WsaRebornHostSettings";
    RegisterClassW(&wc);
    RECT r{0, 0, px(520), px(370)};
    const DWORD style = WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX;
    AdjustWindowRectExForDpi(&r, style, FALSE, 0, dpi);
    HWND w = CreateWindowExW(0, wc.lpszClassName, L"WSA-Reborn host — settings", style, CW_USEDEFAULT, CW_USEDEFAULT,
                             r.right - r.left, r.bottom - r.top, nullptr, nullptr, wc.hInstance, nullptr);
    auto add = [&](const wchar_t* cls, const wchar_t* text, DWORD st, int x, int y, int cx, int cy, int id) {
        HWND c = CreateWindowExW(!wcscmp(cls, L"EDIT") ? WS_EX_CLIENTEDGE : 0, cls, text, WS_CHILD | WS_VISIBLE | st, px(x), px(y), px(cx), px(cy), w,
                                 (HMENU)(INT_PTR)id, wc.hInstance, nullptr);
        SendMessageW(c, WM_SETFONT, (WPARAM)font, TRUE);
        return c;
    };
    add(L"BUTTON", L"Resources", BS_GROUPBOX, 12, 8, 496, 112, -1);
    for (int i = 0; i < 3; i++)
        add(L"BUTTON", kLifecycleLabels[i], BS_AUTORADIOBUTTON | WS_TABSTOP | (i ? 0 : WS_GROUP), 24, 30 + i * 28, 476, 24, kIdRadio + i);
    add(L"STATIC", L"Turn off or restart after no app window for (minutes):", SS_LEFT, 12, 136, 340, 20, -1);
    add(L"EDIT", L"", ES_AUTOHSCROLL | WS_TABSTOP | WS_GROUP, 360, 132, 80, 24, kIdIdle);
    add(L"STATIC", L"Memory (applies at the next start):", SS_LEFT, 12, 172, 340, 20, -1);
    HWND combo = add(L"COMBOBOX", L"", CBS_DROPDOWNLIST | WS_VSCROLL | WS_TABSTOP, 360, 168, 148, 200, kIdMemory);
    wchar_t text[64];
    SendMessageW(combo, CB_ADDSTRING, 0, (LPARAM)L"From config.json");
    swprintf_s(text, L"Default (%d GB)", DefaultMemoryGb());
    SendMessageW(combo, CB_ADDSTRING, 0, (LPARAM)text);
    for (int gb : kMemoryGb) swprintf_s(text, L"%d GB", gb), SendMessageW(combo, CB_ADDSTRING, 0, (LPARAM)text);
    add(L"BUTTON", L"Shared clipboard with Windows", BS_AUTOCHECKBOX | WS_TABSTOP, 12, 206, 496, 24, kIdClip);
    add(L"STATIC", L"", SS_LEFT, 12, 252, 340, 20, kIdState);
    add(L"BUTTON", L"Turn off", BS_PUSHBUTTON | WS_TABSTOP, 360, 246, 148, 30, kIdTurnOff);
    add(L"STATIC", L"Saved settings apply to a running client within seconds.", SS_LEFT, 12, 296, 496, 20, -1);
    add(L"BUTTON", L"Save", BS_DEFPUSHBUTTON | WS_TABSTOP, 252, 328, 124, 30, kIdSave);
    add(L"BUTTON", L"Close", BS_PUSHBUTTON | WS_TABSTOP, 384, 328, 124, 30, kIdClose);
    SettingsLoad(w);
    SettingsState(w);
    SetTimer(w, 1, 2000, nullptr);
    if (visible) ShowWindow(w, SW_SHOWNORMAL);
    return w;
}

static int SettingsWindow()
{
    if (HWND old = FindWindowW(L"WsaRebornHostSettings", nullptr)) { ShowWindow(old, SW_RESTORE); SetForegroundWindow(old); return 0; }
    // Started from a shortcut, the console exe gets a console of its own: drop it (a shell's console is shared — keep).
    DWORD pids[2];
    if (GetConsoleProcessList(pids, 2) == 1) FreeConsole();
    HWND w = SettingsCreate(true);
    for (MSG m; GetMessageW(&m, nullptr, 0, 0) > 0;)
        if (!IsDialogMessageW(w, &m)) TranslateMessage(&m), DispatchMessageW(&m);
    return 0;
}

static int SettingsTest(int argc, wchar_t** argv)
{
    if (argc < 6) { std::printf("--settings-test <lifecycle> <idle> <memory: config|default|GB> <clipboard on|off>\n"); return 2; }
    g_settingsTest = true;
    HWND w = SettingsCreate(false);
    auto show = [&](const char* when) {
        int m = -1;
        for (int i = 0; i < 3; i++)
            if (Button_GetCheck(GetDlgItem(w, kIdRadio + i)) == BST_CHECKED) m = i;
        wchar_t idle[32], mem[64], state[128];
        GetDlgItemTextW(w, kIdIdle, idle, 32);
        HWND combo = GetDlgItem(w, kIdMemory);
        SendMessageW(combo, CB_GETLBTEXT, ComboBox_GetCurSel(combo), (LPARAM)mem);
        GetDlgItemTextW(w, kIdState, state, 128);
        std::printf("%s: lifecycle %s, idle %ls, memory %ls, clipboard %s; %ls (Turn off %s)\n", when, m < 0 ? "-" : kLifecycleNames[m], idle, mem,
                    Button_GetCheck(GetDlgItem(w, kIdClip)) == BST_CHECKED ? "on" : "off", state,
                    IsWindowEnabled(GetDlgItem(w, kIdTurnOff)) ? "enabled" : "disabled");
    };
    show("loaded");
    const std::string mode = Narrow(argv[2]), memory = Narrow(argv[4]);
    for (int i = 0; i < 3; i++) Button_SetCheck(GetDlgItem(w, kIdRadio + i), mode == kLifecycleNames[i] ? BST_CHECKED : BST_UNCHECKED);
    SetDlgItemTextW(w, kIdIdle, argv[3]);
    int sel = memory == "config" ? 0 : memory == "default" ? 1 : -1;
    for (int i = 0; i < (int)std::size(kMemoryGb); i++)
        if (std::atoi(memory.c_str()) == kMemoryGb[i]) sel = 2 + i;
    ComboBox_SetCurSel(GetDlgItem(w, kIdMemory), sel);
    Button_SetCheck(GetDlgItem(w, kIdClip), !wcscmp(argv[5], L"on") ? BST_CHECKED : BST_UNCHECKED);
    SendMessageW(w, WM_COMMAND, MAKEWPARAM(kIdSave, BN_CLICKED), 0);
    show("saved");
    std::ifstream f(ExeDir() + L"\\settings.txt");
    std::printf("settings.txt:\n%s", std::string(std::istreambuf_iterator<char>(f), {}).c_str());
    DestroyWindow(w);
    return 0;
}

int wmain(int argc, wchar_t** argv)
{
    if (argc == 3 && !wcscmp(argv[1], L"--launch")) return LaunchApp(argv[2]);
    if (argc == 3 && !wcscmp(argv[1], L"--lifecycle")) return SetLifecycle(argv[2]);
    if (argc == 2 && !wcscmp(argv[1], L"--settings")) return SettingsWindow();
    if (argc >= 2 && !wcscmp(argv[1], L"--settings-test")) return SettingsTest(argc, argv);
    if (argc == 2 && !wcscmp(argv[1], L"--networks")) return ListNetworks();
    if (argc >= 4 && !wcscmp(argv[1], L"--config"))
        return MakeConfig(Narrow(argv[2]), Narrow(argv[3]), argc > 4 ? _wtoi(argv[4]) : 4096, argc > 5 ? _wtoi(argv[5]) : 4);
    if (argc >= 2 && !wcscmp(argv[1], L"--client")) return ClientMain(argc > 2 ? (unsigned short)_wtoi(argv[2]) : 58600);
    if (argc >= 2 && !wcscmp(argv[1], L"--install")) return Install(argc > 2 ? argv[2] : nullptr);
    if (argc >= 2 && !wcscmp(argv[1], L"--uninstall")) return Uninstall();
    if (argc >= 2 && !wcscmp(argv[1], L"--stop")) return StopService();
    if (argc == 3 && !wcscmp(argv[1], L"--service"))
    {
        g_svcDir = argv[2];
        SERVICE_TABLE_ENTRYW table[] = {{(LPWSTR)kSvcName, SvcMain}, {nullptr, nullptr}};
        return StartServiceCtrlDispatcherW(table) ? 0 : 1;
    }
    if (argc < 2) { std::printf("usage: wsa-reborn-host <config.json> [adb-port] [console-log] | --client [adb-port] | --launch <package> | --lifecycle <mode> | --settings | --config <install-dir> <data-dir> [mem-MB] [cpus] | --networks | --install [folder] | --uninstall | --stop\n"); return 2; }
    setvbuf(stdout, nullptr, _IONBF, 0);
    SetConsoleCtrlHandler(OnCtrl, TRUE);
    return RunVm(argv[1], argc > 2 ? (unsigned short)_wtoi(argv[2]) : 58600, argc > 3 ? Narrow(argv[3]) : "wsa-reborn-host-console.log");
}

// The VM half (the whole host without "client separate"): blocks while the VM runs.
static int RunVm(const wchar_t* config, unsigned short port, std::string log)
{
    // Seen by a client before runtime.txt exists (VmHalfRunning): a client started together with the agent's VM
    // started the service as well, and the service took the agent's VM for a stale one and terminated it (15.10.2026).
    CreateMutexW(nullptr, FALSE, kVmMarker);
    std::ifstream f(ConfigFile(config), std::ios::binary);
    std::stringstream ss;
    ss << f.rdbuf();
    std::string json = ss.str();
    if (json.empty()) { std::printf("cannot read %ls\n", config); return 1; }

    ReadSettings();
    if (g_memoryMb)
    {
        json = std::regex_replace(json, std::regex("(\"SizeInMB\"\\s*:\\s*)[0-9]+"), "$01" + std::to_string(g_memoryMb), std::regex_constants::format_first_only);
        std::printf("memory: %d MB (settings.txt)\n", g_memoryMb);
    }

    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    // As WSL's service: default access checks, the device host calls back into this process.
    CoInitializeSecurity(nullptr, -1, nullptr, nullptr, RPC_C_AUTHN_LEVEL_DEFAULT, RPC_C_IMP_LEVEL_IMPERSONATE, nullptr, EOAC_STATIC_CLOAKING, nullptr);
    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);
    ReadSharesSettings();
    if (g_svc && !g_separateClient) { g_separateClient = true; if (!g_devHost.count("service")) g_devHost.insert("client"); }  // a service has no desktop
    ResolveNic();
    DeleteFileW(L"runtime.txt");

    // A previous run may have left the system behind (the process was killed) — terminate it first.
    if (HCS_SYSTEM old = nullptr; SUCCEEDED(HcsOpenComputeSystem(kVmId, GENERIC_ALL, &old)))
    {
        HCS_OPERATION op = HcsCreateOperation(nullptr, nullptr);
        HcsTerminateComputeSystem(old, op, nullptr);
        Wait(op, "terminate stale");
        HcsCloseComputeSystem(old);
    }

    std::regex pathRe("\"(?:KernelFilePath|InitRdPath|Path)\"\\s*:\\s*\"([^\"]+)\"");
    for (std::sregex_iterator i(json.begin(), json.end(), pathRe), e; i != e; ++i)
    {
        std::string p = std::regex_replace((*i)[1].str(), std::regex("\\\\\\\\"), "\\");
        HRESULT hr = HcsGrantVmAccess(kVmId, Widen(p).c_str());
        std::printf("grant %s: 0x%08lx\n", p.c_str(), hr);
    }

    std::thread(Console, kComPipe, log + ".com1").detach();
    std::thread(Console, kHvcPipe, log).detach();
    Sleep(200);

    HCS_OPERATION op = HcsCreateOperation(nullptr, nullptr);
    HRESULT hr = HcsCreateComputeSystem(kVmId, Widen(json).c_str(), op, nullptr, &g_system);
    if (FAILED(hr)) { std::printf("HcsCreateComputeSystem: 0x%08lx\n", hr); return 1; }
    if (FAILED(Wait(op, "create"))) return 1;

    std::string props;
    op = HcsCreateOperation(nullptr, nullptr);
    HcsGetComputeSystemProperties(g_system, op, nullptr);
    Wait(op, "properties", &props);
    std::smatch m;
    GUID& runtimeId = g_runtimeId;
    if (!std::regex_search(props, m, std::regex("\"RuntimeId\"\\s*:\\s*\"\\{?([0-9a-fA-F-]{36})")) ||
        FAILED(CLSIDFromString(Widen("{" + m[1].str() + "}").c_str(), &runtimeId)))
    {
        std::printf("no RuntimeId in %s\n", props.c_str());
        return 1;
    }
    std::printf("runtime id {%s}\n", m[1].str().c_str());
    // Like WslCoreVm: listen before the start, the guest connects within the first seconds.
    InitListen(runtimeId);
    if (g_separateClient)
    {
        // As WsaService hands the VM to WsaClient: the client listens on the guest's sockets before the start.
        FILETIME since;
        GetSystemTimeAsFileTime(&since);
        std::ofstream("runtime.txt") << "{" << m[1].str() << "} " << GetCurrentProcessId() << " " << (g_nicIp.empty() ? "-" : g_nicIp) << " "
                                     << (g_nicGateway.empty() ? "-" : g_nicGateway) << "\n";
        std::printf("waiting for the client (wsa-reborn-host.exe --client, not elevated) — client.ready\n");
        // Only a client.ready written after runtime.txt (since): the service does not delete in the user's folder.
        int i = 0;
        for (; i < 1200; i++)
        {
            WIN32_FILE_ATTRIBUTE_DATA a;
            if (!GetFileAttributesExW(UserFile(L"client.ready").c_str(), GetFileExInfoStandard, &a) || CompareFileTime(&a.ftLastWriteTime, &since) < 0) { Sleep(100); continue; }
            std::ifstream r(UserFile(L"client.ready"));
            DWORD pid = 0, session = 0;
            std::string sid;
            if (r >> pid >> session >> sid) { g_clientSession = session; g_clientSid = sid; break; }
            Sleep(100);
        }
        if (i < 1200) std::printf("client ready: session %lu, %s\n", g_clientSession, g_clientSid.c_str());
        else std::printf("no client after 2 minutes, starting anyway\n");
    }
    else
        GuestSide(port);

    op = HcsCreateOperation(nullptr, nullptr);
    HcsStartComputeSystem(g_system, op, nullptr);
    if (FAILED(Wait(op, "start"))) return 1;
    // Like WslCoreVm::InitializeGuest: virtio devices are hot-added to the running VM.
    AddShares();
    std::printf("console -> %s, init protocol -> init.log; Ctrl+C to stop\n", log.c_str());
    Relay(runtimeId, port);
    return 0;
}
