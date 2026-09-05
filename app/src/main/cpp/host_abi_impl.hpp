// host_abi_impl 的跨编译单元内部接口（仅 app/src/main/cpp 内部使用）。
#pragma once

#include <mira/adapters/android/host_abi.h>

#include <cstdint>
#include <string>

extern "C" {

// 帧完成（HostBridge.nativeCompleteFrame 经 runtime_glue 调入）。
MiraHostStatus miracle_host_complete_frame(std::uint64_t correlation, bool ok,
                                           std::uint32_t width, std::uint32_t height,
                                           std::uint32_t rotation, const std::uint8_t *pixels,
                                           std::uint64_t size, std::uint64_t begin_ns,
                                           std::uint64_t end_ns, MiraHostStatus error_status);

// epoch 递增并触发能力变化回调（旋转/投影重建时由 Kotlin 调入）。
void miracle_host_notify_epoch_changed();

// 调试统计 JSON（unknown/late/cancelled/outstanding_leases）。
void miracle_host_debug_stats_json(std::string &out);

} // extern "C"
