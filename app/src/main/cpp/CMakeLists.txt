# Miracle JNI bridge：P1 起链接 Mira::android_adapter（Host ABI v1 符号由
# host_abi_impl.cpp 在本库内提供）；P3 起经 Mira::core 公共 API 组装模型网关
# （OpenAiCompatibleProvider/ModelGateway/AgentLoop/ConfirmationAuthority；传输为
# 宿主自实现——安装包未导出官方 transport 头，见台账 MIR-20260906-006）。
cmake_minimum_required(VERSION 3.22)
project(miracle_host LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT DEFINED CMAKE_PREFIX_PATH OR CMAKE_PREFIX_PATH STREQUAL "")
    message(FATAL_ERROR
        "CMAKE_PREFIX_PATH 未设置：请先运行 tools/install-mira.sh（安装前缀 "
        "third_party/mira-install），并由 Gradle externalNativeBuild 注入。")
endif()

# NDK 工具链将包搜索限制在 sysroot（CMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY）；
# 把 mira 安装前缀并入 find root 后 find_package 才能定位 MiraConfig.cmake。
list(APPEND CMAKE_FIND_ROOT_PATH "${CMAKE_PREFIX_PATH}")

find_package(Mira 0.1 CONFIG REQUIRED)

add_library(miracle_host SHARED
    runtime_glue.cpp
    host_abi_impl.cpp
    loop_runtime.cpp)
target_compile_options(miracle_host PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(miracle_host PRIVATE Mira::core Mira::android_adapter log)
