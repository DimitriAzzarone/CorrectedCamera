
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shlobj.h>
#include <string>
using RegFn = HRESULT (__stdcall*)();
int WINAPI wWinMain(HINSTANCE,HINSTANCE,PWSTR,int){
    wchar_t local[MAX_PATH]{};
    SHGetFolderPathW(nullptr,CSIDL_LOCAL_APPDATA,nullptr,SHGFP_TYPE_CURRENT,local);
    std::wstring dir=std::wstring(local)+L"\\CorrectedCamera";
    std::wstring dll=dir+L"\\CorrectedCameraVirtualCam.dll";
    HMODULE h=LoadLibraryW(dll.c_str());
    if(h){auto fn=(RegFn)GetProcAddress(h,"DllUnregisterServer");if(fn)fn();FreeLibrary(h);}
    DeleteFileW((dir+L"\\CorrectedCamera.exe").c_str());
    DeleteFileW((dir+L"\\CorrectedCameraVirtualCam.dll").c_str());
    MessageBoxW(nullptr,L"CorrectedCamera rimossa. Puoi cancellare la cartella residua se rimane.",
                L"CorrectedCamera",MB_OK|MB_ICONINFORMATION);
    return 0;
}
