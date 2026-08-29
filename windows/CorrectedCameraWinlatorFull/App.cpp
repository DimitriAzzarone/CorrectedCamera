
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <objidl.h>
#include <ole2.h>
#include <gdiplus.h>
#include <dshow.h>
#include <strsafe.h>
#include <atomic>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <algorithm>
#include <cmath>
#include <memory>

#include "SampleGrabberCompat.h"
#include "SharedFrame.h"

#pragma comment(lib, "strmiids.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "oleaut32.lib")
#pragma comment(lib, "gdi32.lib")
#pragma comment(lib, "user32.lib")

static HWND gWnd = nullptr;
static HWND gOverlay = nullptr;
static HINSTANCE gInst = nullptr;
static bool gOverlayVisible = false;
static bool gOverlayRound = true;
static bool gOverlayLarge = false;
static HWND gStatus = nullptr;
static HWND gHostEdit = nullptr;
static std::thread gStreamThread;
static std::atomic<bool> gStreamStop{false};
static SOCKET gStreamSocket = INVALID_SOCKET;
static ULONG_PTR gGdiplusToken = 0;
static HBITMAP gPreview = nullptr;
static std::mutex gPreviewMutex;
static int gRotation = 0;
static int gCameraIndex = 0;
static HANDLE gMap = nullptr;
static SharedFrameBlock* gShared = nullptr;

static IGraphBuilder* gGraph = nullptr;
static IMediaControl* gControl = nullptr;
static ISampleGrabber* gGrabber = nullptr;
static IBaseFilter* gSource = nullptr;
static IBaseFilter* gGrabberFilter = nullptr;
static IBaseFilter* gNull = nullptr;
static int gSrcW = 0, gSrcH = 0;
static bool gBottomUp = true;

static const UINT WM_FRAME = WM_APP + 1;

struct CameraDevice {
    std::wstring name;
    IMoniker* moniker{};
};

static std::vector<CameraDevice> EnumerateCameras() {
    std::vector<CameraDevice> out;
    ICreateDevEnum* dev = nullptr;
    IEnumMoniker* en = nullptr;
    if (FAILED(CoCreateInstance(CLSID_SystemDeviceEnum, nullptr, CLSCTX_INPROC_SERVER,
                                IID_PPV_ARGS(&dev)))) return out;
    if (dev->CreateClassEnumerator(CLSID_VideoInputDeviceCategory, &en, 0) != S_OK) {
        dev->Release();
        return out;
    }

    IMoniker* m = nullptr;
    ULONG got = 0;
    while (en->Next(1, &m, &got) == S_OK) {
        std::wstring name = L"Camera";
        IPropertyBag* bag = nullptr;
        if (SUCCEEDED(m->BindToStorage(nullptr, nullptr, IID_PPV_ARGS(&bag)))) {
            VARIANT v; VariantInit(&v);
            if (SUCCEEDED(bag->Read(L"FriendlyName", &v, nullptr)) && v.vt == VT_BSTR)
                name = v.bstrVal;
            VariantClear(&v);
            bag->Release();
        }
        m->AddRef();
        out.push_back({name, m});
        m->Release();
    }
    en->Release();
    dev->Release();
    return out;
}

static void FreeDevices(std::vector<CameraDevice>& v) {
    for (auto& d : v) if (d.moniker) d.moniker->Release();
    v.clear();
}

static void WriteStatus(const std::wstring& s) {
    if (gStatus) SetWindowTextW(gStatus, s.c_str());
}

static void CreatePreviewBitmap(const unsigned char* bgr) {
    BITMAPINFO bi{};
    bi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    bi.bmiHeader.biWidth = kOutW;
    bi.bmiHeader.biHeight = -kOutH;
    bi.bmiHeader.biPlanes = 1;
    bi.bmiHeader.biBitCount = 24;
    bi.bmiHeader.biCompression = BI_RGB;

    void* bits = nullptr;
    HDC dc = GetDC(nullptr);
    HBITMAP bmp = CreateDIBSection(dc, &bi, DIB_RGB_COLORS, &bits, nullptr, 0);
    ReleaseDC(nullptr, dc);
    if (!bmp || !bits) return;

    // DIB scanlines are DWORD aligned; 640*3 is already aligned.
    memcpy(bits, bgr, kPixelBytes);

    std::lock_guard<std::mutex> lock(gPreviewMutex);
    if (gPreview) DeleteObject(gPreview);
    gPreview = bmp;
}


