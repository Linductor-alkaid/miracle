// Miracle JNI bridge：P0 自检入口 + P1 环境自检入口 + P2 输入链路自检入口。
//
// 职责（见 docs/design/system_architecture_design.md §2/§4）：
//  - runtimeSelfTest()：mira RuntimeBaseline 完整生命周期自检（P0）。
//  - environmentSelfTest()：Executor + AndroidHostAdapter + observe×2 全链路自检（P1）。
//  - nativeCompleteFrame()/nativeCompleteInput()：Kotlin 完成 → host_abi_impl 结算
//    （exactly-once 竞争在注册表侧闭合）。
//  - inputContractProbe()：直接 ABI 契约探针（独立 host：非法参数/过期 deadline/
//    RELEASE_ALL/长按中途取消/取消后复验；阻塞等待仅存在于本测试入口）。
//  - inputTestOpen/Dispatch/Interrupt/Close()：经 mira adapter 的输入会话
//    （InputSequence → execute() 全链路）。
//  - 显式 RegisterNatives（JNI_OnLoad），异常绝不穿越 JNI。
//  - 本文件不创建任何线程；mira Executor 由本层唯一持有并按 DEC-001 §17.2 顺序关闭。
#include "host_abi_impl.hpp"
#include "host_jni.hpp"
#include "loop_runtime.hpp"

#include <mira/adapters/android/android_host_adapter.hpp>
#include <mira/core_contracts.hpp>
#include <mira/environment.hpp>
#include <mira/observation.hpp>
#include <mira/runtime_baseline.hpp>
#include <mira/version.hpp>

#include <executor/executor.hpp>

#include <android/log.h>
#include <jni.h>

#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr char kLogTag[] = "miracle/bridge";

} // namespace

// ---- host_jni.hpp 接口实现 ----

namespace miracle::bridge {
namespace {
JavaVM *g_java_vm = nullptr;
jclass g_host_bridge = nullptr;
} // namespace

JavaVM *java_vm() { return g_java_vm; }
void set_java_vm(JavaVM *vm) { g_java_vm = vm; }
jclass host_bridge_class() { return g_host_bridge; }
void set_host_bridge_class(jclass ref) { g_host_bridge = ref; }

AttachedEnv::AttachedEnv() {
    JavaVM *vm = java_vm();
    if (vm == nullptr) {
        return;
    }
    if (vm->GetEnv(reinterpret_cast<void **>(&env_), JNI_VERSION_1_6) != JNI_OK) {
        if (vm->AttachCurrentThread(&env_, nullptr) == JNI_OK) {
            attached_ = true;
        }
    }
}

AttachedEnv::~AttachedEnv() {
    if (attached_ && java_vm() != nullptr) {
        java_vm()->DetachCurrentThread();
    }
}

} // namespace miracle::bridge

namespace {

jstring make_string(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
}

std::string result_code_name(mira::BaselineResultCode code) {
    switch (code) {
    case mira::BaselineResultCode::Applied:
        return "Applied";
    case mira::BaselineResultCode::StaleCompletionIgnored:
        return "StaleCompletionIgnored";
    case mira::BaselineResultCode::Cancelled:
        return "Cancelled";
    case mira::BaselineResultCode::Rejected:
        return "Rejected";
    case mira::BaselineResultCode::Failed:
        return "Failed";
    case mira::BaselineResultCode::ContextStopped:
        return "ContextStopped";
    case mira::BaselineResultCode::NotFound:
        return "NotFound";
    case mira::BaselineResultCode::TimedOut:
        return "TimedOut";
    }
    return "Unknown";
}

std::string runtime_state_name(mira::BaselineRuntimeState state) {
    switch (state) {
    case mira::BaselineRuntimeState::Constructed:
        return "Constructed";
    case mira::BaselineRuntimeState::Running:
        return "Running";
    case mira::BaselineRuntimeState::Stopping:
        return "Stopping";
    case mira::BaselineRuntimeState::Quiesced:
        return "Quiesced";
    case mira::BaselineRuntimeState::Stopped:
        return "Stopped";
    case mira::BaselineRuntimeState::Failed:
        return "Failed";
    }
    return "Unknown";
}

std::string build_payload(bool ok, const char *stage, const char *detail,
                          std::uint64_t init_ms, std::uint64_t wait_ms,
                          const char *result_code, bool task_terminal,
                          const char *final_state, unsigned mira_major,
                          unsigned mira_minor, unsigned mira_patch) {
    char buffer[512];
    std::snprintf(buffer, sizeof(buffer),
                  "{\"ok\":%s,\"stage\":\"%s\",\"detail\":\"%s\","
                  "\"init_ms\":%llu,\"wait_ms\":%llu,\"result_code\":\"%s\","
                  "\"task_terminal\":%s,\"final_state\":\"%s\","
                  "\"mira_version\":\"%u.%u.%u\"}",
                  ok ? "true" : "false", stage, detail,
                  static_cast<unsigned long long>(init_ms),
                  static_cast<unsigned long long>(wait_ms),
                  result_code == nullptr ? "" : result_code,
                  task_terminal ? "true" : "false", final_state, mira_major,
                  mira_minor, mira_patch);
    return buffer;
}

std::string run_baseline_self_test() {
    const auto t0 = std::chrono::steady_clock::now();
    mira::RuntimeBaseline runtime;
    if (!runtime.initialize()) {
        return build_payload(false, "initialize", "RuntimeBaseline initialize failed", 0, 0,
                             nullptr, false,
                             runtime_state_name(runtime.status().state).c_str(),
                             mira::kVersion.major, mira::kVersion.minor, mira::kVersion.patch);
    }
    const auto t1 = std::chrono::steady_clock::now();

    const mira::BaselineSubmission submission =
        runtime.submit(mira::BaselineCommand{1, 1, 0, mira::BaselineCommandKind::Command});
    if (!submission.admitted) {
        const bool shutdown_ok = runtime.request_shutdown();
        runtime.finish_shutdown();
        const std::string state_name = runtime_state_name(runtime.status().state);
        const char *detail = shutdown_ok ? "command was not admitted" :
                                        "command was not admitted; shutdown request failed";
        return build_payload(false, "submit", detail, 0, 0, nullptr, false, state_name.c_str(),
                             mira::kVersion.major, mira::kVersion.minor, mira::kVersion.patch);
    }

    const mira::BaselineResult result = runtime.wait(1, std::chrono::milliseconds(2000));
    const auto t2 = std::chrono::steady_clock::now();

    const bool shutdown_requested = runtime.request_shutdown();
    runtime.finish_shutdown();
    const auto final_state = runtime.status().state;
    const bool ok = shutdown_requested && result.code == mira::BaselineResultCode::Applied &&
                    final_state == mira::BaselineRuntimeState::Stopped;

    const auto init_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    const auto wait_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t1).count();
    const std::string code_name = result_code_name(result.code);
    const std::string state_name = runtime_state_name(final_state);
    std::string detail = "baseline completed";
    if (!shutdown_requested) {
        detail = "request_shutdown returned false";
    } else if (result.code != mira::BaselineResultCode::Applied) {
        detail = result.safe_message.empty() ? "command was not applied" : result.safe_message;
    } else if (final_state != mira::BaselineRuntimeState::Stopped) {
        detail = "runtime did not reach Stopped";
    }
    return build_payload(ok, ok ? "complete" : "verify", detail.c_str(),
                         static_cast<std::uint64_t>(init_ms),
                         static_cast<std::uint64_t>(wait_ms), code_name.c_str(),
                         result.task_terminal, state_name.c_str(), mira::kVersion.major,
                         mira::kVersion.minor, mira::kVersion.patch);
}

