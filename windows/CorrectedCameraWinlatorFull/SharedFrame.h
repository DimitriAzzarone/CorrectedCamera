
#pragma once
#include <windows.h>
#include <cstdint>

static constexpr wchar_t kFrameMapName[] = L"Local\\CorrectedCameraFrame";
static constexpr uint32_t kMagic = 0x43434652; // CCFR
static constexpr int kOutW = 640;
static constexpr int kOutH = 480;
static constexpr int kOutStride = kOutW * 3;
static constexpr int kPixelBytes = kOutStride * kOutH;

#pragma pack(push, 1)
struct SharedFrameHeader {
    uint32_t magic;
    uint32_t width;
    uint32_t height;
    uint32_t stride;
    uint64_t frameNo;
    volatile LONG writing;
};
#pragma pack(pop)

struct SharedFrameBlock {
    SharedFrameHeader h;
    unsigned char pixels[kPixelBytes];
};
