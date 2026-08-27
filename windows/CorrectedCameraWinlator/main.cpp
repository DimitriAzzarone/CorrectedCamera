#define UNICODE
#define _UNICODE

#include <windows.h>
#include <winhttp.h>
#include <wincodec.h>
#include <algorithm>
#include <atomic>
#include <iterator>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#pragma comment(lib, "winhttp.lib")
#pragma comment(lib, "windowscodecs.lib")
#pragma comment(lib, "ole32.lib")

static HWND gEdit = nullptr;
static HWND gButton = nullptr;
static std::atomic<bool> gRunning{false};
static std::thread gWorker;
static std::mutex gFrameMutex;
static HBITMAP gFrame = nullptr;
static int gFrameW = 0;
static int gFrameH = 0;
static const UINT WM_NEW_FRAME = WM_APP + 1;
static const UINT WM_STREAM_STOPPED = WM_APP + 2;

static void ReplaceFrame(HBITMAP bmp, int w, int h) {
    std::lock_guard<std::mutex> lock(gFrameMutex);
    if (gFrame) DeleteObject(gFrame);
    gFrame = bmp;
    gFrameW = w;
    gFrameH = h;
}

static bool DecodeJpegToBitmap(const std::vector<BYTE>& jpeg, HBITMAP& outBmp, int& outW, int& outH) {
    outBmp = nullptr;
    IWICImagingFactory* factory = nullptr;
    IWICStream* stream = nullptr;
    IWICBitmapDecoder* decoder = nullptr;
    IWICBitmapFrameDecode* frame = nullptr;
    IWICFormatConverter* converter = nullptr;

    HRESULT hr = CoCreateInstance(
        CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&factory)
    );
    if (FAILED(hr)) goto done;

    hr = factory->CreateStream(&stream);
    if (FAILED(hr)) goto done;

    hr = stream->InitializeFromMemory(
        const_cast<BYTE*>(jpeg.data()), static_cast<DWORD>(jpeg.size())
    );
    if (FAILED(hr)) goto done;

    hr = factory->CreateDecoderFromStream(
        stream, nullptr, WICDecodeMetadataCacheOnLoad, &decoder
    );
    if (FAILED(hr)) goto done;

    hr = decoder->GetFrame(0, &frame);
    if (FAILED(hr)) goto done;

    hr = factory->CreateFormatConverter(&converter);
    if (FAILED(hr)) goto done;

    hr = converter->Initialize(
        frame,
        GUID_WICPixelFormat32bppBGRA,
        WICBitmapDitherTypeNone,
        nullptr,
        0.0,
        WICBitmapPaletteTypeCustom
    );
    if (FAILED(hr)) goto done;

    {
        UINT w = 0, h = 0;
        hr = converter->GetSize(&w, &h);
        if (FAILED(hr)) goto done;

        outW = static_cast<int>(w);
        outH = static_cast<int>(h);

        BITMAPINFO bi{};
        bi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
        bi.bmiHeader.biWidth = static_cast<LONG>(w);
        bi.bmiHeader.biHeight = -static_cast<LONG>(h);
        bi.bmiHeader.biPlanes = 1;
        bi.bmiHeader.biBitCount = 32;
        bi.bmiHeader.biCompression = BI_RGB;

        void* bits = nullptr;
        HDC screen = GetDC(nullptr);
        HBITMAP bmp = CreateDIBSection(
            screen, &bi, DIB_RGB_COLORS, &bits, nullptr, 0
        );
        ReleaseDC(nullptr, screen);

        if (!bmp || !bits) {
            hr = E_FAIL;
            goto done;
        }

        const UINT stride = w * 4;
        const UINT size = stride * h;

        hr = converter->CopyPixels(
            nullptr, stride, size, static_cast<BYTE*>(bits)
        );

        if (FAILED(hr)) {
            DeleteObject(bmp);
            goto done;
        }

        outBmp = bmp;
    }

done:
    if (converter) converter->Release();
    if (frame) frame->Release();
    if (decoder) decoder->Release();
    if (stream) stream->Release();
    if (factory) factory->Release();

    return SUCCEEDED(hr) && outBmp != nullptr;
}

static void ShowStreamError(HWND hwnd) {
    MessageBoxW(
        hwnd,
        L"Impossibile aprire lo stream.\n\n"
        L"1. Avvia CorrectedCamera su Android.\n"
        L"2. Verifica che il servizio resti attivo.\n"
        L"3. Inserisci l'indirizzo mostrato dall'app, ad esempio:\n"
        L"http://192.168.108.137:8080/video",
        L"CorrectedCamera",
        MB_OK | MB_ICONERROR
    );
}