// 追加一帧观察结果到 JSON 数组（buffer 由调用方保证容量，帧数固定为 2）。
void append_frame_record(std::string &json, bool &first,
                         const mira::Result<mira::Observation> &observation, double elapsed_ms) {
    if (!first) {
        json += ",";
    }
    first = false;
    if (!observation.has_value()) {
        const std::string message = observation.error().safe_message;
        char buffer[384];
        std::snprintf(buffer, sizeof(buffer), "{\"ok\":false,\"error\":\"%.256s\"}",
                      message.c_str());
        json += buffer;
        return;
    }
    const mira::Observation &value = observation.value();
    char buffer[512];
    if (value.screen.has_value()) {
        const auto &descriptor = value.screen->value;
        std::snprintf(buffer, sizeof(buffer),
                      "{\"ok\":true,\"w\":%u,\"h\":%u,\"format\":\"RGBA8888\","
                      "\"planes\":%u,\"epoch\":%llu,\"artifact\":\"%.32s\","
                      "\"quality\":\"%s\",\"ms\":%.1f}",
                      descriptor.width_pixels, descriptor.height_pixels,
                      static_cast<unsigned>(descriptor.planes.size()),
                      static_cast<unsigned long long>(value.environment_epoch),
                      "present", "Good", elapsed_ms);
    } else {
        std::snprintf(buffer, sizeof(buffer),
                      "{\"ok\":false,\"error\":\"observation had no screen component\"}");
    }
    json += buffer;
}

std::string run_environment_self_test() {
    using clock = std::chrono::steady_clock;
    std::string frames_json;
    bool first = true;
    bool all_ok = true;
    std::string failure;

    executor::Executor exec;
    if (!exec.initialize(executor::ExecutorConfig{})) {
        return "{\"ok\":false,\"stage\":\"executor\",\"error\":\"initialize failed\"}";
    }

    auto created = mira::adapters::android::AndroidHostAdapter::create(exec);
    if (!created.has_value()) {
        const std::string message = created.error().safe_message;
        (void)exec.shutdown(true);
        char buffer[384];
        std::snprintf(buffer, sizeof(buffer),
                      "{\"ok\":false,\"stage\":\"adapter_create\",\"error\":\"%.256s\"}",
                      message.c_str());
        return buffer;
    }
    std::unique_ptr<mira::adapters::android::AndroidHostAdapter> adapter =
        std::move(created.value());

    for (int step = 0; step < 2; ++step) {
        mira::ObservationRequest request;
        request.required.screen = true;
        mira::OperationContext context;
        context.operation = mira::OperationId::generate();
        context.started_at = mira::Timestamp::now();
        context.deadline = clock::now() + std::chrono::seconds(10);
        const auto t0 = clock::now();
        auto observation = adapter->observe(request, context);
        const double elapsed_ms =
            std::chrono::duration<double, std::milli>(clock::now() - t0).count();
        append_frame_record(frames_json, first, observation, elapsed_ms);
        if (!observation.has_value() || !observation.value().screen.has_value()) {
            all_ok = false;
            break;
        }
    }

    const auto stats = adapter->bridge_stats();
    adapter.reset();
    const auto shutdown = exec.shutdown(true);
    const bool shutdown_ok = shutdown == executor::ShutdownResult::Completed;

    // 失败时把首帧错误提升到根字段，供 UI 直接呈现。
    std::string root_error;
    if (!all_ok) {
        const std::size_t marker = frames_json.find("\"error\":\"");
        if (marker != std::string::npos) {
            const std::size_t begin = marker + 9;
            const std::size_t end = frames_json.find('"', begin);
            root_error = frames_json.substr(begin, end == std::string::npos
                                                        ? std::string::npos
                                                        : end - begin);
        }
    }

    std::string host_stats;
    miracle_host_debug_stats_json(host_stats);
    char summary[768];
    std::snprintf(summary, sizeof(summary),
                  "{\"ok\":%s,\"stage\":\"%s\",\"error\":\"%.192s\",\"frames\":[%s],"
                  "\"bridge\":{\"submitted\":%llu,\"settled\":%llu,"
                  "\"leases_released\":%llu,\"duplicates\":%llu,\"unknowns\":%llu,"
                  "\"late\":%llu,\"rejections\":%llu,\"violations\":%llu},"
                  "\"host\":%s,\"shutdown\":\"%s\",\"mira_version\":\"%u.%u.%u\"}",
                  (all_ok && shutdown_ok) ? "true" : "false",
                  all_ok ? "complete" : "observe", root_error.c_str(), frames_json.c_str(),
                  static_cast<unsigned long long>(stats.operations_submitted),
                  static_cast<unsigned long long>(stats.operations_settled),
                  static_cast<unsigned long long>(stats.leases_released),
                  static_cast<unsigned long long>(stats.duplicate_terminal_callbacks),
                  static_cast<unsigned long long>(stats.unknown_operation_callbacks),
                  static_cast<unsigned long long>(stats.late_callbacks_after_detach),
                  static_cast<unsigned long long>(stats.executor_submission_rejections),
                  static_cast<unsigned long long>(stats.contract_violations),
                  host_stats.c_str(),
                  shutdown_ok ? "Completed" : "Incomplete",
                  mira::kVersion.major, mira::kVersion.minor, mira::kVersion.patch);
    return summary;
}

// ---- P2 输入链路自检（直接 ABI 契约探针 + adapter 会话） ----

