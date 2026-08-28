
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <dshow.h>
#include <ks.h>
#include <ksmedia.h>
#include <atomic>
#include <thread>
#include <string>
#include <algorithm>
#include <vector>
#include <cstring>

#include "SharedFrame.h"

#pragma comment(lib, "strmiids.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "oleaut32.lib")
#pragma comment(lib, "advapi32.lib")

static const CLSID CLSID_CorrectedCamera =
{ 0x1d9a8d4c, 0x4d57, 0x4e63, { 0x9c, 0x21, 0x8a, 0x5e, 0x7d, 0x1b, 0x5e, 0x71 } };

static HMODULE g_module = nullptr;
static std::atomic<long> g_objects{0};
static std::atomic<long> g_locks{0};

static std::wstring GuidString(REFGUID g) {
    wchar_t b[64]{};
    StringFromGUID2(g, b, 64);
    return b;
}

static HRESULT SetRegSz(HKEY root, const std::wstring& path, const wchar_t* name, const std::wstring& value) {
    HKEY h = nullptr;
    LONG r = RegCreateKeyExW(root, path.c_str(), 0, nullptr, 0, KEY_WRITE, nullptr, &h, nullptr);
    if (r != ERROR_SUCCESS) return HRESULT_FROM_WIN32(r);
    r = RegSetValueExW(h, name, 0, REG_SZ,
        reinterpret_cast<const BYTE*>(value.c_str()),
        static_cast<DWORD>((value.size() + 1) * sizeof(wchar_t)));
    RegCloseKey(h);
    return HRESULT_FROM_WIN32(r);
}
static HRESULT DeleteTree(HKEY root, const std::wstring& path) {
    LONG r = RegDeleteTreeW(root, path.c_str());
    if (r == ERROR_FILE_NOT_FOUND) return S_OK;
    return HRESULT_FROM_WIN32(r);
}

static AM_MEDIA_TYPE* MakeType() {
    auto* mt = static_cast<AM_MEDIA_TYPE*>(CoTaskMemAlloc(sizeof(AM_MEDIA_TYPE)));
    if (!mt) return nullptr;
    ZeroMemory(mt,sizeof(*mt));
    mt->majortype = MEDIATYPE_Video;
    mt->subtype = MEDIASUBTYPE_RGB24;
    mt->bFixedSizeSamples = TRUE;
    mt->formattype = FORMAT_VideoInfo;
    mt->cbFormat = sizeof(VIDEOINFOHEADER);
    mt->pbFormat = static_cast<BYTE*>(CoTaskMemAlloc(mt->cbFormat));
    if (!mt->pbFormat) { CoTaskMemFree(mt); return nullptr; }
    ZeroMemory(mt->pbFormat, mt->cbFormat);
    auto* vih = reinterpret_cast<VIDEOINFOHEADER*>(mt->pbFormat);
    vih->AvgTimePerFrame = 333333;
    vih->bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    vih->bmiHeader.biWidth = kOutW;
    vih->bmiHeader.biHeight = kOutH; // bottom-up for DirectShow RGB24
    vih->bmiHeader.biPlanes = 1;
    vih->bmiHeader.biBitCount = 24;
    vih->bmiHeader.biCompression = BI_RGB;
    vih->bmiHeader.biSizeImage = kPixelBytes;
    mt->lSampleSize = kPixelBytes;
    return mt;
}
static void FreeMT(AM_MEDIA_TYPE* mt) {
    if (!mt) return;
    if (mt->cbFormat && mt->pbFormat) CoTaskMemFree(mt->pbFormat);
    if (mt->pUnk) mt->pUnk->Release();
    CoTaskMemFree(mt);
}

class EnumMT final : public IEnumMediaTypes {
    std::atomic<ULONG> ref{1}; ULONG pos=0;
public:
    EnumMT(){g_objects++;} ~EnumMT(){g_objects--;}
    STDMETHODIMP QueryInterface(REFIID r, void** p) override {
        if(!p)return E_POINTER;*p=nullptr;
        if(r==IID_IUnknown||r==IID_IEnumMediaTypes){*p=this;AddRef();return S_OK;}return E_NOINTERFACE;
    }
    STDMETHODIMP_(ULONG) AddRef() override{return ++ref;}
    STDMETHODIMP_(ULONG) Release() override{auto r=--ref;if(!r)delete this;return r;}
    STDMETHODIMP Next(ULONG c, AM_MEDIA_TYPE** out, ULONG* f) override{
        if(!out)return E_POINTER;if(c!=1&&!f)return E_POINTER;ULONG n=0;
        if(pos==0&&c){out[0]=MakeType();if(!out[0])return E_OUTOFMEMORY;pos=1;n=1;}
        if(f)*f=n;return n==c?S_OK:S_FALSE;
    }
    STDMETHODIMP Skip(ULONG c) override{pos=std::min<ULONG>(1,pos+c);return pos<1?S_OK:S_FALSE;}
    STDMETHODIMP Reset() override{pos=0;return S_OK;}
    STDMETHODIMP Clone(IEnumMediaTypes** e) override{if(!e)return E_POINTER;auto*x=new EnumMT();x->pos=pos;*e=x;return S_OK;}
};

