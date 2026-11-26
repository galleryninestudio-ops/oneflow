cmake_minimum_required(VERSION 3.22.1)
project(oneflow_qjs_worker LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

if(NOT DEFINED QUICKJS_DIR)
    set(QUICKJS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/quickjs-ng")
endif()

add_library(quickjs_ng STATIC
    "${QUICKJS_DIR}/quickjs.c"
    "${QUICKJS_DIR}/libregexp.c"
    "${QUICKJS_DIR}/libunicode.c"
    "${QUICKJS_DIR}/dtoa.c"
)
target_include_directories(quickjs_ng PUBLIC "${QUICKJS_DIR}")
target_compile_definitions(quickjs_ng PRIVATE _GNU_SOURCE QUICKJS_NG_BUILD)
target_link_libraries(quickjs_ng PUBLIC m)
if(NOT ANDROID)
    target_link_libraries(quickjs_ng PUBLIC pthread)
endif()

add_executable(qjs_worker qjs_worker.c)
target_link_libraries(qjs_worker PRIVATE quickjs_ng)