const char *host_status_name(MiraHostStatus status) {
    switch (status) {
    case MIRA_HOST_OK:
        return "OK";
    case MIRA_HOST_ERR_INVALID_ARGUMENT:
        return "INVALID_ARGUMENT";
    case MIRA_HOST_ERR_UNAVAILABLE:
        return "UNAVAILABLE";
    case MIRA_HOST_ERR_PERMISSION_DENIED:
        return "PERMISSION_DENIED";
    case MIRA_HOST_ERR_CANCELLED:
        return "CANCELLED";
    case MIRA_HOST_ERR_DEADLINE_EXCEEDED:
        return "DEADLINE_EXCEEDED";
    case MIRA_HOST_ERR_INVALID_STATE:
        return "INVALID_STATE";
    case MIRA_HOST_ERR_EXECUTION_UNCERTAIN:
        return "EXECUTION_UNCERTAIN";
    case MIRA_HOST_ERR_CAPACITY:
        return "CAPACITY";
    case MIRA_HOST_ERR_PLATFORM_ERROR:
        return "PLATFORM_ERROR";
    default:
        return "OTHER";
    }
}

const char *input_receipt_name(std::uint32_t receipt) {
    switch (receipt) {
    case MIRA_HOST_INPUT_RECEIPT_DISPATCHED:
        return "DISPATCHED";
    case MIRA_HOST_INPUT_RECEIPT_COMPLETED:
        return "COMPLETED";
    case MIRA_HOST_INPUT_RECEIPT_REJECTED:
        return "REJECTED";
    case MIRA_HOST_INPUT_RECEIPT_UNKNOWN:
        return "UNKNOWN";
    default:
        return "NONE";
    }
}

const char *execution_status_name(mira::ExecutionStatus status) {
    switch (status) {
    case mira::ExecutionStatus::Dispatched:
        return "Dispatched";
    case mira::ExecutionStatus::Completed:
        return "Completed";
    case mira::ExecutionStatus::Rejected:
        return "Rejected";
    case mira::ExecutionStatus::Unknown:
        return "Unknown";
    }
    return "Other";
}

// 探针完成等待：宿主回调（主线程）做有界拷贝并通知；等待方为调用线程（测试专用，
// 有界超时；生产取消路径事件驱动、无阻塞）。
struct ProbeWaiter final {
    std::mutex mutex;
    std::condition_variable cv;
    std::uint64_t expected = 0;
    bool settled = false;
    std::uint64_t duplicates = 0;
    MiraHostOperationResultV1 result{};
};

void probe_on_operation_complete(void *user_data, const MiraHostOperationResultV1 *result) {
    auto *waiter = static_cast<ProbeWaiter *>(user_data);
    std::lock_guard lock(waiter->mutex);
    if (waiter->settled || result->correlation != waiter->expected) {
        waiter->duplicates += 1;
        return;
    }
    waiter->result = *result;
    waiter->settled = true;
    waiter->cv.notify_all();
}

bool probe_wait(ProbeWaiter &waiter, std::uint64_t correlation, int timeout_ms) {
    std::unique_lock lock(waiter.mutex);
    waiter.expected = correlation;
    waiter.settled = false;
    return waiter.cv.wait_for(lock, std::chrono::milliseconds(timeout_ms),
                              [&] { return waiter.settled; });
}

MiraHostInputRequestV1 probe_request(std::uint64_t correlation, std::uint32_t kind, double x,
                                     double y, double x2, double y2, std::uint32_t duration_ms,
                                     const char *text, std::int64_t deadline_ns) {
    MiraHostInputRequestV1 request{};
    request.struct_size = sizeof(request);
    request.correlation = correlation;
    request.display_id = 0;
    request.event_count = 1;
    request.events[0].kind = kind;
    request.events[0].x = x;
    request.events[0].y = y;
    request.events[0].x2 = x2;
    request.events[0].y2 = y2;
    request.events[0].duration_ms = duration_ms;
    request.events[0].text = text;
    request.events[0].text_length =
        text == nullptr ? 0 : static_cast<std::uint32_t>(std::strlen(text));
    request.deadline_ns = deadline_ns > 0 ? static_cast<std::uint64_t>(deadline_ns) : 0;
    return request;
}

std::int64_t steady_now_ns() {
    return static_cast<std::int64_t>(
        std::chrono::steady_clock::now().time_since_epoch().count());
}