class Filter;
class Pin final : public IPin, public IAMStreamConfig, public IKsPropertySet {
    std::atomic<ULONG> ref{1};
    Filter* filter;
    IPin* peer=nullptr; IMemInputPin* mem=nullptr; IMemAllocator* alloc=nullptr;
    std::atomic<bool> run{false}; std::thread worker;
public:
    explicit Pin(Filter*f):filter(f){g_objects++;}
    ~Pin(){Stop();Disconnect();g_objects--;}
    void Stop(){run=false;if(worker.joinable())worker.join();}
    HRESULT Start(){
        if(!peer||!mem||!alloc)return S_FALSE;
        if(run)return S_OK;run=true;
        worker=std::thread([this]{
            HANDLE map=nullptr; SharedFrameBlock* sh=nullptr;
            LONGLONG t=0; uint64_t last=0;
            std::vector<BYTE> tmp(kPixelBytes,0);
            while(run){
                if(!map){
                    map=OpenFileMappingW(FILE_MAP_READ,FALSE,kFrameMapName);
                    if(map) sh=(SharedFrameBlock*)MapViewOfFile(map,FILE_MAP_READ,0,0,sizeof(SharedFrameBlock));
                }
                bool copied=false;
                if(sh && sh->h.magic==kMagic && sh->h.width==kOutW && sh->h.height==kOutH && sh->h.writing==0){
                    uint64_t n=sh->h.frameNo;
                    if(n!=last){
                        memcpy(tmp.data(), sh->pixels, kPixelBytes);
                        MemoryBarrier();
                        if(sh->h.writing==0){last=n;copied=true;}
                    }
                }
                IMediaSample*s=nullptr;
                if(SUCCEEDED(alloc->GetBuffer(&s,nullptr,nullptr,0))&&s){
                    BYTE*p=nullptr;
                    if(SUCCEEDED(s->GetPointer(&p))&&p){
                        // App shared frame is top-down; DirectShow RGB24 here is bottom-up.
                        for(int y=0;y<kOutH;++y)
                            memcpy(p+(kOutH-1-y)*kOutStride, tmp.data()+y*kOutStride, kOutStride);
                        s->SetActualDataLength(kPixelBytes);
                        REFERENCE_TIME a=t,b=t+333333;s->SetTime(&a,&b);s->SetSyncPoint(TRUE);
                        mem->Receive(s);t=b;
                    }
                    s->Release();
                }
                Sleep(33);
            }
            if(sh)UnmapViewOfFile(sh);if(map)CloseHandle(map);
        });
        return S_OK;
    }
    STDMETHODIMP QueryInterface(REFIID r,void**p)override{
        if(!p)return E_POINTER;*p=nullptr;
        if(r==IID_IUnknown||r==IID_IPin)*p=(IPin*)this;
        else if(r==IID_IAMStreamConfig)*p=(IAMStreamConfig*)this;
        else if(r==IID_IKsPropertySet)*p=(IKsPropertySet*)this;
        else return E_NOINTERFACE;AddRef();return S_OK;
    }
    STDMETHODIMP_(ULONG)AddRef()override{return ++ref;}
    STDMETHODIMP_(ULONG)Release()override{auto r=--ref;if(!r)delete this;return r;}
    STDMETHODIMP Connect(IPin*,const AM_MEDIA_TYPE*)override{return E_UNEXPECTED;}
    STDMETHODIMP ReceiveConnection(IPin*p,const AM_MEDIA_TYPE*mt)override{
        if(!p||!mt)return E_POINTER;if(peer)return VFW_E_ALREADY_CONNECTED;
        if(mt->majortype!=MEDIATYPE_Video||mt->subtype!=MEDIASUBTYPE_RGB24)return VFW_E_TYPE_NOT_ACCEPTED;
        IMemInputPin*m=nullptr;HRESULT hr=p->QueryInterface(IID_PPV_ARGS(&m));if(FAILED(hr))return hr;
        IMemAllocator*a=nullptr;hr=m->GetAllocator(&a);if(FAILED(hr)||!a){m->Release();return E_FAIL;}
        ALLOCATOR_PROPERTIES req{4,kPixelBytes,1,0},act{};
        hr=a->SetProperties(&req,&act);if(SUCCEEDED(hr))hr=a->Commit();
        if(FAILED(hr)){a->Release();m->Release();return hr;}
        p->AddRef();peer=p;mem=m;alloc=a;return S_OK;
    }
    STDMETHODIMP Disconnect()override{
        Stop();
        if(alloc){alloc->Decommit();alloc->Release();alloc=nullptr;}
        if(mem){mem->Release();mem=nullptr;}
        if(peer){peer->Release();peer=nullptr;}
        return S_OK;
    }
    STDMETHODIMP ConnectedTo(IPin**p)override{if(!p)return E_POINTER;if(!peer){*p=nullptr;return VFW_E_NOT_CONNECTED;}peer->AddRef();*p=peer;return S_OK;}
    STDMETHODIMP ConnectionMediaType(AM_MEDIA_TYPE*mt)override{
        if(!mt)return E_POINTER;auto*x=MakeType();if(!x)return E_OUTOFMEMORY;*mt=*x;CoTaskMemFree(x);return S_OK;
    }
    STDMETHODIMP QueryPinInfo(PIN_INFO*info)override;
    STDMETHODIMP QueryDirection(PIN_DIRECTION*d)override{if(!d)return E_POINTER;*d=PINDIR_OUTPUT;return S_OK;}
    STDMETHODIMP QueryId(LPWSTR*id)override{
        if(!id)return E_POINTER;const wchar_t*s=L"Capture";size_t n=(wcslen(s)+1)*2;
        *id=(LPWSTR)CoTaskMemAlloc(n);if(!*id)return E_OUTOFMEMORY;memcpy(*id,s,n);return S_OK;
    }
    STDMETHODIMP QueryAccept(const AM_MEDIA_TYPE*mt)override{return mt&&mt->majortype==MEDIATYPE_Video&&mt->subtype==MEDIASUBTYPE_RGB24?S_OK:S_FALSE;}
    STDMETHODIMP EnumMediaTypes(IEnumMediaTypes**e)override{if(!e)return E_POINTER;*e=new EnumMT();return S_OK;}
    STDMETHODIMP QueryInternalConnections(IPin**,ULONG*)override{return E_NOTIMPL;}
    STDMETHODIMP EndOfStream()override{return S_OK;} STDMETHODIMP BeginFlush()override{return S_OK;}
    STDMETHODIMP EndFlush()override{return S_OK;} STDMETHODIMP NewSegment(REFERENCE_TIME,REFERENCE_TIME,double)override{return S_OK;}
    STDMETHODIMP SetFormat(AM_MEDIA_TYPE*mt)override{return QueryAccept(mt)==S_OK?S_OK:VFW_E_INVALIDMEDIATYPE;}
    STDMETHODIMP GetFormat(AM_MEDIA_TYPE**mt)override{if(!mt)return E_POINTER;*mt=MakeType();return *mt?S_OK:E_OUTOFMEMORY;}
    STDMETHODIMP GetNumberOfCapabilities(int*c,int*s)override{if(!c||!s)return E_POINTER;*c=1;*s=sizeof(VIDEO_STREAM_CONFIG_CAPS);return S_OK;}
    STDMETHODIMP GetStreamCaps(int i,AM_MEDIA_TYPE**mt,BYTE*caps)override{
        if(i!=0)return S_FALSE;if(!mt||!caps)return E_POINTER;*mt=MakeType();if(!*mt)return E_OUTOFMEMORY;
        auto*c=(VIDEO_STREAM_CONFIG_CAPS*)caps;ZeroMemory(c,sizeof(*c));c->guid=FORMAT_VideoInfo;
        c->InputSize={kOutW,kOutH};c->MinOutputSize=c->MaxOutputSize=c->InputSize;
        c->MinFrameInterval=c->MaxFrameInterval=333333;c->MinBitsPerSecond=c->MaxBitsPerSecond=kOutW*kOutH*24*30;return S_OK;
    }
    STDMETHODIMP Set(REFGUID,DWORD,LPVOID,DWORD,LPVOID,DWORD)override{return E_NOTIMPL;}
    STDMETHODIMP Get(REFGUID g,DWORD id,LPVOID,DWORD,LPVOID data,DWORD cb,DWORD*ret)override{
        if(g==AMPROPSETID_Pin&&id==AMPROPERTY_PIN_CATEGORY){if(ret)*ret=sizeof(GUID);if(!data)return S_OK;if(cb<sizeof(GUID))return E_UNEXPECTED;*(GUID*)data=PIN_CATEGORY_CAPTURE;return S_OK;}return E_PROP_ID_UNSUPPORTED;
    }
    STDMETHODIMP QuerySupported(REFGUID g,DWORD id,DWORD*t)override{
        if(!t)return E_POINTER;if(g==AMPROPSETID_Pin&&id==AMPROPERTY_PIN_CATEGORY){*t=KSPROPERTY_SUPPORT_GET;return S_OK;}*t=0;return S_FALSE;
    }
};