static void PublishFrame(const std::vector<BYTE>& frame) {
    if (frame.size() < kPixelBytes) return;

    if (gShared) {
        InterlockedExchange(&gShared->h.writing, 1);
        memcpy(gShared->pixels, frame.data(), kPixelBytes);
        gShared->h.magic = kMagic;
        gShared->h.width = kOutW;
        gShared->h.height = kOutH;
        gShared->h.stride = kOutStride;
        gShared->h.frameNo++;
        MemoryBarrier();
        InterlockedExchange(&gShared->h.writing, 0);
    }

    CreatePreviewBitmap(frame.data());
    if (gWnd) PostMessageW(gWnd, WM_FRAME, 0, 0);
}

static inline void SampleNearest(const BYTE* src, int sw, int sh, int sstride, bool bottomUp,
                                 int x, int y, BYTE* dst) {
    x = std::clamp(x, 0, sw - 1);
    y = std::clamp(y, 0, sh - 1);
    int sy = bottomUp ? (sh - 1 - y) : y;
    const BYTE* p = src + sy * sstride + x * 3;
    dst[0] = p[0]; dst[1] = p[1]; dst[2] = p[2];
}

static void RotateScaleLetterbox(const BYTE* src, int sw, int sh, bool bottomUp,
                                 int rotation, std::vector<BYTE>& out) {
    out.assign(kPixelBytes, 0);
    const int sstride = ((sw * 3 + 3) / 4) * 4;

    int rw = (rotation == 90 || rotation == 270) ? sh : sw;
    int rh = (rotation == 90 || rotation == 270) ? sw : sh;

    double scale = std::min((double)kOutW / rw, (double)kOutH / rh);
    int dw = std::max(1, (int)std::lround(rw * scale));
    int dh = std::max(1, (int)std::lround(rh * scale));
    int ox = (kOutW - dw) / 2;
    int oy = (kOutH - dh) / 2;

    for (int dy = 0; dy < dh; ++dy) {
        for (int dx = 0; dx < dw; ++dx) {
            double rx = dx / scale;
            double ry = dy / scale;
            int sx = 0, sy = 0;

            switch (rotation) {
            case 90:
                sx = (int)std::lround(ry);
                sy = sh - 1 - (int)std::lround(rx);
                break;
            case 180:
                sx = sw - 1 - (int)std::lround(rx);
                sy = sh - 1 - (int)std::lround(ry);
                break;
            case 270:
                sx = sw - 1 - (int)std::lround(ry);
                sy = (int)std::lround(rx);
                break;
            default:
                sx = (int)std::lround(rx);
                sy = (int)std::lround(ry);
                break;
            }

            BYTE* d = out.data() + ((oy + dy) * kOutW + (ox + dx)) * 3;
            SampleNearest(src, sw, sh, sstride, bottomUp, sx, sy, d);
        }
    }
}

class GrabberCB final : public ISampleGrabberCB {
    std::atomic<ULONG> ref{1};
public:
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        *ppv = nullptr;
        if (riid == IID_IUnknown || riid == __uuidof(ISampleGrabberCB)) {
            *ppv = static_cast<ISampleGrabberCB*>(this); AddRef(); return S_OK;
        }
        return E_NOINTERFACE;
    }
    STDMETHODIMP_(ULONG) AddRef() override { return ++ref; }
    STDMETHODIMP_(ULONG) Release() override {
        ULONG r = --ref; if (!r) delete this; return r;
    }
    STDMETHODIMP SampleCB(double, IMediaSample*) override { return E_NOTIMPL; }

    STDMETHODIMP BufferCB(double, BYTE* pBuffer, long BufferLen) override {
        if (!pBuffer || gSrcW <= 0 || gSrcH <= 0) return S_OK;
        int expectedMin = gSrcW * gSrcH * 3;
        if (BufferLen < expectedMin) return S_OK;

        std::vector<BYTE> frame;
        RotateScaleLetterbox(pBuffer, gSrcW, gSrcH, gBottomUp, gRotation, frame);

        PublishFrame(frame);
        return S_OK;
    }
};