std::string run_input_contract_probe(double tap_x, double tap_y) {
    std::string steps_json;
    bool all_ok = true;
    auto append_step = [&](const char *name, bool ok, const char *detail, double ms) {
        if (!steps_json.empty()) {
            steps_json += ",";
        }
        char buffer[320];
        std::snprintf(buffer, sizeof(buffer),
                      "{\"name\":\"%s\",\"ok\":%s,\"detail\":\"%.192s\",\"ms\":%.1f}", name,
                      ok ? "true" : "false", detail, ms);
        steps_json += buffer;
        if (!ok) {
            all_ok = false;
        }
    };
    const auto input_ready = [] {
        miracle::bridge::AttachedEnv attach;
        JNIEnv *env = attach.env();
        jclass bridge = miracle::bridge::host_bridge_class();
        if (env == nullptr || bridge == nullptr) {
            return 2;
        }
        jmethodID method = env->GetStaticMethodID(bridge, "inputState", "()I");
        if (method == nullptr) {
            env->ExceptionClear();
            return 2;
        }
        return static_cast<int>(env->CallStaticIntMethod(bridge, method));
    };

    const int ready = input_ready();
    if (ready != 0) {
        char error[160];
        std::snprintf(error, sizeof(error), "input state=%d (accessibility)", ready);
        std::string json;
        miracle_host_debug_stats_json(json);
        char summary[512];
        std::snprintf(summary, sizeof(summary),
                      "{\"ok\":false,\"stage\":\"accessibility\",\"error\":\"%.128s\","
                      "\"steps\":[],\"probe\":{\"duplicates\":0,\"unknowns\":0},"
                      "\"host\":%s,\"stop\":\"skipped\",\"destroy\":\"skipped\"}",
                      error, json.c_str());
        return summary;
    }

    ProbeWaiter waiter;
    MiraAndroidHostConfigV1 config{};
    config.struct_size = sizeof(config);
    config.abi_version = MIRA_ANDROID_ABI_VERSION;
    MiraHostCallbacksV1 callbacks{};
    callbacks.struct_size = sizeof(callbacks);
    callbacks.user_data = &waiter;
    callbacks.on_operation_complete = &probe_on_operation_complete;
    MiraAndroidHostV1 *host = nullptr;
    MiraHostStatus status = mira_android_host_create_v1(&config, &callbacks, &host);
    if (status != MIRA_HOST_OK || host == nullptr) {
        char summary[256];
        std::snprintf(summary, sizeof(summary),
                      "{\"ok\":false,\"stage\":\"host_create\",\"error\":\"%s\"}",
                      host_status_name(status));
        return summary;
    }
    status = mira_android_host_start_v1(host);
    if (status != MIRA_HOST_OK) {
        // 进程内单 host 约束：其他自检会话占用中。
        char summary[256];
        std::snprintf(summary, sizeof(summary),
                      "{\"ok\":false,\"stage\":\"host_start\",\"error\":\"%s\"}",
                      host_status_name(status));
        (void)mira_android_host_destroy_v1(host);
        return summary;
    }

    auto dispatch_and_wait = [&](std::uint64_t correlation, MiraHostInputRequestV1 &request,
                                 int wait_ms) -> std::pair<bool, MiraHostStatus> {
        const MiraHostStatus submitted = mira_android_host_dispatch_input_v1(host, &request, nullptr);
        if (submitted != MIRA_HOST_OK) {
            return {false, submitted};
        }
        if (!probe_wait(waiter, correlation, wait_ms)) {
            return {false, MIRA_HOST_ERR_DEADLINE_EXCEEDED};
        }
        return {true, waiter.result.status};
    };

    // 1. 非法坐标：同步快速失败（不回调）。
    {
        MiraHostInputRequestV1 request =
            probe_request(101, MIRA_HOST_INPUT_TAP, 1.5, 0.5, 0, 0, 0, nullptr, 0);
        request.deadline_ns = 0;
        const MiraHostStatus submitted =
            mira_android_host_dispatch_input_v1(host, &request, nullptr);
        const bool ok = submitted == MIRA_HOST_ERR_INVALID_ARGUMENT;
        append_step("invalid_coords", ok,
                    ok ? "sync INVALID_ARGUMENT" : host_status_name(submitted), 0.0);
    }

    // 2. 过期 deadline：受理后按 DEADLINE_EXCEEDED 结算（不触碰平台）。
    {
        MiraHostInputRequestV1 request =
            probe_request(102, MIRA_HOST_INPUT_LONG_PRESS, 0.5, 0.5, 0, 0, 600, nullptr, 0);
        request.deadline_ns = static_cast<std::uint64_t>(steady_now_ns() - 1'000'000'000LL);
        const auto t0 = std::chrono::steady_clock::now();
        const auto outcome = dispatch_and_wait(102, request, 3000);
        const double ms = std::chrono::duration<double, std::milli>(
                              std::chrono::steady_clock::now() - t0)
                              .count();
        const bool ok = outcome.first && outcome.second == MIRA_HOST_ERR_DEADLINE_EXCEEDED &&
                        waiter.result.side_effect_may_have_occurred == 0;
        char detail[128];
        std::snprintf(detail, sizeof(detail), "status=%s side=%u",
                      host_status_name(outcome.second), waiter.result.side_effect_may_have_occurred);
        append_step("expired_deadline", ok, detail, ms);
    }

    // 3. RELEASE_ALL：安全原语随时可发，预期快速完成。
    {
        MiraHostInputRequestV1 request =
            probe_request(103, MIRA_HOST_INPUT_RELEASE_ALL, 0, 0, 0, 0, 0, nullptr, 0);
        request.deadline_ns = static_cast<std::uint64_t>(steady_now_ns() + 3'000'000'000LL);
        const auto t0 = std::chrono::steady_clock::now();
        const auto outcome = dispatch_and_wait(103, request, 3000);
        const double ms = std::chrono::duration<double, std::milli>(
                              std::chrono::steady_clock::now() - t0)
                              .count();
        const bool ok = outcome.first && outcome.second == MIRA_HOST_OK &&
                        waiter.result.input_receipt == MIRA_HOST_INPUT_RECEIPT_COMPLETED;
        char detail[160];
        std::snprintf(detail, sizeof(detail), "status=%s receipt=%s", host_status_name(outcome.second),
                      input_receipt_name(waiter.result.input_receipt));
        append_step("release_all", ok, detail, ms);
    }

    // 4. 长按中途取消：手势在途时 cancel → EXECUTION_UNCERTAIN + side_effect=1。
    {
        MiraHostInputRequestV1 request =
            probe_request(104, MIRA_HOST_INPUT_LONG_PRESS, 0.5, 0.5, 0, 0, 3000, nullptr, 0);
        request.deadline_ns = static_cast<std::uint64_t>(steady_now_ns() + 8'000'000'000LL);
        const auto t0 = std::chrono::steady_clock::now();
        const MiraHostStatus submitted =
            mira_android_host_dispatch_input_v1(host, &request, nullptr);
        bool ok = false;
        char detail[192];
        if (submitted == MIRA_HOST_OK) {
            // 有界等待手势进入平台（测试专用 sleep；生产路径无阻塞）。
            std::this_thread::sleep_for(std::chrono::milliseconds(700));
            const MiraHostStatus cancelled = mira_android_host_cancel_operation_v1(host, 104);
            const bool settled = probe_wait(waiter, 104, 4000);
            const double ms = std::chrono::duration<double, std::milli>(
                                  std::chrono::steady_clock::now() - t0)
                                  .count();
            ok = cancelled == MIRA_HOST_OK && settled &&
                 waiter.result.status == MIRA_HOST_ERR_EXECUTION_UNCERTAIN &&
                 waiter.result.side_effect_may_have_occurred == 1;
            std::snprintf(detail, sizeof(detail), "cancel=%s settled=%s status=%s side=%u",
                          host_status_name(cancelled), settled ? "true" : "false",
                          host_status_name(waiter.result.status),
                          waiter.result.side_effect_may_have_occurred);
            append_step("cancel_midflight", ok, detail, ms);
        } else {
            std::snprintf(detail, sizeof(detail), "submit=%s", host_status_name(submitted));
            append_step("cancel_midflight", false, detail, 0.0);
        }
    }

    // 5. 取消后复验 tap：证明输入管线未滞死（无粘滞触点）。
    {
        MiraHostInputRequestV1 request =
            probe_request(105, MIRA_HOST_INPUT_TAP, tap_x, tap_y, 0, 0, 0, nullptr, 0);
        request.deadline_ns = static_cast<std::uint64_t>(steady_now_ns() + 5'000'000'000LL);
        const auto t0 = std::chrono::steady_clock::now();
        const auto outcome = dispatch_and_wait(105, request, 5000);
        const double ms = std::chrono::duration<double, std::milli>(
                              std::chrono::steady_clock::now() - t0)
                              .count();
        const bool ok = outcome.first && outcome.second == MIRA_HOST_OK &&
                        waiter.result.input_receipt == MIRA_HOST_INPUT_RECEIPT_COMPLETED;
        char detail[160];
        std::snprintf(detail, sizeof(detail), "status=%s receipt=%s",
                      host_status_name(outcome.second),
                      input_receipt_name(waiter.result.input_receipt));
        append_step("tap_after_cancel", ok, detail, ms);
    }

    const MiraHostStatus stop_status = mira_android_host_stop_v1(host);
    const MiraHostStatus destroy_status = stop_status == MIRA_HOST_OK
                                              ? mira_android_host_destroy_v1(host)
                                              : MIRA_HOST_ERR_INVALID_STATE;
    if (stop_status != MIRA_HOST_OK) {
        // stop 失败（有 lease/操作悬挂）时不得 destroy；泄漏交由进程回收并如实上报。
        append_step("stop", false, host_status_name(stop_status), 0.0);
    }
    if (stop_status == MIRA_HOST_OK && destroy_status != MIRA_HOST_OK) {
        append_step("destroy", false, host_status_name(destroy_status), 0.0);
    }

    std::string host_stats;
    miracle_host_debug_stats_json(host_stats);
    char summary[1024];
    std::snprintf(summary, sizeof(summary),
                  "{\"ok\":%s,\"stage\":\"%s\",\"error\":\"\","
                  "\"steps\":[%s],"
                  "\"probe\":{\"duplicates\":%llu,\"unknowns\":0},"
                  "\"host\":%s,\"stop\":\"%s\",\"destroy\":\"%s\"}",
                  all_ok ? "true" : "false", all_ok ? "complete" : "probe", steps_json.c_str(),
                  static_cast<unsigned long long>(waiter.duplicates), host_stats.c_str(),
                  host_status_name(stop_status), host_status_name(destroy_status));
    return summary;
}

// ---- adapter 会话（InputSequence → AndroidHostAdapter.execute() 全链路） ----

struct InputTestSession final {
    executor::Executor executor;
    std::unique_ptr<mira::adapters::android::AndroidHostAdapter> adapter;
};

std::mutex g_input_test_mutex;
std::unique_ptr<InputTestSession> g_input_test_session;

std::string run_input_test_dispatch(std::int32_t kind, double x, double y, double x2, double y2,
                                    const std::string &text, std::int32_t duration_ms,
                                    std::int32_t timeout_ms) {
    // mira InputEvent 自 cbed6ad 起携带 duration_ms（MIR-20260906-005 关闭）：
    // adapter 映射到 ABI duration_ms，超宿主 max_gesture_duration_ms 由 adapter 拒绝。
    InputTestSession *session = nullptr;
    {
        std::lock_guard lock(g_input_test_mutex);
        session = g_input_test_session.get();
    }
    if (session == nullptr) {
        return "{\"ok\":false,\"error\":\"session not open\"}";
    }
    const char *kind_name = "unknown";
    std::string payload;
    char number[128];
    switch (kind) {
    case MIRA_HOST_INPUT_TAP:
        kind_name = "tap";
        std::snprintf(number, sizeof(number), "%.17g,%.17g", x, y);
        payload = number;
        break;
    case MIRA_HOST_INPUT_LONG_PRESS:
        kind_name = "long_press";
        std::snprintf(number, sizeof(number), "%.17g,%.17g", x, y);
        payload = number;
        break;
    case MIRA_HOST_INPUT_SWIPE:
        kind_name = "swipe";
        std::snprintf(number, sizeof(number), "%.17g,%.17g,%.17g,%.17g", x, y, x2, y2);
        payload = number;
        break;
    case MIRA_HOST_INPUT_TYPE:
        kind_name = "type";
        payload = text;
        break;
    case MIRA_HOST_INPUT_BACK:
        kind_name = "back";
        break;
    case MIRA_HOST_INPUT_HOME:
        kind_name = "home";
        break;
    default:
        return "{\"ok\":false,\"error\":\"unsupported kind for adapter path\"}";
    }

    mira::InputSequence sequence;
    mira::InputEvent event{kind_name, payload};
    if (duration_ms > 0) {
        event.duration_ms = static_cast<std::uint32_t>(duration_ms);
    }
    sequence.events.push_back(std::move(event));
    mira::OperationContext context;
    context.operation = mira::OperationId::generate();
    context.started_at = mira::Timestamp::now();
    context.deadline =
        std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);

    const auto t0 = std::chrono::steady_clock::now();
    auto receipt = session->adapter->execute(sequence, context);
    const double ms = std::chrono::duration<double, std::milli>(
                          std::chrono::steady_clock::now() - t0)
                          .count();
    char summary[512];
    if (!receipt.has_value()) {
        const std::string message = receipt.error().safe_message;
        std::snprintf(summary, sizeof(summary),
                      "{\"ok\":false,\"kind\":\"%s\",\"error\":\"%.192s\",\"ms\":%.1f}",
                      kind_name, message.c_str(), ms);
        return summary;
    }
    const mira::ExecutionReceipt &value = receipt.value();
    const bool completed = value.status == mira::ExecutionStatus::Completed;
    std::snprintf(summary, sizeof(summary),
                  "{\"ok\":%s,\"kind\":\"%s\",\"receipt\":\"%s\","
                  "\"side_effect\":%s,\"epoch\":%llu,\"ms\":%.1f,\"error\":\"\"}",
                  completed ? "true" : "false", kind_name,
                  execution_status_name(value.status),
                  value.side_effect_may_have_occurred ? "true" : "false",
                  static_cast<unsigned long long>(value.environment_epoch), ms);
    return summary;
}

// ---- JNI 导出 ----

jstring JNICALL native_runtime_self_test(JNIEnv *env, jclass /*clazz*/) {
    try {
        const std::string payload = run_baseline_self_test();
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "self test: %s", payload.c_str());
        return make_string(env, payload);
    } catch (const std::exception &error) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "self test exception: %s", error.what());
        return make_string(env, build_payload(false, "exception", "native exception", 0, 0,
                                              nullptr, false, "", mira::kVersion.major,
                                              mira::kVersion.minor, mira::kVersion.patch));
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "self test unknown exception");
        return make_string(env, build_payload(false, "exception", "unknown native exception", 0,
                                              0, nullptr, false, "", mira::kVersion.major,
                                              mira::kVersion.minor, mira::kVersion.patch));
    }
}

