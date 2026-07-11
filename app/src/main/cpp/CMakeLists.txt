cmake_minimum_required(VERSION 3.22.1)

project("llama_android")

# llama.cpp source files copied from tag b3248
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama)

set(LLAMA_SOURCES
    ${LLAMA_DIR}/ggml.c
    ${LLAMA_DIR}/ggml-alloc.c
    ${LLAMA_DIR}/ggml-backend.c
    ${LLAMA_DIR}/ggml-quants.c
    ${LLAMA_DIR}/llama.cpp
    ${LLAMA_DIR}/unicode.cpp
    ${LLAMA_DIR}/unicode-data.cpp
    ${LLAMA_DIR}/sgemm.cpp
)

add_library(${CMAKE_PROJECT_NAME} SHARED
    llama_bridge.cpp
    ${LLAMA_SOURCES}
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${LLAMA_DIR}
)

target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE
    -O3
    -DNDEBUG
    -DGGML_USE_CPU
    -DGGML_NO_METAL=1
    -DGGML_NO_CUDA=1
    -DGGML_NO_OPENMP=1
    -pthread
)

target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    log
    dl
)