static GrabberCB* gCB = nullptr;

static void StopGraph() {
    if (gControl) gControl->Stop();
    if (gGrabber) gGrabber->SetCallback(nullptr, 0);
    if (gCB) { gCB->Release(); gCB = nullptr; }
    if (gGrabber) { gGrabber->Release(); gGrabber = nullptr; }
    if (gControl) { gControl->Release(); gControl = nullptr; }
    if (gNull) { gNull->Release(); gNull = nullptr; }
    if (gGrabberFilter) { gGrabberFilter->Release(); gGrabberFilter = nullptr; }
    if (gSource) { gSource->Release(); gSource = nullptr; }
    if (gGraph) { gGraph->Release(); gGraph = nullptr; }
}

static HRESULT StartCamera(int index) {
    StopGraph();

    auto devices = EnumerateCameras();
    if (devices.empty()) {
        WriteStatus(L"Nessuna camera DirectShow visibile in Winlator/Wine.");
        return VFW_E_NOT_FOUND;
    }
    if (index < 0 || index >= (int)devices.size()) index = 0;
    gCameraIndex = index;

    HRESULT hr = CoCreateInstance(CLSID_FilterGraph, nullptr, CLSCTX_INPROC_SERVER,
                                  IID_PPV_ARGS(&gGraph));
    if (FAILED(hr)) { FreeDevices(devices); return hr; }

    ICaptureGraphBuilder2* builder = nullptr;
    hr = CoCreateInstance(CLSID_CaptureGraphBuilder2, nullptr, CLSCTX_INPROC_SERVER,
                          IID_PPV_ARGS(&builder));
    if (FAILED(hr)) { FreeDevices(devices); StopGraph(); return hr; }
    builder->SetFiltergraph(gGraph);

    hr = devices[index].moniker->BindToObject(nullptr, nullptr, IID_PPV_ARGS(&gSource));
    if (FAILED(hr)) { builder->Release(); FreeDevices(devices); StopGraph(); return hr; }

    hr = CoCreateInstance(CLSID_SampleGrabber, nullptr, CLSCTX_INPROC_SERVER,
                          IID_PPV_ARGS(&gGrabberFilter));
    if (FAILED(hr)) { builder->Release(); FreeDevices(devices); StopGraph(); return hr; }

    hr = gGrabberFilter->QueryInterface(__uuidof(ISampleGrabber), (void**)&gGrabber);
    if (FAILED(hr)) { builder->Release(); FreeDevices(devices); StopGraph(); return hr; }

    AM_MEDIA_TYPE mt{};
    mt.majortype = MEDIATYPE_Video;
    mt.subtype = MEDIASUBTYPE_RGB24;
    mt.formattype = FORMAT_VideoInfo;
    gGrabber->SetMediaType(&mt);
    gGrabber->SetOneShot(FALSE);
    gGrabber->SetBufferSamples(FALSE);

    hr = CoCreateInstance(CLSID_NullRenderer, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&gNull));
    if (FAILED(hr)) { builder->Release(); FreeDevices(devices); StopGraph(); return hr; }

    gGraph->AddFilter(gSource, L"Physical Camera");
    gGraph->AddFilter(gGrabberFilter, L"CorrectedCamera Processor");
    gGraph->AddFilter(gNull, L"Null Renderer");

    hr = builder->RenderStream(&PIN_CATEGORY_CAPTURE, &MEDIATYPE_Video,
                               gSource, gGrabberFilter, gNull);
    builder->Release();
    if (FAILED(hr)) {
        FreeDevices(devices);
        StopGraph();
        WriteStatus(L"Camera trovata ma Winlator/Wine non riesce ad aprire il flusso DirectShow.");
        return hr;
    }

    AM_MEDIA_TYPE conn{};
    hr = gGrabber->GetConnectedMediaType(&conn);
    if (SUCCEEDED(hr) && conn.formattype == FORMAT_VideoInfo && conn.pbFormat) {
        auto* vih = reinterpret_cast<VIDEOINFOHEADER*>(conn.pbFormat);
        gSrcW = vih->bmiHeader.biWidth;
        LONG h = vih->bmiHeader.biHeight;
        gBottomUp = h > 0;
        gSrcH = std::abs(h);
    }
    if (conn.cbFormat && conn.pbFormat) CoTaskMemFree(conn.pbFormat);
    if (conn.pUnk) conn.pUnk->Release();

    gCB = new GrabberCB();
    gGrabber->SetCallback(gCB, 1);

    gGraph->QueryInterface(IID_PPV_ARGS(&gControl));
    hr = gControl ? gControl->Run() : E_NOINTERFACE;

    std::wstring status = L"Camera: " + devices[index].name +
                          L" | rotazione " + std::to_wstring(gRotation) +
                          L"° | Virtual Camera attiva";
    WriteStatus(status);
    FreeDevices(devices);
    return hr;
}


