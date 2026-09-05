// Miracle JNI bridge：P0 自检入口 + P1 环境自检入口。
//
// 职责（见 docs/design/system_architecture_design.md §2/§4）：
//  - runtimeSelfTest()：mira RuntimeBaseline 完整生命周期自检（P0）。
//  - environmentSelfTest()：Executor + AndroidHostAdapter + observe×2 全链路自检（P1）。
//  - nativeCompleteFrame()：Kotlin 帧完成 → host_abi_impl 结算（exactly-once 竞争在
//    注册表侧闭合）。
//  - 显式 RegisterNatives（JNI_OnLoad），异常绝不穿越 JNI。
//  - 本文件不创建任何线程；mira Executor 由本层唯一持有并按 DEC-001 §17.2 顺序关闭。
#include "host_abi_impl.hpp"
#include "host_jni.hpp"

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
#include <cstdio>
#include <memory>
#include <string>

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
                                   jlong begin_ns, jlong end_ns, jint err_code) {
    try {
        if (pixels == nullptr) {
            (void)miracle_host_complete_frame(
                static_cast<std::uint64_t>(correlation), false, 0, 0, 0, nullptr, 0, 0, 0,
                err_code != 0 ? static_cast<MiraHostStatus>(err_code) : MIRA_HOST_ERR_UNAVAILABLE);
            return;
        }
        const jsize length = env->GetArrayLength(pixels);
        jboolean is_copy = JNI_FALSE;
        auto *bytes = reinterpret_cast<const std::uint8_t *>(
            env->GetByteArrayElements(pixels, &is_copy));
        if (bytes == nullptr) {
            (void)miracle_host_complete_frame(
                static_cast<std::uint64_t>(correlation), false, 0, 0, 0, nullptr, 0, 0, 0,
                MIRA_HOST_ERR_CAPACITY);
            return;
        }
        const MiraHostStatus status = miracle_host_complete_frame(
            static_cast<std::uint64_t>(correlation), ok != 0, static_cast<std::uint32_t>(width),
            static_cast<std::uint32_t>(height), static_cast<std::uint32_t>(rotation), bytes,
            static_cast<std::uint64_t>(length), static_cast<std::uint64_t>(begin_ns),
            static_cast<std::uint64_t>(end_ns),
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

const JNINativeMethod kNativeBridgeMethods[] = {
    {"runtimeSelfTest", "()Ljava/lang/String;",
     reinterpret_cast<void *>(&native_runtime_self_test)},
};

const JNINativeMethod kHostBridgeMethods[] = {
    {"nativeCompleteFrame", "(JIIII[BJJI)V",
     reinterpret_cast<void *>(&native_complete_frame)},
    {"nativeNotifyEpochChanged", "()V", reinterpret_cast<void *>(&native_notify_epoch_changed)},
    {"environmentSelfTest", "()Ljava/lang/String;",
     reinterpret_cast<void *>(&native_environment_self_test)},
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
    if (env->RegisterNatives(host_bridge, kHostBridgeMethods, 3) != JNI_OK) {
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