jstring JNICALL native_environment_self_test(JNIEnv *env, jclass /*clazz*/) {
    try {
        const std::string payload = run_environment_self_test();
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "env self test: %s", payload.c_str());
        return make_string(env, payload);
    } catch (const std::exception &error) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "env self test exception: %s",
                            error.what());
        return make_string(env, "{\"ok\":false,\"stage\":\"exception\",\"error\":\"native\"}");
    } catch (...) {
        return make_string(env, "{\"ok\":false,\"stage\":\"exception\",\"error\":\"unknown\"}");
    }
}

void JNICALL native_complete_frame(JNIEnv *env, jclass /*clazz*/, jlong correlation, jint ok,
                                   jint width, jint height, jint rotation, jbyteArray pixels,
                                   jbyteArray encoded, jlong begin_ns, jlong end_ns,
                                   jint err_code) {
    try {
        if (pixels == nullptr) {
            (void)miracle_host_complete_frame(
                static_cast<std::uint64_t>(correlation), false, 0, 0, 0, nullptr, 0, nullptr, 0,
                0, 0,
                err_code != 0 ? static_cast<MiraHostStatus>(err_code) : MIRA_HOST_ERR_UNAVAILABLE);
            return;
        }
        const jsize length = env->GetArrayLength(pixels);
        jboolean is_copy = JNI_FALSE;
        auto *bytes = reinterpret_cast<const std::uint8_t *>(
            env->GetByteArrayElements(pixels, &is_copy));
        if (bytes == nullptr) {
            (void)miracle_host_complete_frame(
                static_cast<std::uint64_t>(correlation), false, 0, 0, 0, nullptr, 0, nullptr, 0,
                0, 0, MIRA_HOST_ERR_CAPACITY);
            return;
        }
        // 编码载荷（PNG）：拷贝后传入（登记方在 host_abi_impl 内复制，此处出参即可）。
        std::vector<std::uint8_t> encoded_bytes;
        if (encoded != nullptr) {
            const jsize encoded_length = env->GetArrayLength(encoded);
            if (encoded_length > 0) {
                encoded_bytes.resize(static_cast<std::size_t>(encoded_length));
                env->GetByteArrayRegion(encoded, 0, encoded_length,
                                        reinterpret_cast<jbyte *>(encoded_bytes.data()));
            }
        }
        const MiraHostStatus status = miracle_host_complete_frame(
            static_cast<std::uint64_t>(correlation), ok != 0, static_cast<std::uint32_t>(width),
            static_cast<std::uint32_t>(height), static_cast<std::uint32_t>(rotation), bytes,
            static_cast<std::uint64_t>(length),
            encoded_bytes.empty() ? nullptr : encoded_bytes.data(), encoded_bytes.size(),
            static_cast<std::uint64_t>(begin_ns), static_cast<std::uint64_t>(end_ns),
            err_code != 0 ? static_cast<MiraHostStatus>(err_code) : MIRA_HOST_ERR_UNAVAILABLE);
        env->ReleaseByteArrayElements(pixels, reinterpret_cast<jbyte *>(const_cast<std::uint8_t *>(bytes)),
                                      JNI_ABORT);
        if (status != MIRA_HOST_OK) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                "complete_frame settled with status %d for correlation %llu",
                                static_cast<int>(status),
                                static_cast<unsigned long long>(correlation));
        }
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "complete_frame unexpected exception (correlation %lld)",
                            static_cast<long long>(correlation));
    }
}