static bool DecodeJpegToFrame(const BYTE* data, size_t size, std::vector<BYTE>& out) {
    if (!data || size < 4) return false;

    HGLOBAL mem = GlobalAlloc(GMEM_MOVEABLE, size);
    if (!mem) return false;

    void* ptr = GlobalLock(mem);
    if (!ptr) {
        GlobalFree(mem);
        return false;
    }

    memcpy(ptr, data, size);
    GlobalUnlock(mem);

    IStream* stream = nullptr;
    if (FAILED(CreateStreamOnHGlobal(mem, TRUE, &stream)) || !stream) {
        GlobalFree(mem);
        return false;
    }

    std::unique_ptr<Gdiplus::Bitmap> image(
        Gdiplus::Bitmap::FromStream(stream, FALSE)
    );
    stream->Release();

    if (!image || image->GetLastStatus() != Gdiplus::Ok ||
        image->GetWidth() == 0 || image->GetHeight() == 0) {
        return false;
    }

    std::vector<BYTE> base(kPixelBytes, 0);

    Gdiplus::Bitmap canvas(
        kOutW,
        kOutH,
        kOutStride,
        PixelFormat24bppRGB,
        base.data()
    );

    if (canvas.GetLastStatus() != Gdiplus::Ok) return false;

    Gdiplus::Graphics graphics(&canvas);
    graphics.Clear(Gdiplus::Color(255, 0, 0, 0));
    graphics.SetInterpolationMode(Gdiplus::InterpolationModeHighQualityBicubic);

    const double sx = (double)kOutW / image->GetWidth();
    const double sy = (double)kOutH / image->GetHeight();
    const double scale = std::min(sx, sy);

    const int dw = std::max(1, (int)std::lround(image->GetWidth() * scale));
    const int dh = std::max(1, (int)std::lround(image->GetHeight() * scale));
    const int dx = (kOutW - dw) / 2;
    const int dy = (kOutH - dh) / 2;

    graphics.DrawImage(image.get(), dx, dy, dw, dh);

    if (gRotation == 0) {
        out.swap(base);
    } else {
        RotateScaleLetterbox(
            base.data(),
            kOutW,
            kOutH,
            false,
            gRotation,
            out
        );
    }

    return true;
}

static std::string GetAndroidHost() {
    wchar_t text[256]{};
    if (gHostEdit) GetWindowTextW(gHostEdit, text, 255);

    char utf8[512]{};
    WideCharToMultiByte(
        CP_UTF8, 0, text, -1,
        utf8, (int)sizeof(utf8),
        nullptr, nullptr
    );

    std::string host = utf8;
    if (host.empty()) host = "127.0.0.1";
    return host;
}

