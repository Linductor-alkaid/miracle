// host_abi_impl 的跨编译单元内部接口（仅 app/src/main/cpp 内部使用）。
#pragma once

#include <mira/adapters/android/host_abi.h>

#include <cstdint>
#include <string>

extern "C" {

// 帧完成（HostBridge.nativeCompleteFrame 经 runtime_glue 调入）。encoded 为宿主随帧
// 产出的 PNG 载荷（可空：无编码或编码失败时原始帧按 image/x-host-frame 如实发布）。
MiraHostStatus miracle_host_complete_frame(std::uint64_t correlation, bool ok,
                                           std::uint32_t width, std::uint32_t height,
                                           std::uint32_t rotation, const std::uint8_t *pixels,
                                           std::uint64_t size,
                                           const std::uint8_t *encoded,
                                           std::uint64_t encoded_size, std::uint64_t begin_ns,
                                           std::uint64_t end_ns, MiraHostStatus error_status);

// 输入完成（HostBridge.nativeCompleteInput 经 runtime_glue 调入）。
// receipt 为 MiraHostInputReceipt；side_effect 非 0 即 side_effect_may_have_occurred。
MiraHostStatus miracle_host_complete_input(std::uint64_t correlation, MiraHostStatus status,
                                           std::uint32_t receipt,
                                           std::uint32_t side_effect);

// epoch 递增并触发能力变化回调（旋转/投影重建时由 Kotlin 调入）。
void miracle_host_notify_epoch_changed();

// P3 R3 确认回流：allow=true 时按停放参数派发平台；false 时结算 REJECTED(side=0)。
// 返回非 OK＝correlation 未停放或宿主不可用（调用方如实上报）。
MiraHostStatus miracle_host_confirm_release(std::uint64_t correlation, bool allow);

// 调试统计 JSON（unknown/late/cancelled/outstanding_leases）。
void miracle_host_debug_stats_json(std::string &out);

} // extern "C"
