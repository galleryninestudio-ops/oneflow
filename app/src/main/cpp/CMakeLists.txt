cmake_minimum_required(VERSION 3.22.1)
project(oneflow_qjs_android LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(QUICKJS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/quickjs-ng")

add_library(quickjs_ng STATIC
    "${QUICKJS_DIR}/quickjs.c"
    "${QUICKJS_DIR}/libregexp.c"
    "${QUICKJS_DIR}/libunicode.c"
    "${QUICKJS_DIR}/dtoa.c"
)
target_include_directories(quickjs_ng PUBLIC "${QUICKJS_DIR}")
target_compile_definitions(quickjs_ng PRIVATE _GNU_SOURCE QUICKJS_NG_BUILD)
target_link_libraries(quickjs_ng PUBLIC m)

add_library(oneflow_qjs SHARED qjs_isolated_jni.c)
target_link_libraries(oneflow_qjs PRIVATE quickjs_ng log)