static void StopAndroidStream() {
    gStreamStop.store(true);

    SOCKET s = gStreamSocket;
    gStreamSocket = INVALID_SOCKET;

    if (s != INVALID_SOCKET) {
        shutdown(s, SD_BOTH);
        closesocket(s);
    }

    if (gStreamThread.joinable())
        gStreamThread.join();
}

static void AndroidStreamWorker(std::string host) {
    addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;

    addrinfo* result = nullptr;
    if (getaddrinfo(host.c_str(), "8080", &hints, &result) != 0) {
        WriteStatus(L"Impossibile risolvere l'indirizzo Android.");
        return;
    }

    SOCKET sock = INVALID_SOCKET;

    for (addrinfo* p = result; p; p = p->ai_next) {
        sock = socket(p->ai_family, p->ai_socktype, p->ai_protocol);
        if (sock == INVALID_SOCKET) continue;

        DWORD timeoutMs = 2500;
        setsockopt(
            sock,
            SOL_SOCKET,
            SO_RCVTIMEO,
            reinterpret_cast<const char*>(&timeoutMs),
            sizeof(timeoutMs)
        );

        if (connect(sock, p->ai_addr, (int)p->ai_addrlen) == 0)
            break;

        closesocket(sock);
        sock = INVALID_SOCKET;
    }

    freeaddrinfo(result);

    if (sock == INVALID_SOCKET) {
        WriteStatus(
            L"Connessione Android fallita. Prova l'IP mostrato nell'app Android."
        );
        return;
    }

    gStreamSocket = sock;

    const std::string request =
        "GET /video HTTP/1.1\r\n"
        "Host: " + host + "\r\n"
        "Connection: keep-alive\r\n\r\n";

    if (send(sock, request.c_str(), (int)request.size(), 0) <= 0) {
        closesocket(sock);
        gStreamSocket = INVALID_SOCKET;
        WriteStatus(L"Errore richiesta stream Android.");
        return;
    }

    WriteStatus(
        L"Android collegato | MJPEG -> CorrectedCamera Virtual Camera"
    );

    std::vector<BYTE> buffer;
    buffer.reserve(1024 * 1024);
    BYTE chunk[16384];

    const BYTE soi[] = {0xFF, 0xD8};
    const BYTE eoi[] = {0xFF, 0xD9};

    while (!gStreamStop.load()) {
        int n = recv(sock, reinterpret_cast<char*>(chunk), sizeof(chunk), 0);

        if (n == 0) break;

        if (n < 0) {
            const int err = WSAGetLastError();
            if (err == WSAETIMEDOUT) continue;
            break;
        }

        buffer.insert(buffer.end(), chunk, chunk + n);

        while (true) {
            auto begin = std::search(
                buffer.begin(), buffer.end(),
                std::begin(soi), std::end(soi)
            );

            if (begin == buffer.end()) {
                if (buffer.size() > 2 * 1024 * 1024)
                    buffer.clear();
                break;
            }

            auto end = std::search(
                begin + 2, buffer.end(),
                std::begin(eoi), std::end(eoi)
            );

            if (end == buffer.end()) {
                if (begin != buffer.begin())
                    buffer.erase(buffer.begin(), begin);
                break;
            }

            end += 2;

            std::vector<BYTE> frame;
            if (DecodeJpegToFrame(
                    &(*begin),
                    (size_t)(end - begin),
                    frame
                )) {
                PublishFrame(frame);
            }

            buffer.erase(buffer.begin(), end);
        }
    }

    if (gStreamSocket == sock)
        gStreamSocket = INVALID_SOCKET;

    closesocket(sock);

    if (!gStreamStop.load()) {
        WriteStatus(
            L"Stream Android interrotto. Premi Connetti Android per riprovare."
        );
    }
}

