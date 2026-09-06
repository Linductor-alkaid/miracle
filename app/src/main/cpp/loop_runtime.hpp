// P3 闭环运行时的跨编译单元内部接口（仅 app/src/main/cpp 内部使用）。
//
// LoopRuntime 组装 mira AgentLoop 全链（Executor/AndroidHostAdapter/ModelGateway/
// OpenAiCompatibleProvider/传输/admission/事件存储），host_abi_impl 经本头文件查询
// loop 会话活跃状态与确认门；runtime_glue 负责 JNI 封送与注册。
#pragma once

#include <cstdint>
#include <string>

namespace miracle::bridge::loop {

// loop 会话是否活跃（host_abi_impl 的粗相位上投与确认门据此门控；自检会话不受影响）。
bool active();

// 粗相位信号上投（host_abi_impl 在 capture/input 受理时调用："observing"/"acting"）。
void emit_phase_signal(const char *phase);

// —— Kotlin transport 完成回流（runtime_glue 转发）——
// status<0 时视为传输层错误（-1 取消 -2 超时 -3 网络 -4 协议/配置）。
void complete_http_exchange(std::uint64_t exchange_id, std::int32_t status,
                            const std::string &headers_json, const std::string &body);

// —— R3 确认（状态在本模块，挂接点在 host_abi_impl 的 dispatch_input）——
// 发起确认：digest 为动作摘要（事件 JSON 的 sha256 hex）。返回 challenge id（hex）；
// 空串＝失败（调用方按 REJECTED 结算）。
std::string begin_confirmation(std::uint64_t correlation, const std::string &events_json,
                               const std::string &digest_hex, const std::string &action_summary,
                               const std::string &risk_reason);
// 用户响应回流：0=放行 1=拒绝 2=未找到/已失效。-3=校验失败（nonce/digest/到期）。
std::int32_t resolve_confirmation(const std::string &challenge_hex, const std::string &nonce_hex,
                                  bool approve);
// 确认对应的输入 correlation（放行后 host_abi_impl 继续派发；0=未找到/已失效）。
std::uint64_t confirmation_correlation(const std::string &challenge_hex);
// 到期清扫：结算超期挑战，返回被清除的 correlation JSON 数组（含 0 项＝仅有失效条目），
// host_abi_impl 对每个非 0 correlation 按 REJECTED(side=0) 结算。
std::string expire_confirmations();
// takeover：全部待确认动作失效（不放行派发）并通知 Kotlin 关闭弹窗。
void invalidate_confirmations_for_takeover();

// —— 会话管理（runtime_glue 的 JNI 入口转发）——
// config_json：endpoint/api_prefix/model/dialect("responses"|"chat")/api_key/
// max_steps/timeout_ms/transport("kotlin"|"scripted")/script（decision 对象数组）。
// 返回：1=ok -1=已打开 -2=host 被占用/adapter 失败 -3=传输就绪检查失败
// -4=配置非法 -5=native 异常。
std::int32_t open(const std::string &config_json);

// 提交目标：1=ok -1=状态非法 -2=提交失败。
std::int32_t submit(const std::string &goal, std::int32_t max_steps);

// 协作取消（保留会话）：1=ok。
std::int32_t cancel();

// Human Takeover：阻断新决策 + 取消在途 + RELEASE_ALL + 确认失效：1=ok。
std::int32_t takeover();

// 关闭运行时（排空 + executor shutdown + adapter 销毁），返回统计 JSON。
std::string close();

// 当前状态 JSON（state/goal/task_epoch/pending_confirmations）。
std::string state_json();

// 一次性模型连通性自检（独立小栈，文本-only 决策请求），返回结果 JSON（阻塞调用）。
std::string connectivity(const std::string &config_json);

} // namespace miracle::bridge::loop
