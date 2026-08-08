cmake_minimum_required(VERSION 3.10)
project(transcriber_android_whispercpp)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/whisper.cpp)

file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if(CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1} PARENT_SCOPE)
else()
    set(WHISPER_VERSION "unknown" PARENT_SCOPE)
endif()

set(SOURCE_FILES
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper_jni.c)

find_library(LOG_LIB log)
include(FetchContent)

add_library(whisper SHARED ${SOURCE_FILES})
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
FetchContent_MakeAvailable(ggml)

set(ANDROID_PAGE_SIZE_LINK_FLAGS
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")

foreach(target whisper ggml ggml-base ggml-cpu)
    if(TARGET ${target})
        target_link_options(${target} PRIVATE ${ANDROID_PAGE_SIZE_LINK_FLAGS})
    endif()
endforeach()

target_link_libraries(whisper ${LOG_LIB} android ggml)
target_compile_definitions(whisper PUBLIC GGML_USE_CPU)
target_compile_definitions(whisper PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")
if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
    target_compile_options(whisper PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections)
endif()

target_include_directories(whisper PRIVATE
    ${WHISPER_LIB_DIR}
    ${WHISPER_LIB_DIR}/src
    ${WHISPER_LIB_DIR}/include
    ${WHISPER_LIB_DIR}/ggml/include
    ${WHISPER_LIB_DIR}/ggml/src
    ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu)