static void StartAndroidStream() {
    StopAndroidStream();
    StopGraph();
    gStreamStop.store(false);

    const std::string host = GetAndroidHost();
    WriteStatus(L"Connessione allo stream Android...");
    gStreamThread = std::thread(AndroidStreamWorker, host);
}

static void InitSharedMemory() {
    gMap = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0,
                              sizeof(SharedFrameBlock), kFrameMapName);
    if (gMap) {
        gShared = static_cast<SharedFrameBlock*>(
            MapViewOfFile(gMap, FILE_MAP_ALL_ACCESS, 0, 0, sizeof(SharedFrameBlock))
        );
        if (gShared) {
            ZeroMemory(gShared, sizeof(SharedFrameBlock));
            gShared->h.magic = kMagic;
            gShared->h.width = kOutW;
            gShared->h.height = kOutH;
            gShared->h.stride = kOutStride;
        }
    }
}

static void CloseSharedMemory() {
    if (gShared) { UnmapViewOfFile(gShared); gShared = nullptr; }
    if (gMap) { CloseHandle(gMap); gMap = nullptr; }
}


static void ApplyOverlayShapeAndSize() {
    if (!gOverlay) return;

    int w = 0, h = 0;
    if (gOverlayRound) {
        w = gOverlayLarge ? 420 : 220;
        h = w;
    } else {
        w = gOverlayLarge ? 520 : 280;
        h = gOverlayLarge ? 390 : 210;
    }

    SetWindowPos(
        gOverlay,
        HWND_TOPMOST,
        0, 0, w, h,
        SWP_NOMOVE | SWP_NOACTIVATE | SWP_SHOWWINDOW
    );

    if (gOverlayRound) {
        HRGN rgn = CreateEllipticRgn(0, 0, w, h);
        SetWindowRgn(gOverlay, rgn, TRUE);
    } else {
        SetWindowRgn(gOverlay, nullptr, TRUE);
    }

    InvalidateRect(gOverlay, nullptr, TRUE);
}

static void ToggleOverlay() {
    if (!gOverlay) return;
    gOverlayVisible = !gOverlayVisible;

    if (gOverlayVisible) {
        ApplyOverlayShapeAndSize();
        ShowWindow(gOverlay, SW_SHOWNOACTIVATE);
        SetWindowPos(
            gOverlay, HWND_TOPMOST, 0, 0, 0, 0,
            SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE
        );
    } else {
        ShowWindow(gOverlay, SW_HIDE);
    }
}

static void PaintCameraIntoRect(HDC dc, const RECT& box) {
    HBRUSH black = CreateSolidBrush(RGB(0, 0, 0));
    FillRect(dc, &box, black);
    DeleteObject(black);

    std::lock_guard<std::mutex> lock(gPreviewMutex);
    if (!gPreview) return;

    HDC mem = CreateCompatibleDC(dc);
    HGDIOBJ old = SelectObject(mem, gPreview);

    int bw = box.right - box.left;
    int bh = box.bottom - box.top;

    double scale = std::max((double)bw / kOutW, (double)bh / kOutH);
    int dw = (int)std::lround(kOutW * scale);
    int dh = (int)std::lround(kOutH * scale);
    int dx = box.left + (bw - dw) / 2;
    int dy = box.top + (bh - dh) / 2;

    SetStretchBltMode(dc, HALFTONE);
    StretchBlt(
        dc, dx, dy, dw, dh,
        mem, 0, 0, kOutW, kOutH,
        SRCCOPY
    );

    SelectObject(mem, old);
    DeleteDC(mem);
}

static LRESULT CALLBACK OverlayProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case WM_NCHITTEST:
        return HTCAPTION;

    case WM_ERASEBKGND:
        return 1;

    case WM_PAINT: {
        PAINTSTRUCT ps{};
        HDC dc = BeginPaint(hwnd, &ps);
        RECT rc{};
        GetClientRect(hwnd, &rc);
        PaintCameraIntoRect(dc, rc);
        EndPaint(hwnd, &ps);
        return 0;
    }

    case WM_CLOSE:
        gOverlayVisible = false;
        ShowWindow(hwnd, SW_HIDE);
        return 0;
    }

    return DefWindowProcW(hwnd, msg, wp, lp);
}

static LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case WM_CREATE:
        CreateWindowW(L"STATIC", L"CORRECTED CAMERA", WS_CHILD|WS_VISIBLE,
                      16, 12, 240, 26, hwnd, nullptr, nullptr, nullptr);

        CreateWindowW(L"BUTTON", L"Ruota sinistra", WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,
                      16, 48, 140, 36, hwnd, (HMENU)101, nullptr, nullptr);
        CreateWindowW(L"BUTTON", L"Ruota destra", WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,
                      164, 48, 140, 36, hwnd, (HMENU)102, nullptr, nullptr);
        CreateWindowW(L"BUTTON", L"Cambia camera", WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,
                      312, 48, 140, 36, hwnd, (HMENU)103, nullptr, nullptr);

        CreateWindowW(L"BUTTON", L"Forma: tondo", WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,
                      460, 48, 140, 36, hwnd, (HMENU)105, nullptr, nullptr);
        CreateWindowW(L"BUTTON", L"Dimensione: piccola", WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,
                      608, 48, 170, 36, hwnd, (HMENU)106, nullptr, nullptr);

        CreateWindowW(L"BUTTON", L"Mostra in primo piano", WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,
                      16, 92, 210, 38, hwnd, (HMENU)107, nullptr, nullptr);

        CreateWindowW(L"STATIC", L"IP Android:", WS_CHILD|WS_VISIBLE,
                      238, 100, 85, 24, hwnd, nullptr, nullptr, nullptr);

        gHostEdit = CreateWindowExW(
            WS_EX_CLIENTEDGE,
            L"EDIT",
            L"127.0.0.1",
            WS_CHILD|WS_VISIBLE|ES_AUTOHSCROLL,
            322, 94, 170, 34,
            hwnd, (HMENU)108, nullptr, nullptr
        );

        CreateWindowW(L"BUTTON", L"Connetti Android", WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,
                      502, 92, 165, 38, hwnd, (HMENU)109, nullptr, nullptr);

        gStatus = CreateWindowW(
            L"STATIC",
            L"Apri CorrectedCamera Android, poi premi Connetti Android",
            WS_CHILD|WS_VISIBLE,
            16, 136, 762, 24,
            hwnd, nullptr, nullptr, nullptr
        );

        return 0;

    case WM_COMMAND:
        if (LOWORD(wp) == 101) {
            gRotation = (gRotation + 270) % 360;
        } else if (LOWORD(wp) == 102) {
            gRotation = (gRotation + 90) % 360;
        } else if (LOWORD(wp) == 103) {
            WriteStatus(L"Cambia camera dall'app Android: lo stream Winlator si aggiorna automaticamente.");
        } else if (LOWORD(wp) == 104) {
            StartAndroidStream();
        } else if (LOWORD(wp) == 105) {
            gOverlayRound = !gOverlayRound;
            if (lp) SetWindowTextW((HWND)lp, gOverlayRound ? L"Forma: tondo" : L"Forma: quadrato");
            if (gOverlayVisible) ApplyOverlayShapeAndSize();
        } else if (LOWORD(wp) == 106) {
            gOverlayLarge = !gOverlayLarge;
            if (lp) SetWindowTextW((HWND)lp, gOverlayLarge ? L"Dimensione: grande" : L"Dimensione: piccola");
            if (gOverlayVisible) ApplyOverlayShapeAndSize();
        } else if (LOWORD(wp) == 107) {
            ToggleOverlay();
            if (lp) SetWindowTextW((HWND)lp, gOverlayVisible ? L"Nascondi primo piano" : L"Mostra in primo piano");
        } else if (LOWORD(wp) == 109) {
            StartAndroidStream();
        }
        return 0;

    case WM_FRAME:
        InvalidateRect(hwnd, nullptr, FALSE);
        if (gOverlay && gOverlayVisible)
            InvalidateRect(gOverlay, nullptr, FALSE);
        return 0;

    case WM_PAINT: {
        PAINTSTRUCT ps{};
        HDC dc = BeginPaint(hwnd, &ps);
        RECT rc{}; GetClientRect(hwnd, &rc);
        RECT box{16, 174, rc.right-16, rc.bottom-16};
        HBRUSH panelBrush = CreateSolidBrush(RGB(20, 25, 30));
        FillRect(dc, &box, panelBrush);
        DeleteObject(panelBrush);

        std::lock_guard<std::mutex> lock(gPreviewMutex);
        if (gPreview) {
            HDC mem = CreateCompatibleDC(dc);
            HGDIOBJ old = SelectObject(mem, gPreview);
            int bw = box.right-box.left, bh=box.bottom-box.top;
            double s = std::min((double)bw/kOutW, (double)bh/kOutH);
            int dw=(int)(kOutW*s), dh=(int)(kOutH*s);
            int dx=box.left+(bw-dw)/2, dy=box.top+(bh-dh)/2;
            SetStretchBltMode(dc, HALFTONE);
            StretchBlt(dc,dx,dy,dw,dh,mem,0,0,kOutW,kOutH,SRCCOPY);
            SelectObject(mem, old); DeleteDC(mem);
        }
        EndPaint(hwnd, &ps);
        return 0;
    }

    case WM_DESTROY:
        StopAndroidStream();
        StopGraph();
        {
            std::lock_guard<std::mutex> lock(gPreviewMutex);
            if (gPreview) { DeleteObject(gPreview); gPreview=nullptr; }
        }
        CloseSharedMemory();
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd,msg,wp,lp);
}