void JNICALL native_notify_epoch_changed(JNIEnv * /*env*/, jclass /*clazz*/) {
    miracle_host_notify_epoch_changed();
}

void JNICALL native_complete_input(JNIEnv * /*env*/, jclass /*clazz*/, jlong correlation,
                                   jint status, jint receipt, jint side_effect) {
    try {
        const MiraHostStatus settled = miracle_host_complete_input(
            static_cast<std::uint64_t>(correlation), static_cast<MiraHostStatus>(status),
            static_cast<std::uint32_t>(receipt), static_cast<std::uint32_t>(side_effect));
        if (settled != MIRA_HOST_OK) {
            __android_log_print(
                ANDROID_LOG_WARN, kLogTag,
                "complete_input settled with status %d for correlation %lld",
                static_cast<int>(settled), static_cast<long long>(correlation));
        }
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "complete_input unexpected exception (correlation %lld)",
                            static_cast<long long>(correlation));
    }
}

jstring JNICALL native_input_contract_probe(JNIEnv *env, jclass /*clazz*/, jdouble tap_x,
                                            jdouble tap_y) {
    try {
        const std::string payload = run_input_contract_probe(tap_x, tap_y);
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "input probe: %s", payload.c_str());
        return make_string(env, payload);
    } catch (const std::exception &error) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "input probe exception: %s", error.what());
        return make_string(env, "{\"ok\":false,\"stage\":\"exception\",\"error\":\"native\"}");
    } catch (...) {
        return make_string(env, "{\"ok\":false,\"stage\":\"exception\",\"error\":\"unknown\"}");
    }
}

jint JNICALL native_input_test_open(JNIEnv * /*env*/, jclass /*clazz*/) {
    try {
        std::lock_guard lock(g_input_test_mutex);
        if (g_input_test_session != nullptr) {
            return -1; // 会话已打开
        }
        auto session = std::make_unique<InputTestSession>();
        if (!session->executor.initialize(executor::ExecutorConfig{})) {
            return -3;
        }
        auto created =
            mira::adapters::android::AndroidHostAdapter::create(session->executor);
        if (!created.has_value()) {
            (void)session->executor.shutdown(true);
            return -4;
        }
        session->adapter = std::move(created.value());
        g_input_test_session = std::move(session);
        return 1;
    } catch (...) {
        return -5;
    }
}

jstring JNICALL native_input_test_dispatch(JNIEnv *env, jclass /*clazz*/, jint kind, jdouble x,
                                           jdouble y, jdouble x2, jdouble y2, jstring text,
                                           jint duration_ms, jint timeout_ms) {
    try {
        std::string text_value;
        if (text != nullptr) {
            const char *chars = env->GetStringUTFChars(text, nullptr);
            if (chars == nullptr) {
                return make_string(env, "{\"ok\":false,\"error\":\"text jni failed\"}");
            }
            text_value = chars;
            env->ReleaseStringUTFChars(text, chars);
        }
        return make_string(
            env, run_input_test_dispatch(kind, x, y, x2, y2, text_value, duration_ms,
                                         timeout_ms <= 0 ? 8000 : timeout_ms));
    } catch (const std::exception &error) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "input test dispatch exception: %s",
                            error.what());
        return make_string(env, "{\"ok\":false,\"error\":\"native exception\"}");
    } catch (...) {
        return make_string(env, "{\"ok\":false,\"error\":\"unknown native exception\"}");
    }
}