class EnumPins final:public IEnumPins{
    std::atomic<ULONG>ref{1};Pin*pin;ULONG pos=0;
public:explicit EnumPins(Pin*p):pin(p){pin->AddRef();g_objects++;}~EnumPins(){pin->Release();g_objects--;}
    STDMETHODIMP QueryInterface(REFIID r,void**p)override{if(!p)return E_POINTER;*p=nullptr;if(r==IID_IUnknown||r==IID_IEnumPins){*p=this;AddRef();return S_OK;}return E_NOINTERFACE;}
    STDMETHODIMP_(ULONG)AddRef()override{return ++ref;} STDMETHODIMP_(ULONG)Release()override{auto r=--ref;if(!r)delete this;return r;}
    STDMETHODIMP Next(ULONG c,IPin**p,ULONG*f)override{if(!p)return E_POINTER;ULONG n=0;if(pos==0&&c){pin->AddRef();p[0]=pin;pos=1;n=1;}if(f)*f=n;return n==c?S_OK:S_FALSE;}
    STDMETHODIMP Skip(ULONG c)override{pos=std::min<ULONG>(1,pos+c);return pos<1?S_OK:S_FALSE;} STDMETHODIMP Reset()override{pos=0;return S_OK;}
    STDMETHODIMP Clone(IEnumPins**e)override{if(!e)return E_POINTER;auto*x=new EnumPins(pin);x->pos=pos;*e=x;return S_OK;}
};