int WINAPI wWinMain(HINSTANCE hi, HINSTANCE, PWSTR, int show) {
    HRESULT hr = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);

    WSADATA wsa{};
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0)
        return 2;

    Gdiplus::GdiplusStartupInput gdiplusInput;
    if (Gdiplus::GdiplusStartup(
            &gGdiplusToken,
            &gdiplusInput,
            nullptr
        ) != Gdiplus::Ok) {
        WSACleanup();
        return 3;
    }

    InitSharedMemory();
    gInst = hi;

    WNDCLASSW overlayClass{};
    overlayClass.lpfnWndProc = OverlayProc;
    overlayClass.hInstance = hi;
    overlayClass.lpszClassName = L"CorrectedCameraFloatingOverlay";
    overlayClass.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    overlayClass.hbrBackground = (HBRUSH)GetStockObject(BLACK_BRUSH);
    RegisterClassW(&overlayClass);

    WNDCLASSW wc{};
    wc.lpfnWndProc = WndProc;
    wc.hInstance = hi;
    wc.lpszClassName = L"CorrectedCameraWinlatorFull";
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.hbrBackground = CreateSolidBrush(RGB(16, 20, 24));
    RegisterClassW(&wc);

    gWnd = CreateWindowW(wc.lpszClassName, L"CorrectedCamera - Winlator",
                         WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT,
                         800, 650, nullptr, nullptr, hi, nullptr);
    if (!gWnd) return 1;

    gOverlay = CreateWindowExW(
        WS_EX_TOPMOST | WS_EX_TOOLWINDOW,
        overlayClass.lpszClassName,
        L"CorrectedCamera Overlay",
        WS_POPUP,
        40, 40, 220, 220,
        nullptr, nullptr, hi, nullptr
    );
    if (gOverlay) {
        ApplyOverlayShapeAndSize();
        ShowWindow(gOverlay, SW_HIDE);
    }

    ShowWindow(gWnd, show);
    UpdateWindow(gWnd);

    MSG msg{};
    while (GetMessageW(&msg,nullptr,0,0)) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    if (gGdiplusToken)
        Gdiplus::GdiplusShutdown(gGdiplusToken);

    WSACleanup();

    if (SUCCEEDED(hr)) CoUninitialize();
    return (int)msg.wParam;
}