jint JNICALL native_input_test_interrupt(JNIEnv * /*env*/, jclass /*clazz*/) {
    try {
        InputTestSession *session = nullptr;
        {
            std::lock_guard lock(g_input_test_mutex);
            session = g_input_test_session.get();
        }
        if (session == nullptr) {
            return -1;
        }
        mira::OperationContext context;
        context.operation = mira::OperationId::generate();
        context.started_at = mira::Timestamp::now();
        (void)session->adapter->interrupt(context);
        return 1;
    } catch (...) {
        return -2;
    }
}

jstring JNICALL native_input_test_close(JNIEnv *env, jclass /*clazz*/) {
    try {
        std::unique_ptr<InputTestSession> session;
        {
            std::lock_guard lock(g_input_test_mutex);
            session = std::move(g_input_test_session);
            g_input_test_session = nullptr;
        }
        if (session == nullptr) {
            return make_string(env, "{\"ok\":false,\"error\":\"session not open\"}");
        }
        const auto stats = session->adapter->bridge_stats();
        session->adapter.reset();
        const auto shutdown = session->executor.shutdown(true);
        const bool shutdown_ok = shutdown == executor::ShutdownResult::Completed;
        std::string host_stats;
        miracle_host_debug_stats_json(host_stats);
        char summary[640];
        std::snprintf(summary, sizeof(summary),
                      "{\"ok\":%s,"
                      "\"bridge\":{\"submitted\":%llu,\"settled\":%llu,"
                      "\"leases_released\":%llu,\"duplicates\":%llu,\"unknowns\":%llu,"
                      "\"late\":%llu,\"rejections\":%llu,\"violations\":%llu},"
                      "\"host\":%s,\"shutdown\":\"%s\"}",
                      shutdown_ok ? "true" : "false",
                      static_cast<unsigned long long>(stats.operations_submitted),
                      static_cast<unsigned long long>(stats.operations_settled),
                      static_cast<unsigned long long>(stats.leases_released),
                      static_cast<unsigned long long>(stats.duplicate_terminal_callbacks),
                      static_cast<unsigned long long>(stats.unknown_operation_callbacks),
                      static_cast<unsigned long long>(stats.late_callbacks_after_detach),
                      static_cast<unsigned long long>(stats.executor_submission_rejections),
                      static_cast<unsigned long long>(stats.contract_violations),
                      host_stats.c_str(),
                      shutdown_ok ? "Completed" : "Incomplete");
        return make_string(env, summary);
    } catch (const std::exception &error) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "input test close exception: %s",
                            error.what());
        return make_string(env, "{\"ok\":false,\"error\":\"native exception\"}");
    } catch (...) {
        return make_string(env, "{\"ok\":false,\"error\":\"unknown native exception\"}");
    }
}

// ---- P3 闭环运行时（loop_runtime 转发；异常不穿越 JNI） ----

jint JNICALL native_loop_open(JNIEnv *env, jclass /*clazz*/, jstring config) {
    try {
        if (config == nullptr) {
            return -4;
        }
        const char *chars = env->GetStringUTFChars(config, nullptr);
        if (chars == nullptr) {
            return -5;
        }
        const std::string config_json = chars;
        env->ReleaseStringUTFChars(config, chars);
        return miracle::bridge::loop::open(config_json);
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "loop open exception");
        return -5;
    }
}

jint JNICALL native_loop_submit(JNIEnv *env, jclass /*clazz*/, jstring goal, jint max_steps) {
    try {
        if (goal == nullptr) {
            return -1;
        }
        const char *chars = env->GetStringUTFChars(goal, nullptr);
        if (chars == nullptr) {
            return -2;
        }
        const std::string goal_text = chars;
        env->ReleaseStringUTFChars(goal, chars);
        return miracle::bridge::loop::submit(goal_text, max_steps);
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "loop submit exception");
        return -2;
    }
}

jint JNICALL native_loop_cancel(JNIEnv * /*env*/, jclass /*clazz*/) {
    try {
        return miracle::bridge::loop::cancel();
    } catch (...) {
        return -2;
    }
}

jint JNICALL native_loop_takeover(JNIEnv * /*env*/, jclass /*clazz*/) {
    try {
        return miracle::bridge::loop::takeover();
    } catch (...) {
        return -2;
    }
}

jstring JNICALL native_loop_close(JNIEnv *env, jclass /*clazz*/) {
    try {
        return make_string(env, miracle::bridge::loop::close());
    } catch (const std::exception &error) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "loop close exception: %s", error.what());
        return make_string(env, "{\"ok\":false,\"error\":\"native exception\"}");
    } catch (...) {
        return make_string(env, "{\"ok\":false,\"error\":\"unknown native exception\"}");
    }
}

jstring JNICALL native_loop_state(JNIEnv *env, jclass /*clazz*/) {
    try {
        return make_string(env, miracle::bridge::loop::state_json());
    } catch (...) {
        return make_string(env, "{\"state\":\"closed\"}");
    }
}

jstring JNICALL native_model_connectivity(JNIEnv *env, jclass /*clazz*/, jstring config) {
    try {
        if (config == nullptr) {
            return make_string(env, "{\"ok\":false,\"stage\":\"config\",\"error\":\"null\"}");
        }
        const char *chars = env->GetStringUTFChars(config, nullptr);
        if (chars == nullptr) {
            return make_string(env, "{\"ok\":false,\"stage\":\"config\",\"error\":\"jni\"}");
        }
        const std::string config_json = chars;
        env->ReleaseStringUTFChars(config, chars);
        const std::string result = miracle::bridge::loop::connectivity(config_json);
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "connectivity: %s", result.c_str());
        return make_string(env, result);
    } catch (const std::exception &error) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "connectivity exception: %s",
                            error.what());
        return make_string(env, "{\"ok\":false,\"stage\":\"exception\",\"error\":\"native\"}");
    } catch (...) {
        return make_string(env, "{\"ok\":false,\"stage\":\"exception\",\"error\":\"unknown\"}");
    }
}