class Filter final:public IBaseFilter{
    std::atomic<ULONG>ref{1};FILTER_STATE state=State_Stopped;IFilterGraph*graph=nullptr;Pin*pin;WCHAR name[128]{};
public:
    Filter(){wcscpy_s(name,L"CorrectedCamera Virtual Camera");pin=new Pin(this);g_objects++;}
    ~Filter(){if(graph)graph->Release();pin->Release();g_objects--;}
    STDMETHODIMP QueryInterface(REFIID r,void**p)override{if(!p)return E_POINTER;*p=nullptr;if(r==IID_IUnknown||r==IID_IBaseFilter||r==IID_IMediaFilter||r==IID_IPersist){*p=(IBaseFilter*)this;AddRef();return S_OK;}return E_NOINTERFACE;}
    STDMETHODIMP_(ULONG)AddRef()override{return ++ref;} STDMETHODIMP_(ULONG)Release()override{auto r=--ref;if(!r)delete this;return r;}
    STDMETHODIMP GetClassID(CLSID*c)override{if(!c)return E_POINTER;*c=CLSID_CorrectedCamera;return S_OK;}
    STDMETHODIMP Stop()override{state=State_Stopped;pin->Stop();return S_OK;} STDMETHODIMP Pause()override{state=State_Paused;return S_OK;}
    STDMETHODIMP Run(REFERENCE_TIME)override{state=State_Running;return pin->Start();}
    STDMETHODIMP GetState(DWORD,FILTER_STATE*s)override{if(!s)return E_POINTER;*s=state;return S_OK;}
    STDMETHODIMP SetSyncSource(IReferenceClock*)override{return S_OK;} STDMETHODIMP GetSyncSource(IReferenceClock**c)override{if(!c)return E_POINTER;*c=nullptr;return S_OK;}
    STDMETHODIMP EnumPins(IEnumPins**e)override{if(!e)return E_POINTER;*e=new EnumPins(pin);return S_OK;}
    STDMETHODIMP FindPin(LPCWSTR id,IPin**p)override{if(!p)return E_POINTER;*p=nullptr;if(id&&_wcsicmp(id,L"Capture")==0){pin->AddRef();*p=pin;return S_OK;}return VFW_E_NOT_FOUND;}
    STDMETHODIMP QueryFilterInfo(FILTER_INFO*i)override{if(!i)return E_POINTER;wcscpy_s(i->achName,name);i->pGraph=graph;if(graph)graph->AddRef();return S_OK;}
    STDMETHODIMP JoinFilterGraph(IFilterGraph*g,LPCWSTR n)override{if(graph)graph->Release();graph=g;if(graph)graph->AddRef();if(n)wcscpy_s(name,n);return S_OK;}
    STDMETHODIMP QueryVendorInfo(LPWSTR*v)override{if(!v)return E_POINTER;const wchar_t*s=L"CorrectedCamera";size_t n=(wcslen(s)+1)*2;*v=(LPWSTR)CoTaskMemAlloc(n);if(!*v)return E_OUTOFMEMORY;memcpy(*v,s,n);return S_OK;}
};
STDMETHODIMP Pin::QueryPinInfo(PIN_INFO*i){if(!i)return E_POINTER;ZeroMemory(i,sizeof(*i));i->pFilter=(IBaseFilter*)filter;i->pFilter->AddRef();i->dir=PINDIR_OUTPUT;wcscpy_s(i->achName,L"Capture");return S_OK;}