static void StreamThread(HWND hwnd, std::wstring url) {
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);

    URL_COMPONENTS uc{};
    uc.dwStructSize = sizeof(uc);

    wchar_t host[256]{};
    wchar_t path[2048]{};

    uc.lpszHostName = host;
    uc.dwHostNameLength = 255;
    uc.lpszUrlPath = path;
    uc.dwUrlPathLength = 2047;

    if (!WinHttpCrackUrl(url.c_str(), 0, 0, &uc)) {
        MessageBoxW(hwnd, L"Indirizzo non valido.", L"CorrectedCamera", MB_OK | MB_ICONERROR);
        gRunning = false;
        PostMessageW(hwnd, WM_STREAM_STOPPED, 0, 0);
        CoUninitialize();
        return;
    }

    std::wstring hostName(host, uc.dwHostNameLength);
    std::wstring urlPath(path, uc.dwUrlPathLength);

    HINTERNET session = WinHttpOpen(
        L"CorrectedCamera-Winlator/0.1",
        WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY,
        WINHTTP_NO_PROXY_NAME,
        WINHTTP_NO_PROXY_BYPASS,
        0
    );

    HINTERNET connect = session
        ? WinHttpConnect(session, hostName.c_str(), uc.nPort, 0)
        : nullptr;

    DWORD flags = (uc.nScheme == INTERNET_SCHEME_HTTPS)
        ? WINHTTP_FLAG_SECURE
        : 0;

    HINTERNET request = connect
        ? WinHttpOpenRequest(
            connect,
            L"GET",
            urlPath.empty() ? L"/" : urlPath.c_str(),
            nullptr,
            WINHTTP_NO_REFERER,
            WINHTTP_DEFAULT_ACCEPT_TYPES,
            flags
        )
        : nullptr;

    bool ok =
        request &&
        WinHttpSendRequest(
            request,
            WINHTTP_NO_ADDITIONAL_HEADERS,
            0,
            WINHTTP_NO_REQUEST_DATA,
            0,
            0,
            0
        ) &&
        WinHttpReceiveResponse(request, nullptr);

    if (!ok) {
        ShowStreamError(hwnd);
    } else {
        std::vector<BYTE> buffer;
        buffer.reserve(2 * 1024 * 1024);

        std::vector<BYTE> chunk(64 * 1024);
        const BYTE soi[] = {0xFF, 0xD8};
        const BYTE eoi[] = {0xFF, 0xD9};

        while (gRunning) {
            DWORD got = 0;

            if (!WinHttpReadData(
                    request,
                    chunk.data(),
                    static_cast<DWORD>(chunk.size()),
                    &got
                ) || got == 0) {
                break;
            }

            buffer.insert(buffer.end(), chunk.begin(), chunk.begin() + got);

            for (;;) {
                auto s = std::search(
                    buffer.begin(), buffer.end(),
                    std::begin(soi), std::end(soi)
                );

                if (s == buffer.end()) {
                    if (buffer.size() > 4 * 1024 * 1024) buffer.clear();
                    break;
                }

                auto e = std::search(
                    s + 2, buffer.end(),
                    std::begin(eoi), std::end(eoi)
                );

                if (e == buffer.end()) {
                    if (s != buffer.begin()) {
                        buffer.erase(buffer.begin(), s);
                    }
                    break;
                }

                e += 2;

                std::vector<BYTE> jpeg(s, e);

                HBITMAP bmp = nullptr;
                int w = 0;
                int h = 0;

                if (DecodeJpegToBitmap(jpeg, bmp, w, h)) {
                    ReplaceFrame(bmp, w, h);
                    PostMessageW(hwnd, WM_NEW_FRAME, 0, 0);
                }

                buffer.erase(buffer.begin(), e);
            }
        }
    }

    if (request) WinHttpCloseHandle(request);
    if (connect) WinHttpCloseHandle(connect);
    if (session) WinHttpCloseHandle(session);

    gRunning = false;
    PostMessageW(hwnd, WM_STREAM_STOPPED, 0, 0);

    CoUninitialize();
}

static LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    switch (msg) {
    case WM_CREATE:
        CreateWindowW(
            L"STATIC",
            L"Stream CorrectedCamera Android:",
            WS_CHILD | WS_VISIBLE,
            12, 12, 300, 22,
            hwnd, nullptr, nullptr, nullptr
        );

        gEdit = CreateWindowExW(
            WS_EX_CLIENTEDGE,
            L"EDIT",
            L"http://192.168.108.137:8080/video",
            WS_CHILD | WS_VISIBLE | ES_AUTOHSCROLL,
            12, 38, 520, 28,
            hwnd, reinterpret_cast<HMENU>(101), nullptr, nullptr
        );

        gButton = CreateWindowW(
            L"BUTTON",
            L"Avvia",
            WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON,
            542, 38, 90, 28,
            hwnd, reinterpret_cast<HMENU>(102), nullptr, nullptr
        );

        CreateWindowW(
            L"STATIC",
            L"Il programma riceve il video gia corretto dall'app Android.",
            WS_CHILD | WS_VISIBLE,
            12, 74, 620, 22,
            hwnd, nullptr, nullptr, nullptr
        );

        return 0;

    case WM_COMMAND:
        if (LOWORD(wParam) == 102) {
            if (!gRunning) {
                wchar_t url[2048]{};
                GetWindowTextW(gEdit, url, 2047);

                if (gWorker.joinable()) gWorker.join();

                gRunning = true;
                SetWindowTextW(gButton, L"Ferma");

                gWorker = std::thread(
                    StreamThread,
                    hwnd,
                    std::wstring(url)
                );
            } else {
                gRunning = false;
                SetWindowTextW(gButton, L"Avvia");
            }
        }
        return 0;

    case WM_NEW_FRAME:
        InvalidateRect(hwnd, nullptr, FALSE);
        return 0;

    case WM_STREAM_STOPPED:
        SetWindowTextW(gButton, L"Avvia");
        return 0;

    case WM_PAINT: {
        PAINTSTRUCT ps{};
        HDC dc = BeginPaint(hwnd, &ps);

        RECT rc{};
        GetClientRect(hwnd, &rc);

        RECT view{12, 106, rc.right - 12, rc.bottom - 12};
        FillRect(dc, &view, reinterpret_cast<HBRUSH>(COLOR_WINDOW + 1));

        std::lock_guard<std::mutex> lock(gFrameMutex);

        if (gFrame && gFrameW > 0 && gFrameH > 0) {
            int vw = view.right - view.left;
            int vh = view.bottom - view.top;

            double sx = static_cast<double>(vw) / gFrameW;
            double sy = static_cast<double>(vh) / gFrameH;
            double sc = (sx < sy) ? sx : sy;

            int dw = static_cast<int>(gFrameW * sc);
            int dh = static_cast<int>(gFrameH * sc);

            int dx = view.left + (vw - dw) / 2;
            int dy = view.top + (vh - dh) / 2;

            HDC mem = CreateCompatibleDC(dc);
            HGDIOBJ old = SelectObject(mem, gFrame);

            SetStretchBltMode(dc, HALFTONE);

            StretchBlt(
                dc,
                dx, dy, dw, dh,
                mem,
                0, 0, gFrameW, gFrameH,
                SRCCOPY
            );

            SelectObject(mem, old);
            DeleteDC(mem);
        }

        EndPaint(hwnd, &ps);
        return 0;
    }

    case WM_DESTROY:
        gRunning = false;

        if (gWorker.joinable()) {
            gWorker.join();
        }

        {
            std::lock_guard<std::mutex> lock(gFrameMutex);
            if (gFrame) {
                DeleteObject(gFrame);
                gFrame = nullptr;
            }
        }

        PostQuitMessage(0);
        return 0;
    }

    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

int WINAPI wWinMain(HINSTANCE hInst, HINSTANCE, PWSTR, int show) {
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);

    const wchar_t cls[] = L"CorrectedCameraWinlatorWindow";

    WNDCLASSW wc{};
    wc.lpfnWndProc = WndProc;
    wc.hInstance = hInst;
    wc.lpszClassName = cls;
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_BTNFACE + 1);

    RegisterClassW(&wc);

    HWND hwnd = CreateWindowW(
        cls,
        L"CorrectedCamera for Winlator",
        WS_OVERLAPPEDWINDOW,
        CW_USEDEFAULT,
        CW_USEDEFAULT,
        760,
        620,
        nullptr,
        nullptr,
        hInst,
        nullptr
    );

    if (!hwnd) {
        CoUninitialize();
        return 1;
    }

    ShowWindow(hwnd, show);
    UpdateWindow(hwnd);

    MSG msg{};

    while (GetMessageW(&msg, nullptr, 0, 0)) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }

    CoUninitialize();
    return static_cast<int>(msg.wParam);
}