jint JNICALL native_consent_resolve(JNIEnv *env, jclass /*clazz*/, jstring challenge,
                                    jstring nonce, jboolean approve) {
    try {
        if (challenge == nullptr || nonce == nullptr) {
            return 2;
        }
        const char *challenge_chars = env->GetStringUTFChars(challenge, nullptr);
        const char *nonce_chars = env->GetStringUTFChars(nonce, nullptr);
        if (challenge_chars == nullptr || nonce_chars == nullptr) {
            if (challenge_chars != nullptr) {
                env->ReleaseStringUTFChars(challenge, challenge_chars);
            }
            if (nonce_chars != nullptr) {
                env->ReleaseStringUTFChars(nonce, nonce_chars);
            }
            return 2;
        }
        const std::string challenge_hex = challenge_chars;
        const std::string nonce_hex = nonce_chars;
        env->ReleaseStringUTFChars(challenge, challenge_chars);
        env->ReleaseStringUTFChars(nonce, nonce_chars);
        const std::int32_t outcome =
            miracle::bridge::loop::resolve_confirmation(challenge_hex, nonce_hex,
                                                        approve == JNI_TRUE);
        if (outcome != 0 && outcome != 1) {
            return outcome; // 2=未找到 -3=校验失败（不派发、不结算）
        }
        // 放行/拒绝落到停放操作：派发平台或结算 REJECTED(side=0)。
        const std::uint64_t correlation =
            outcome == 0 ? miracle::bridge::loop::confirmation_correlation(challenge_hex) : 0;
        if (outcome == 0) {
            if (correlation == 0) {
                return 2; // 已被取消/失效（确认 settlement 已通知 Kotlin）
            }
            const MiraHostStatus released = miracle_host_confirm_release(correlation, true);
            return released == MIRA_HOST_OK ? 0 : -4;
        }
        // 拒绝：停放表中的 correlation 需要查询（尚未清除前）。
        const std::uint64_t denied =
            miracle::bridge::loop::confirmation_correlation(challenge_hex);
        if (denied != 0) {
            (void)miracle_host_confirm_release(denied, false);
        }
        return 1;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "consent resolve exception");
        return -5;
    }
}

void JNICALL native_http_exchange_complete(JNIEnv *env, jclass /*clazz*/, jlong exchange_id,
                                           jint status, jstring headers, jbyteArray body) {
    try {
        std::string headers_json;
        if (headers != nullptr) {
            const char *chars = env->GetStringUTFChars(headers, nullptr);
            if (chars != nullptr) {
                headers_json = chars;
                env->ReleaseStringUTFChars(headers, chars);
            } else {
                env->ExceptionClear();
            }
        }
        std::string body_bytes;
        if (body != nullptr) {
            const jsize length = env->GetArrayLength(body);
            body_bytes.resize(static_cast<std::size_t>(length));
            if (length > 0) {
                env->GetByteArrayRegion(body, 0, length,
                                        reinterpret_cast<jbyte *>(body_bytes.data()));
            }
        }
        miracle::bridge::loop::complete_http_exchange(static_cast<std::uint64_t>(exchange_id),
                                                      status, headers_json, body_bytes);
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "http exchange complete exception (id %lld)",
                            static_cast<long long>(exchange_id));
    }
}

const JNINativeMethod kNativeBridgeMethods[] = {
    {"runtimeSelfTest", "()Ljava/lang/String;",
     reinterpret_cast<void *>(&native_runtime_self_test)},
};

const JNINativeMethod kHostBridgeMethods[] = {
    {"nativeCompleteFrame", "(JIIII[B[BJJI)V",
     reinterpret_cast<void *>(&native_complete_frame)},
    {"nativeCompleteInput", "(JIII)V", reinterpret_cast<void *>(&native_complete_input)},
    {"nativeNotifyEpochChanged", "()V", reinterpret_cast<void *>(&native_notify_epoch_changed)},
    {"environmentSelfTest", "()Ljava/lang/String;",
     reinterpret_cast<void *>(&native_environment_self_test)},
    {"inputContractProbe", "(DD)Ljava/lang/String;",
     reinterpret_cast<void *>(&native_input_contract_probe)},
    {"inputTestOpen", "()I", reinterpret_cast<void *>(&native_input_test_open)},
    {"inputTestDispatch", "(IDDDDLjava/lang/String;II)Ljava/lang/String;",
     reinterpret_cast<void *>(&native_input_test_dispatch)},
    {"inputTestInterrupt", "()I", reinterpret_cast<void *>(&native_input_test_interrupt)},
    {"inputTestClose", "()Ljava/lang/String;",
     reinterpret_cast<void *>(&native_input_test_close)},
    {"loopOpen", "(Ljava/lang/String;)I", reinterpret_cast<void *>(&native_loop_open)},
    {"loopSubmit", "(Ljava/lang/String;I)I", reinterpret_cast<void *>(&native_loop_submit)},
    {"loopCancel", "()I", reinterpret_cast<void *>(&native_loop_cancel)},
    {"loopTakeover", "()I", reinterpret_cast<void *>(&native_loop_takeover)},
    {"loopClose", "()Ljava/lang/String;", reinterpret_cast<void *>(&native_loop_close)},
    {"loopState", "()Ljava/lang/String;", reinterpret_cast<void *>(&native_loop_state)},
    {"modelConnectivityTest", "(Ljava/lang/String;)Ljava/lang/String;",
     reinterpret_cast<void *>(&native_model_connectivity)},
    {"consentResolve", "(Ljava/lang/String;Ljava/lang/String;Z)I",
     reinterpret_cast<void *>(&native_consent_resolve)},
    {"nativeHttpExchangeComplete", "(JILjava/lang/String;[B)V",
     reinterpret_cast<void *>(&native_http_exchange_complete)},
};

} // namespace

jint JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    miracle::bridge::set_java_vm(vm);

    const jclass bridge = env->FindClass("dev/linductor/miracle/runtime/NativeBridge");
    if (bridge == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "NativeBridge class not found");
        return JNI_ERR;
    }
    if (env->RegisterNatives(bridge, kNativeBridgeMethods, 1) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "NativeBridge RegisterNatives failed");
        return JNI_ERR;
    }

    const jclass host_bridge = env->FindClass("dev/linductor/miracle/host/HostBridge");
    if (host_bridge == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "HostBridge class not found");
        return JNI_ERR;
    }
    if (env->RegisterNatives(host_bridge, kHostBridgeMethods,
                             sizeof(kHostBridgeMethods) / sizeof(kHostBridgeMethods[0])) !=
        JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "HostBridge RegisterNatives failed");
        return JNI_ERR;
    }
    const jclass host_bridge_ref = static_cast<jclass>(env->NewGlobalRef(host_bridge));
    if (host_bridge_ref == nullptr) {
        return JNI_ERR;
    }
    miracle::bridge::set_host_bridge_class(host_bridge_ref);
    return JNI_VERSION_1_6;
}
