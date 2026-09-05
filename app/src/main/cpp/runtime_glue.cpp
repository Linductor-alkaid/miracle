// Miracle JNI bridge：P0 自检入口。
//
// 职责（见 docs/design/system_architecture_design.md §2/§4）：
//  - 唯一 native 入口 runtimeSelfTest()：驱动 mira RuntimeBaseline 的完整生命周期
//    （initialize -> submit -> wait -> request_shutdown -> finish_shutdown），
//    返回结构化 JSON 结果供 UI 呈现。
//  - 显式 RegisterNatives（JNI_OnLoad），避免符号查找歧义。
//  - 本文件不创建任何线程；Executor 由 mira RuntimeBaseline 内部管理，
//    关闭顺序遵循 mira DEC-001（§17.2）。异常绝不穿越 JNI。
#include <mira/runtime_baseline.hpp>
#include <mira/version.hpp>

#include <android/log.h>
#include <jni.h>

#include <chrono>
#include <cstdio>
#include <cstring>
#include <string>

namespace {

constexpr char kLogTag[] = "miracle/bridge";

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

// 组装自检 JSON。字符串值均为内部枚举名，无需转义。
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
                             nullptr, false, runtime_state_name(runtime.status().state).c_str(),
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

jstring JNICALL native_runtime_self_test(JNIEnv *env, jclass /*clazz*/) {
    try {
        const std::string payload = run_baseline_self_test();
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "self test: %s", payload.c_str());
        return make_string(env, payload);
    } catch (const std::exception &error) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "self test exception: %s", error.what());
        return make_string(env,
                           build_payload(false, "exception", "native exception", 0, 0, nullptr,
                                         false, "", mira::kVersion.major, mira::kVersion.minor,
                                         mira::kVersion.patch)
                               .c_str());
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "self test unknown exception");
        return make_string(env,
                           build_payload(false, "exception", "unknown native exception", 0, 0,
                                         nullptr, false, "", mira::kVersion.major,
                                         mira::kVersion.minor, mira::kVersion.patch)
                               .c_str());
    }
}

const JNINativeMethod kNativeMethods[] = {
    {"runtimeSelfTest", "()Ljava/lang/String;", reinterpret_cast<void *>(&native_runtime_self_test)},
};

} // namespace

jint JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    const jclass bridge =
        env->FindClass("dev/linductor/miracle/runtime/NativeBridge");
    if (bridge == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "NativeBridge class not found");
        return JNI_ERR;
    }
    if (env->RegisterNatives(bridge, kNativeMethods, 1) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "RegisterNatives failed");
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