class Factory final:public IClassFactory{
    std::atomic<ULONG>ref{1};
public:Factory(){g_objects++;}~Factory(){g_objects--;}
    STDMETHODIMP QueryInterface(REFIID r,void**p)override{if(!p)return E_POINTER;*p=nullptr;if(r==IID_IUnknown||r==IID_IClassFactory){*p=this;AddRef();return S_OK;}return E_NOINTERFACE;}
    STDMETHODIMP_(ULONG)AddRef()override{return ++ref;} STDMETHODIMP_(ULONG)Release()override{auto r=--ref;if(!r)delete this;return r;}
    STDMETHODIMP CreateInstance(IUnknown*o,REFIID r,void**p)override{if(o)return CLASS_E_NOAGGREGATION;auto*f=new Filter();HRESULT hr=f->QueryInterface(r,p);f->Release();return hr;}
    STDMETHODIMP LockServer(BOOL b)override{if(b)++g_locks;else--g_locks;return S_OK;}
};

extern "C" BOOL WINAPI DllMain(HINSTANCE h,DWORD r,LPVOID){if(r==DLL_PROCESS_ATTACH){g_module=h;DisableThreadLibraryCalls(h);}return TRUE;}
extern "C" __declspec(dllexport) HRESULT __stdcall DllCanUnloadNow(){return g_objects==0&&g_locks==0?S_OK:S_FALSE;}
extern "C" __declspec(dllexport) HRESULT __stdcall DllGetClassObject(REFCLSID c,REFIID r,void**p){if(c!=CLSID_CorrectedCamera)return CLASS_E_CLASSNOTAVAILABLE;auto*f=new Factory();HRESULT hr=f->QueryInterface(r,p);f->Release();return hr;}

extern "C" __declspec(dllexport) HRESULT __stdcall DllRegisterServer(){
    wchar_t path[MAX_PATH]{};if(!GetModuleFileNameW(g_module,path,MAX_PATH))return HRESULT_FROM_WIN32(GetLastError());
    auto cls=GuidString(CLSID_CorrectedCamera);auto cat=GuidString(CLSID_VideoInputDeviceCategory);
    std::wstring base=L"Software\\Classes\\CLSID\\"+cls;
    HRESULT hr=SetRegSz(HKEY_CURRENT_USER,base,nullptr,L"CorrectedCamera Virtual Camera");if(FAILED(hr))return hr;
    hr=SetRegSz(HKEY_CURRENT_USER,base+L"\\InprocServer32",nullptr,path);if(FAILED(hr))return hr;
    hr=SetRegSz(HKEY_CURRENT_USER,base+L"\\InprocServer32",L"ThreadingModel",L"Both");if(FAILED(hr))return hr;
    std::wstring inst=L"Software\\Classes\\CLSID\\"+cat+L"\\Instance\\"+cls;
    hr=SetRegSz(HKEY_CURRENT_USER,inst,L"FriendlyName",L"CorrectedCamera Virtual Camera");if(FAILED(hr))return hr;
    hr=SetRegSz(HKEY_CURRENT_USER,inst,L"CLSID",cls);return hr;
}
extern "C" __declspec(dllexport) HRESULT __stdcall DllUnregisterServer(){
    auto cls=GuidString(CLSID_CorrectedCamera);auto cat=GuidString(CLSID_VideoInputDeviceCategory);
    DeleteTree(HKEY_CURRENT_USER,L"Software\\Classes\\CLSID\\"+cls);
    DeleteTree(HKEY_CURRENT_USER,L"Software\\Classes\\CLSID\\"+cat+L"\\Instance\\"+cls);
    return S_OK;
}
