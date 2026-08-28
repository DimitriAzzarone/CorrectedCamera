
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shlobj.h>
#include <string>

using RegFn = HRESULT (__stdcall*)();

static std::wstring Dir() {
    wchar_t p[MAX_PATH]{};
    GetModuleFileNameW(nullptr,p,MAX_PATH);
    std::wstring s=p; auto x=s.find_last_of(L"\\/");
    return x==std::wstring::npos?L".":s.substr(0,x);
}
static bool CopySelfPackage(const std::wstring& srcDir, std::wstring& installDir) {
    wchar_t local[MAX_PATH]{};
    if (FAILED(SHGetFolderPathW(nullptr, CSIDL_LOCAL_APPDATA, nullptr, SHGFP_TYPE_CURRENT, local))) return false;
    installDir = std::wstring(local) + L"\\CorrectedCamera";
    CreateDirectoryW(installDir.c_str(), nullptr);
    const wchar_t* files[] = {L"CorrectedCamera.exe", L"CorrectedCameraVirtualCam.dll", L"CorrectedCamera-Uninstall.exe"};
    for (auto f : files) {
        std::wstring src = srcDir + L"\\" + f;
        std::wstring dst = installDir + L"\\" + f;
        if (!CopyFileW(src.c_str(), dst.c_str(), FALSE)) return false;
    }
    return true;
}
int WINAPI wWinMain(HINSTANCE,HINSTANCE,PWSTR,int) {
    std::wstring install;
    if (!CopySelfPackage(Dir(), install)) {
        MessageBoxW(nullptr,L"Installazione fallita: tieni tutti i file del pacchetto nella stessa cartella.",
                    L"CorrectedCamera Setup",MB_OK|MB_ICONERROR);
        return 1;
    }
    std::wstring dll=install+L"\\CorrectedCameraVirtualCam.dll";
    HMODULE h=LoadLibraryW(dll.c_str());
    if(!h){MessageBoxW(nullptr,L"DLL virtual camera non caricabile.",L"CorrectedCamera Setup",MB_OK|MB_ICONERROR);return 2;}
    auto fn=(RegFn)GetProcAddress(h,"DllRegisterServer");
    HRESULT hr=fn?fn():E_FAIL;FreeLibrary(h);
    if(FAILED(hr)){MessageBoxW(nullptr,L"Registrazione DirectShow fallita.",L"CorrectedCamera Setup",MB_OK|MB_ICONERROR);return 3;}

    std::wstring exe=install+L"\\CorrectedCamera.exe";
    ShellExecuteW(nullptr,L"open",exe.c_str(),nullptr,install.c_str(),SW_SHOWNORMAL);
    MessageBoxW(nullptr,
        L"CorrectedCamera installata.\n\n"
        L"Il programma prova ad aprire direttamente la camera fisica che Winlator/Wine espone.\n"
        L"Puoi ruotare a sinistra/destra e cambiare camera.\n"
        L"Le app Windows dovrebbero vedere: CorrectedCamera Virtual Camera",
        L"Installazione completata",MB_OK|MB_ICONINFORMATION);
    return 0;
}
