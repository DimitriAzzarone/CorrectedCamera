cmake_minimum_required(VERSION 3.20)
project(CorrectedCameraWinlator LANGUAGES CXX)

add_executable(CorrectedCamera-Winlator WIN32 main.cpp)

target_compile_features(CorrectedCamera-Winlator PRIVATE cxx_std_17)

if(MSVC)
  set_property(
    TARGET CorrectedCamera-Winlator
    PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>"
  )
  target_compile_options(CorrectedCamera-Winlator PRIVATE /W4 /EHsc)
endif()

target_link_libraries(
  CorrectedCamera-Winlator
  PRIVATE
    winhttp
    windowscodecs
    ole32
)
