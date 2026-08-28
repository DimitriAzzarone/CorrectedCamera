
cmake_minimum_required(VERSION 3.20)
project(CorrectedCameraWinlatorFull LANGUAGES CXX)

add_executable(CorrectedCamera WIN32 App.cpp)
target_compile_features(CorrectedCamera PRIVATE cxx_std_17)
target_compile_definitions(CorrectedCamera PRIVATE UNICODE _UNICODE)
target_link_libraries(CorrectedCamera PRIVATE strmiids ole32 oleaut32 user32 gdi32 ws2_32 gdiplus)

add_library(CorrectedCameraVirtualCam SHARED VirtualCam.cpp VirtualCam.def)
target_compile_features(CorrectedCameraVirtualCam PRIVATE cxx_std_17)
target_compile_definitions(CorrectedCameraVirtualCam PRIVATE UNICODE _UNICODE)
target_link_libraries(CorrectedCameraVirtualCam PRIVATE strmiids ole32 oleaut32 advapi32)
set_target_properties(CorrectedCameraVirtualCam PROPERTIES PREFIX "" OUTPUT_NAME "CorrectedCameraVirtualCam")

add_executable(CorrectedCamera-Setup WIN32 Setup.cpp)
target_compile_features(CorrectedCamera-Setup PRIVATE cxx_std_17)
target_compile_definitions(CorrectedCamera-Setup PRIVATE UNICODE _UNICODE)
target_link_libraries(CorrectedCamera-Setup PRIVATE shell32)

add_executable(CorrectedCamera-Uninstall WIN32 Uninstall.cpp)
target_compile_features(CorrectedCamera-Uninstall PRIVATE cxx_std_17)
target_compile_definitions(CorrectedCamera-Uninstall PRIVATE UNICODE _UNICODE)
target_link_libraries(CorrectedCamera-Uninstall PRIVATE shell32)

if(MSVC)
  foreach(t CorrectedCamera CorrectedCameraVirtualCam CorrectedCamera-Setup CorrectedCamera-Uninstall)
    set_property(TARGET ${t} PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
    target_compile_options(${t} PRIVATE /W3 /EHsc)
  endforeach()
endif()
