// mira Android Host ABI v1 的宿主侧实现（P1：截屏路径可用，其余 fail-closed）。
//
// 语义对齐 host_abi.h 冻结契约与本仓库 P1 计划的"关键实现决策"：
//  - lease 内存由 native 拥有（malloc 拷贝），release() 释放并递减未决计数；
//    stop 在 lease 未清零时有界等待后如实返回 EXECUTION_UNCERTAIN。
//  - epoch 由 native 单调维护；notifyEpochChanged 递增并触发 capabilities 变化回调。
//  - 能力快照诚实：仅截屏可用（RGBA8888/virtual display）；ui_tree 与输入 fail-closed。
//  - exactly-once：操作注册表 erase 竞争决定终态归属；重复/未知/迟到完成计数并丢弃。
//  - 本文件不创建线程；完成回调在完成线程直接调用，回调外不持有注册表锁。
#include "host_jni.hpp"

#include <mira/adapters/android/host_abi.h>

#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <new>
#include <string>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

struct PendingOp final {
    std::uint32_t kind; // MiraHostOperationKind
};

struct LeaseContext final {
    std::atomic<std::uint64_t> *outstanding;
    std::uint8_t *buffer;
};

void release_lease(std::uint64_t /*lease_id*/, void *user_data) {
    auto *ctx = static_cast<LeaseContext *>(user_data);
    delete[] ctx->buffer;
    ctx->outstanding->fetch_sub(1);
    delete ctx;
}

struct CallbackView final {
    void (*on_complete)(void *, const MiraHostOperationResultV1 *) = nullptr;
    void *user_data = nullptr;
    std::uint64_t host_generation = 0;
    std::uint64_t environment_epoch = 0;
};

} // namespace

struct MiraAndroidHostV1 final {
    std::mutex mutex;
    MiraHostCallbacksV1 callbacks{};
    int lifecycle = 0; // 0 created, 1 started, 2 stopped, 3 destroyed
    std::uint64_t host_generation = 0;
    std::uint64_t host_sequence = 1;
    std::uint64_t environment_epoch = 1;
    std::uint64_t next_lease_id = 1;
    std::uint64_t next_frame_id = 1;
    std::unordered_map<std::uint64_t, PendingOp> ops;
    std::atomic<std::uint64_t> outstanding_leases{0};
    std::uint64_t unknown_completions = 0;
    std::uint64_t late_completions = 0;
    std::uint64_t cancelled_ops = 0;

    [[nodiscard]] CallbackView callback_view() {
        CallbackView view;
        view.on_complete = callbacks.on_operation_complete;
        view.user_data = callbacks.user_data;
        view.host_generation = host_generation;
        view.environment_epoch = environment_epoch;
        return view;
    }
};

namespace {

// P1 进程内单 host：完成路由与调试统计使用。start 时登记，stop/destroy 时清除。
std::mutex g_active_mutex;
MiraAndroidHostV1 *g_active_host = nullptr;

struct HostDebugStats final {
    std::uint64_t unknown_completions = 0;
    std::uint64_t late_completions = 0;
    std::uint64_t cancelled_ops = 0;
    std::uint64_t outstanding_leases = 0;
};

HostDebugStats host_debug_stats() {
    std::lock_guard lock(g_active_mutex);
    HostDebugStats stats;
    if (g_active_host != nullptr) {
        std::lock_guard host_lock(g_active_host->mutex);
        stats.unknown_completions = g_active_host->unknown_completions;
        stats.late_completions = g_active_host->late_completions;
        stats.cancelled_ops = g_active_host->cancelled_ops;
        stats.outstanding_leases = g_active_host->outstanding_leases.load();
    }
    return stats;
}

// ---- Kotlin HostBridge 静态方法调用（低频控制面） ----

bool call_request_frame(std::uint64_t correlation, std::uint64_t deadline_ns) {
    miracle::bridge::AttachedEnv attach;
    JNIEnv *env = attach.env();
    jclass bridge = miracle::bridge::host_bridge_class();
    if (env == nullptr || bridge == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, "miracle/hostabi",
                            "request_frame: jni unavailable (env=%p class=%p)",
                            static_cast<void *>(env), static_cast<void *>(bridge));
        return false;
    }
    jmethodID method = env->GetStaticMethodID(bridge, "requestFrame", "(JJ)Z");
    if (method == nullptr) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_WARN, "miracle/hostabi", "requestFrame not found");
        return false;
    }
    const jboolean accepted =
        env->CallStaticBooleanMethod(bridge, method, static_cast<jlong>(correlation),
                                     static_cast<jlong>(deadline_ns));
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_WARN, "miracle/hostabi",
                            "requestFrame threw for correlation %llu",
                            static_cast<unsigned long long>(correlation));
        return false;
    }
    return accepted == JNI_TRUE;
}

int call_permission_state() {
    miracle::bridge::AttachedEnv attach;
    JNIEnv *env = attach.env();
    jclass bridge = miracle::bridge::host_bridge_class();
    if (env == nullptr || bridge == nullptr) {
        return 2; // unknown
    }
    jmethodID method = env->GetStaticMethodID(bridge, "permissionState", "()I");
    if (method == nullptr) {
        env->ExceptionClear();
        return 2;
    }
    return static_cast<int>(env->CallStaticIntMethod(bridge, method));
}

// 输入受理：0=已受理（完成经 nativeCompleteInput 回流）1=无障碍未启用 2=受理失败。
int call_dispatch_input(std::uint64_t correlation, std::int64_t deadline_ns,
                        const std::string &events_json) {
    miracle::bridge::AttachedEnv attach;
    JNIEnv *env = attach.env();
    jclass bridge = miracle::bridge::host_bridge_class();
    if (env == nullptr || bridge == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, "miracle/hostabi",
                            "dispatch_input: jni unavailable (env=%p class=%p)",
                            static_cast<void *>(env), static_cast<void *>(bridge));
        return 2;
    }
    jmethodID method = env->GetStaticMethodID(bridge, "dispatchInput", "(JJ[B)I");
    if (method == nullptr) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_WARN, "miracle/hostabi", "dispatchInput not found");
        return 2;
    }
    const jsize length = static_cast<jsize>(events_json.size());
    jbyteArray payload = env->NewByteArray(length);
    if (payload == nullptr) {
        env->ExceptionClear();
        return 2;
    }
    env->SetByteArrayRegion(payload, 0, length,
                            reinterpret_cast<const jbyte *>(events_json.data()));
    const jint outcome =
        env->CallStaticIntMethod(bridge, method, static_cast<jlong>(correlation),
                                 static_cast<jlong>(deadline_ns), payload);
    env->DeleteLocalRef(payload);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_WARN, "miracle/hostabi",
                            "dispatchInput threw for correlation %llu",
                            static_cast<unsigned long long>(correlation));
        return 2;
    }
    return outcome;
}

// 协作取消通知（fire-and-forget；结算由 Kotlin 完成路径回流）。
void call_cancel_input(std::uint64_t correlation) {
    miracle::bridge::AttachedEnv attach;
    JNIEnv *env = attach.env();
    jclass bridge = miracle::bridge::host_bridge_class();
    if (env == nullptr || bridge == nullptr) {
        return;
    }
    jmethodID method = env->GetStaticMethodID(bridge, "cancelInput", "(J)V");
    if (method == nullptr) {
        env->ExceptionClear();
        return;
    }
    env->CallStaticVoidMethod(bridge, method, static_cast<jlong>(correlation));
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

// 输入就绪状态：0=就绪 1=无障碍未启用 2=瞬态未就绪。
int call_input_state() {
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
}

std::string call_topology_json() {
    miracle::bridge::AttachedEnv attach;
    JNIEnv *env = attach.env();
    jclass bridge = miracle::bridge::host_bridge_class();
    if (env == nullptr || bridge == nullptr) {
        return "{}";
    }
    jmethodID method = env->GetStaticMethodID(bridge, "topologyJson", "()Ljava/lang/String;");
    if (method == nullptr) {
        env->ExceptionClear();
        return "{}";
    }
    auto *json = static_cast<jstring>(env->CallStaticObjectMethod(bridge, method));
    if (json == nullptr) {
        env->ExceptionClear();
        return "{}";
    }
    const char *chars = env->GetStringUTFChars(json, nullptr);
    std::string value = chars == nullptr ? "{}" : chars;
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(json, chars);
    }
    env->DeleteLocalRef(json);
    return value;
}

bool json_number(const std::string &text, const char *key, double &out) {
    const std::string pattern = std::string("\"") + key + "\"";
    std::size_t pos = text.find(pattern);
    if (pos == std::string::npos) {
        return false;
    }
    pos = text.find(':', pos + pattern.size());
    if (pos == std::string::npos) {
        return false;
    }
    out = std::strtod(text.c_str() + pos + 1, nullptr);
    return true;
}

void deliver_status_result(const CallbackView &view, std::uint64_t correlation,
                           std::uint32_t kind, MiraHostStatus status) {
    MiraHostOperationResultV1 result{};
    result.struct_size = sizeof(result);
    result.correlation = correlation;
    result.host_generation = view.host_generation;
    result.status = status;
    result.kind = kind;
    result.environment_epoch = view.environment_epoch;
    view.on_complete(view.user_data, &result);
}

void deliver_input_result(const CallbackView &view, std::uint64_t correlation,
                          MiraHostStatus status, std::uint32_t receipt,
                          std::uint32_t side_effect) {
    MiraHostOperationResultV1 result{};
    result.struct_size = sizeof(result);
    result.correlation = correlation;
    result.host_generation = view.host_generation;
    result.status = status;
    result.kind = MIRA_HOST_OP_DISPATCH_INPUT;
    result.input_receipt = receipt;
    result.side_effect_may_have_occurred = side_effect;
    result.environment_epoch = view.environment_epoch;
    view.on_complete(view.user_data, &result);
}

// JSON 字符串字面量转义（控制字符与引号；非 ASCII 以原始 UTF-8 字节透传，
// 整体经 jbyteArray 送往 Kotlin 按 UTF-8 解码，不经 NewStringUTF）。
void append_json_escaped(std::string &out, const char *text, std::uint32_t length) {
    static const char *kHex = "0123456789abcdef";
    for (std::uint32_t i = 0; i < length; ++i) {
        const unsigned char c = static_cast<unsigned char>(text[i]);
        switch (c) {
        case '"':
            out += "\\\"";
            break;
        case '\\':
            out += "\\\\";
            break;
        case '\b':
            out += "\\b";
            break;
        case '\f':
            out += "\\f";
            break;
        case '\n':
            out += "\\n";
            break;
        case '\r':
            out += "\\r";
            break;
        case '\t':
            out += "\\t";
            break;
        default:
            if (c < 0x20) {
                out += "\\u00";
                out += kHex[(c >> 4) & 0xF];
                out += kHex[c & 0xF];
            } else {
                out += static_cast<char>(c);
            }
        }
    }
}

// 输入请求 → Kotlin 事件 JSON：[{"k":1,"x":..,"y":..,"x2":..,"y2":..,"d":..,"t":..}]。
// 调用前已通过 ABI 校验；缓冲尺寸有界（64 事件 × 文本 ≤4096B）。
std::string build_input_events_json(const MiraHostInputRequestV1 *request) {
    std::string json;
    json.reserve(64 + request->event_count * 96);
    json += "[";
    char number[64];
    for (std::uint32_t i = 0; i < request->event_count; ++i) {
        const MiraHostInputEventV1 &event = request->events[i];
        if (i != 0) {
            json += ",";
        }
        json += "{\"k\":";
        std::snprintf(number, sizeof(number), "%u", event.kind);
        json += number;
        std::snprintf(number, sizeof(number), ",\"x\":%.17g", event.x);
        json += number;
        std::snprintf(number, sizeof(number), ",\"y\":%.17g", event.y);
        json += number;
        std::snprintf(number, sizeof(number), ",\"x2\":%.17g", event.x2);
        json += number;
        std::snprintf(number, sizeof(number), ",\"y2\":%.17g", event.y2);
        json += number;
        std::snprintf(number, sizeof(number), ",\"d\":%u", event.duration_ms);
        json += number;
        if (event.text != nullptr && event.text_length > 0) {
            json += ",\"t\":\"";
            append_json_escaped(json, event.text, event.text_length);
            json += "\"";
        }
        json += "}";
    }
    json += "]";
    return json;
}

// 竞争结算：从注册表取走 correlation 的所有权。true = 本调用负责终态回调。
bool claim_pending(MiraAndroidHostV1 *host, std::uint64_t correlation, CallbackView &view) {
    std::lock_guard lock(host->mutex);
    if (host->ops.erase(correlation) == 0) {
        return false;
    }
    view = host->callback_view();
    return true;
}

} // namespace

extern "C" {

// ---- 生命周期 ----

MiraHostStatus mira_android_host_create_v1(const MiraAndroidHostConfigV1 *config,
                                           const MiraHostCallbacksV1 *callbacks,
                                           MiraAndroidHostV1 **out_host) {
    if (config == nullptr || callbacks == nullptr || out_host == nullptr) {
        return MIRA_HOST_ERR_INVALID_ARGUMENT;
    }
    if (config->struct_size < sizeof(MiraAndroidHostConfigV1) ||
        callbacks->struct_size < sizeof(MiraHostCallbacksV1)) {
        return MIRA_HOST_ERR_INVALID_ARGUMENT;
    }
    if (config->abi_version != MIRA_ANDROID_ABI_VERSION) {
        return MIRA_HOST_ERR_UNSUPPORTED_VERSION;
    }
    if (callbacks->on_operation_complete == nullptr) {
        return MIRA_HOST_ERR_INVALID_ARGUMENT;
    }
    auto *host = new (std::nothrow) MiraAndroidHostV1();
    if (host == nullptr) {
        return MIRA_HOST_ERR_PLATFORM_ERROR;
    }
    host->callbacks = *callbacks;
    host->host_generation = static_cast<std::uint64_t>(
        std::chrono::steady_clock::now().time_since_epoch().count());
    *out_host = host;
    return MIRA_HOST_OK;
}

MiraHostStatus mira_android_host_start_v1(MiraAndroidHostV1 *host) {
    if (host == nullptr) {
        return MIRA_HOST_ERR_INVALID_ARGUMENT;
    }
    {
        std::lock_guard lock(host->mutex);
        if (host->lifecycle != 0) {
            return MIRA_HOST_ERR_INVALID_STATE;
        }
        host->lifecycle = 1;
    }
    {
        std::lock_guard active_lock(g_active_mutex);
        if (g_active_host != nullptr && g_active_host != host) {
            std::lock_guard lock(host->mutex);
            host->lifecycle = 0;
            return MIRA_HOST_ERR_INVALID_STATE; // P1：进程内单 host
        }
        g_active_host = host;
    }
    return MIRA_HOST_OK;
}

MiraHostStatus mira_android_host_stop_v1(MiraAndroidHostV1 *host) {
    if (host == nullptr) {
        return MIRA_HOST_ERR_INVALID_ARGUMENT;
    }
    CallbackView view;
    std::vector<std::pair<std::uint64_t, std::uint32_t>> cancelled;
    std::vector<std::uint64_t> input_ops;
    {
        std::lock_guard lock(host->mutex);
        if (host->lifecycle == 3) {
            return MIRA_HOST_ERR_INVALID_STATE;
        }
        host->lifecycle = 2;
        for (const auto &entry : host->ops) {
            if (entry.second.kind == MIRA_HOST_OP_DISPATCH_INPUT) {
                input_ops.emplace_back(entry.first);
            } else {
                cancelled.emplace_back(entry.first, entry.second.kind);
            }
        }
        host->cancelled_ops += cancelled.size() + input_ops.size();
        if (!cancelled.empty()) {
            view = host->callback_view();
        }
    }
    for (const auto &entry : cancelled) {
        deliver_status_result(view, entry.first, entry.second, MIRA_HOST_ERR_CANCELLED);
    }
    // 输入操作协作取消：通知 Kotlin（手势取消是异步的），有界等待完成回流。
    for (const std::uint64_t correlation : input_ops) {
        call_cancel_input(correlation);
    }
    for (int attempt = 0; attempt < 100; ++attempt) {
        {
            std::lock_guard lock(host->mutex);
            if (host->ops.empty()) {
                break;
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    {
        // 排空后残留（Kotlin 未在界内回流）：强制结算 CANCELLED；迟到的 Kotlin
        // 完成将计入 unknown_completions（有界、可见）。
        std::lock_guard lock(host->mutex);
        if (!host->ops.empty()) {
            view = host->callback_view();
            for (const auto &entry : host->ops) {
                cancelled.emplace_back(entry.first, entry.second.kind);
            }
            host->ops.clear();
        }
    }
    for (const auto &entry : cancelled) {
        deliver_status_result(view, entry.first, entry.second, MIRA_HOST_ERR_CANCELLED);
    }
    // 输入完成回流依赖 g_active_host 定位宿主：解除登记延后排空之后。
    {
        std::lock_guard active_lock(g_active_mutex);
        if (g_active_host == host) {
            g_active_host = nullptr;
        }
    }
    // 有界等待 lease 清零（最长 500ms），之后如实报告不确定。
    for (int attempt = 0; attempt < 50 && host->outstanding_leases.load() > 0; ++attempt) {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    return host->outstanding_leases.load() == 0 ? MIRA_HOST_OK : MIRA_HOST_ERR_EXECUTION_UNCERTAIN;
}

MiraHostStatus mira_android_host_destroy_v1(MiraAndroidHostV1 *host) {
    if (host == nullptr) {
        return MIRA_HOST_ERR_INVALID_ARGUMENT;
    }
    {
        std::lock_guard lock(host->mutex);
        if (host->outstanding_leases.load() > 0 || !host->ops.empty()) {
            return MIRA_HOST_ERR_INVALID_STATE;
        }
        host->lifecycle = 3;
    }
    delete host;
    return MIRA_HOST_OK;
}

// ---- 查询 ----

MiraHostStatus mira_android_host_get_capabilities_v1(MiraAndroidHostV1 *host,
                                                     MiraHostCapabilitiesV1 *out) {
    if (host == nullptr || out == nullptr) {
        return MIRA_HOST_ERR_INVALID_ARGUMENT;
    }
    // out 参数：v1 为首个版本，调用方按类型持有完整结构体；零值 struct_size
    // 视为"填充 v1"（mira 参考消费方零初始化后传入）。仅显式偏小的非零值拒绝。
    if (out->struct_size != 0 && out->struct_size < sizeof(MiraHostCapabilitiesV1)) {
        return MIRA_HOST_ERR_INVALID_ARGUMENT;
    }
    const int permission = call_permission_state();
    const int input_state = call_input_state(); // 0=就绪 1=无障碍未启用 2=瞬态
    const std::string topology = call_topology_json();
    double cap_w = 0;
    double cap_h = 0;
    (void)json_number(topology, "cap_w", cap_w);
    (void)json_number(topology, "cap_h", cap_h);

    std::lock_guard lock(host->mutex);
    MiraHostCapabilitiesV1 caps{};
    caps.struct_size = sizeof(caps);
    caps.abi_version = MIRA_ANDROID_ABI_VERSION;
    caps.screenshot_pixel_formats_mask = 1U << MIRA_HOST_PIXEL_RGBA8888;
    caps.screenshot_backends = 1; // virtual display
    caps.max_frame_width = static_cast<std::uint32_t>(cap_w);
    caps.max_frame_height = static_cast<std::uint32_t>(cap_h);
    caps.accessibility_completeness = 0;
    caps.supported_node_actions_mask = 0;
    // 输入能力位（bits 1..7 = tap/long_press/swipe/type/back/home/release_all）：
    // 仅在无障碍服务连接且派发器就绪时声明，断开即 0（能力诚实，决策 2）。
    caps.input_capabilities_mask = input_state == 0 ? 0x7FU : 0U;
    caps.max_gesture_duration_ms = input_state == 0 ? 60'000U : 0U; // 平台 stroke 上限
    caps.max_pointers = input_state == 0 ? 1U : 0U;                  // v1 单指
    caps.callback_thread_model = 0; // 宿主内部线程
    caps.lifecycle_state = host->lifecycle <= 2 ? static_cast<std::uint32_t>(host->lifecycle) : 2;
    caps.permission_state = static_cast<std::uint32_t>(permission);
    caps.secure_surface_policy = 2; // unknown
    caps.topology_version = host->environment_epoch;
    caps.environment_epoch = host->environment_epoch;
    caps.host_sequence = host->host_sequence;
    caps.host_generation = host->host_generation;
    *out = caps;
    return MIRA_HOST_OK;
}

MiraHostStatus mira_android_host_get_topology_v1(MiraAndroidHostV1 *host,
                                                 MiraHostTopologyV1 *out) {
    if (host == nullptr || out == nullptr) {
        return MIRA_HOST_ERR_INVALID_ARGUMENT;
    }
    // 同 get_capabilities：零值 struct_size 表示"填充 v1"。
    if (out->struct_size != 0 && out->struct_size < sizeof(MiraHostTopologyV1)) {
        return MIRA_HOST_ERR_INVALID_ARGUMENT;
    }
    const std::string topology = call_topology_json();
    double w = 0;
    double h = 0;
    double rot = 0;
    double density = 1.0;
    double inset_l = 0;
    double inset_t = 0;
    double inset_r = 0;
    double inset_b = 0;
    double active = 0;
    const bool complete = json_number(topology, "w", w) && json_number(topology, "h", h);
    if (!complete) {
        return MIRA_HOST_ERR_UNAVAILABLE;
    }
    (void)json_number(topology, "rot", rot);
    (void)json_number(topology, "den", density);
    (void)json_number(topology, "il", inset_l);
    (void)json_number(topology, "it", inset_t);
    (void)json_number(topology, "ir", inset_r);
    (void)json_number(topology, "ib", inset_b);
    (void)json_number(topology, "active", active);

    std::lock_guard lock(host->mutex);
    MiraHostTopologyV1 value{};
    value.struct_size = sizeof(value);
    value.environment_epoch = host->environment_epoch;
    value.topology_version = host->environment_epoch;
    value.display_count = 1;
    value.displays[0].display_id = 0;
    value.displays[0].native_width = static_cast<std::uint32_t>(w);
    value.displays[0].native_height = static_cast<std::uint32_t>(h);
    value.displays[0].rotation = static_cast<std::uint32_t>(rot);
    value.displays[0].pixels_per_logical = density > 0 ? density : 1.0;
    value.displays[0].inset_left = inset_l;
    value.displays[0].inset_top = inset_t;
    value.displays[0].inset_right = inset_r;
    value.displays[0].inset_bottom = inset_b;
    value.displays[0].active = active != 0 ? 1U : 0U;
    *out = value;
    return MIRA_HOST_OK;
}

// ---- 异步操作 ----

MiraHostStatus mira_android_host_capture_frame_v1(MiraAndroidHostV1 *host,
                                                  const MiraHostFrameRequestV1 *request,
                                                  std::uint64_t *out_operation) {
    // out_operation 可为 null：mira 参考消费方（HostDispatcherBridge）以 correlation
    // 匹配结果，不需要宿主侧操作句柄。
    if (host == nullptr || request == nullptr) {
        return MIRA_HOST_ERR_INVALID_ARGUMENT;
    }
    if (request->struct_size < sizeof(MiraHostFrameRequestV1)) {
        return MIRA_HOST_ERR_INVALID_ARGUMENT;
    }
    {
        std::lock_guard lock(host->mutex);
        if (host->lifecycle != 1) {
            return MIRA_HOST_ERR_INVALID_STATE;
        }
        host->ops[request->correlation] = PendingOp{MIRA_HOST_OP_CAPTURE_FRAME};
    }
    if (out_operation != nullptr) {
        *out_operation = request->correlation;
    }
    // mira 的 deadline_ns 是 steady_clock 绝对时刻；转换为剩余时长（ns）交给
    // Kotlin 协程做相对超时，并收紧上限为 6s（mira 侧观察 deadline 通常 10s，
    // 留出余量让失败以明确错误状态而非宿主超时结算）。
    std::uint64_t remaining_ns = 0;
    if (request->deadline_ns > 0) {
        const std::uint64_t now_ns = static_cast<std::uint64_t>(
            std::chrono::steady_clock::now().time_since_epoch().count());
        remaining_ns = request->deadline_ns > now_ns ? request->deadline_ns - now_ns : 0;
    }
    if (!call_request_frame(request->correlation, remaining_ns)) {
        // 快速失败路径：竞争结算，赢家负责恰好一次终态回调。
        CallbackView view;
        if (claim_pending(host, request->correlation, view)) {
            const MiraHostStatus status = call_permission_state() == 0
                                              ? MIRA_HOST_ERR_PERMISSION_DENIED
                                              : MIRA_HOST_ERR_UNAVAILABLE;
            deliver_status_result(view, request->correlation, MIRA_HOST_OP_CAPTURE_FRAME, status);
        }
    }
    return MIRA_HOST_OK;
}

MiraHostStatus mira_android_host_get_ui_tree_v1(MiraAndroidHostV1 *host,
                                                const MiraHostTreeRequestV1 *request,
                                                std::uint64_t *out_operation) {
    // P1 fail-closed：无 UI 树能力（上游缺口 MIR-20260905-001）。快速失败，不回调。
    (void)host;
    (void)request;
    (void)out_operation;
    return MIRA_HOST_ERR_UNAVAILABLE;
}

MiraHostStatus mira_android_host_dispatch_input_v1(MiraAndroidHostV1 *host,
                                                   const MiraHostInputRequestV1 *request,
                                                   std::uint64_t *out_operation) {
    if (host == nullptr || request == nullptr) {
        return MIRA_HOST_ERR_INVALID_ARGUMENT;
    }
    if (request->struct_size < sizeof(MiraHostInputRequestV1)) {
        return MIRA_HOST_ERR_INVALID_ARGUMENT;
    }
    if (request->display_id != 0) {
        return MIRA_HOST_ERR_INVALID_ARGUMENT; // 单显示宿主
    }
    if (request->event_count == 0 || request->event_count > MIRA_MAX_INPUT_EVENTS) {
        return MIRA_HOST_ERR_INVALID_ARGUMENT;
    }
    // 逐事件校验（快速失败：同步返回，不回调，与契约一致）。
    for (std::uint32_t i = 0; i < request->event_count; ++i) {
        const MiraHostInputEventV1 &event = request->events[i];
        if (event.duration_ms > 60'000) {
            return MIRA_HOST_ERR_INVALID_ARGUMENT;
        }
        const bool pair_ok = std::isfinite(event.x) && std::isfinite(event.y) &&
                             event.x >= 0.0 && event.x <= 1.0 && event.y >= 0.0 &&
                             event.y <= 1.0;
        const bool quad_ok =
            pair_ok && std::isfinite(event.x2) && std::isfinite(event.y2) &&
            event.x2 >= 0.0 && event.x2 <= 1.0 && event.y2 >= 0.0 && event.y2 <= 1.0;
        switch (event.kind) {
        case MIRA_HOST_INPUT_TAP:
        case MIRA_HOST_INPUT_LONG_PRESS:
            if (!pair_ok) {
                return MIRA_HOST_ERR_INVALID_ARGUMENT;
            }
            break;
        case MIRA_HOST_INPUT_SWIPE:
            if (!quad_ok) {
                return MIRA_HOST_ERR_INVALID_ARGUMENT;
            }
            break;
        case MIRA_HOST_INPUT_TYPE:
            if (event.text == nullptr || event.text_length == 0 ||
                event.text_length > 4096) {
                return MIRA_HOST_ERR_INVALID_ARGUMENT;
            }
            break;
        case MIRA_HOST_INPUT_BACK:
        case MIRA_HOST_INPUT_HOME:
        case MIRA_HOST_INPUT_RELEASE_ALL:
            break;
        default:
            return MIRA_HOST_ERR_INVALID_ARGUMENT;
        }
    }
    {
        std::lock_guard lock(host->mutex);
        if (host->lifecycle != 1) {
            return MIRA_HOST_ERR_INVALID_STATE;
        }
        host->ops[request->correlation] = PendingOp{MIRA_HOST_OP_DISPATCH_INPUT};
    }
    if (out_operation != nullptr) {
        *out_operation = request->correlation;
    }
    // deadline 换算（内部约定）：0=无超时；负值=已过期；正值=剩余时长（ns）。
    std::int64_t deadline = 0;
    if (request->deadline_ns > 0) {
        const std::uint64_t now_ns = static_cast<std::uint64_t>(
            std::chrono::steady_clock::now().time_since_epoch().count());
        deadline = request->deadline_ns > now_ns
                       ? static_cast<std::int64_t>(request->deadline_ns - now_ns)
                       : -1;
    }
    const std::string events_json = build_input_events_json(request);
    const int accepted = call_dispatch_input(request->correlation, deadline, events_json);
    if (accepted != 0) {
        // 快速失败路径：竞争结算，赢家负责恰好一次终态回调。
        CallbackView view;
        if (claim_pending(host, request->correlation, view)) {
            const MiraHostStatus status = accepted == 1
                                              ? MIRA_HOST_ERR_PERMISSION_DENIED
                                              : MIRA_HOST_ERR_UNAVAILABLE;
            deliver_input_result(view, request->correlation, status,
                                 MIRA_HOST_INPUT_RECEIPT_UNKNOWN, 0);
        }
    }
    return MIRA_HOST_OK;
}

MiraHostStatus mira_android_host_cancel_operation_v1(MiraAndroidHostV1 *host,
                                                     std::uint64_t operation) {
    if (host == nullptr || operation == 0) {
        return MIRA_HOST_ERR_INVALID_ARGUMENT;
    }
    std::uint32_t kind = 0;
    bool sync_settle = false;
    bool async_input = false;
    CallbackView view;
    {
        std::lock_guard lock(host->mutex);
        const auto found = host->ops.find(operation);
        if (found != host->ops.end()) {
            kind = found->second.kind;
            host->cancelled_ops += 1;
            if (kind == MIRA_HOST_OP_DISPATCH_INPUT) {
                // 输入取消是协作式：操作留在注册表，由 Kotlin 完成路径结算
                // （未提交平台→CANCELLED；已提交被中断→EXECUTION_UNCERTAIN+
                // side_effect=1；竞态下先完成→原结果）。不在此处投递终态。
                async_input = true;
            } else {
                host->ops.erase(found);
                sync_settle = true;
                view = host->callback_view();
            }
        }
    }
    if (async_input) {
        call_cancel_input(operation); // 解除锁后通知，避免回调进锁
    }
    if (sync_settle) {
        deliver_status_result(view, operation, kind, MIRA_HOST_ERR_CANCELLED);
    }
    return MIRA_HOST_OK; // 已结算或未知的取消是成功 no-op
}

// ---- Kotlin 完成入口与 epoch 通知（HostBridge 经 JNI 调用） ----

void miracle_host_debug_stats_json(std::string &out) {
    const HostDebugStats stats = host_debug_stats();
    char buffer[256];
    std::snprintf(buffer, sizeof(buffer),
                  "{\"unknown_completions\":%llu,\"late_completions\":%llu,"
                  "\"cancelled_ops\":%llu,\"outstanding_leases\":%llu}",
                  static_cast<unsigned long long>(stats.unknown_completions),
                  static_cast<unsigned long long>(stats.late_completions),
                  static_cast<unsigned long long>(stats.cancelled_ops),
                  static_cast<unsigned long long>(stats.outstanding_leases));
    out = buffer;
}

void miracle_host_notify_epoch_changed() {
    MiraAndroidHostV1 *host = nullptr;
    {
        std::lock_guard active_lock(g_active_mutex);
        host = g_active_host;
    }
    if (host == nullptr) {
        return;
    }
    {
        std::lock_guard lock(host->mutex);
        if (host->lifecycle != 1) {
            return;
        }
        host->environment_epoch += 1;
        host->host_sequence += 1;
        __android_log_print(ANDROID_LOG_INFO, "miracle/hostabi",
                            "epoch changed -> %llu (host_sequence=%llu)",
                            static_cast<unsigned long long>(host->environment_epoch),
                            static_cast<unsigned long long>(host->host_sequence));
        if (host->callbacks.on_capabilities_changed == nullptr) {
            return;
        }
    }
    MiraHostCapabilitiesV1 refreshed{};
    refreshed.struct_size = sizeof(refreshed);
    if (mira_android_host_get_capabilities_v1(host, &refreshed) == MIRA_HOST_OK) {
        host->callbacks.on_capabilities_changed(host->callbacks.user_data, &refreshed);
    }
}

} // extern "C"

// 帧完成：Kotlin 携带拷贝后的 RGBA 像素与元数据。由 HostBridge.nativeCompleteFrame
// （见 runtime_glue.cpp）在 JVM 线程调用。声明见 host_abi_impl.hpp。
extern "C" MiraHostStatus miracle_host_complete_frame(std::uint64_t correlation, bool ok,
                                                       std::uint32_t width, std::uint32_t height,
                                                       std::uint32_t rotation,
                                                       const std::uint8_t *pixels,
                                                       std::uint64_t size, std::uint64_t begin_ns,
                                                       std::uint64_t end_ns,
                                                       MiraHostStatus error_status) {
    MiraAndroidHostV1 *host = nullptr;
    {
        std::lock_guard active_lock(g_active_mutex);
        host = g_active_host;
    }
    if (host == nullptr) {
        return MIRA_HOST_ERR_INVALID_STATE;
    }
    {
        std::lock_guard lock(host->mutex);
        if (host->lifecycle != 1) {
            host->late_completions += 1;
            return MIRA_HOST_ERR_INVALID_STATE;
        }
    }
    CallbackView view;
    if (!claim_pending(host, correlation, view)) {
        std::lock_guard lock(host->mutex);
        host->unknown_completions += 1;
        return MIRA_HOST_ERR_INVALID_STATE;
    }

    if (!ok) {
        deliver_status_result(view, correlation, MIRA_HOST_OP_CAPTURE_FRAME, error_status);
        return MIRA_HOST_OK;
    }

    const std::uint64_t byte_size = size;
    if (pixels == nullptr || byte_size == 0 || width == 0 || height == 0) {
        deliver_status_result(view, correlation, MIRA_HOST_OP_CAPTURE_FRAME,
                              MIRA_HOST_ERR_INVALID_BUFFER);
        return MIRA_HOST_ERR_INVALID_BUFFER;
    }
    auto *buffer = new (std::nothrow) std::uint8_t[byte_size];
    auto *lease_context = new (std::nothrow) LeaseContext{};
    if (buffer == nullptr || lease_context == nullptr) {
        delete[] buffer;
        delete lease_context;
        deliver_status_result(view, correlation, MIRA_HOST_OP_CAPTURE_FRAME,
                              MIRA_HOST_ERR_CAPACITY);
        return MIRA_HOST_ERR_CAPACITY;
    }
    std::memcpy(buffer, pixels, byte_size);

    std::uint64_t lease_id = 0;
    std::uint64_t frame_id = 0;
    std::uint64_t epoch = view.environment_epoch;
    {
        std::lock_guard lock(host->mutex);
        lease_id = host->next_lease_id++;
        frame_id = host->next_frame_id++;
    }
    lease_context->outstanding = &host->outstanding_leases;
    lease_context->buffer = buffer;
    host->outstanding_leases.fetch_add(1);

    MiraHostBufferLeaseV1 lease{};
    lease.struct_size = sizeof(lease);
    lease.lease_id = lease_id;
    lease.data = buffer;
    lease.size = byte_size;
    lease.plane_count = 1;
    lease.planes[0].offset = 0;
    lease.planes[0].row_stride = width * 4;
    lease.planes[0].pixel_stride = 4;
    lease.planes[0].width = width;
    lease.planes[0].height = height;
    lease.release = &release_lease;
    lease.user_data = lease_context;

    MiraHostOperationResultV1 result{};
    result.struct_size = sizeof(result);
    result.correlation = correlation;
    result.host_generation = view.host_generation;
    result.status = MIRA_HOST_OK;
    result.kind = MIRA_HOST_OP_CAPTURE_FRAME;
    result.lease = &lease;
    result.frame_id = frame_id;
    result.display_id = 0;
    result.width = width;
    result.height = height;
    result.pixel_format = MIRA_HOST_PIXEL_RGBA8888;
    result.rotation = rotation;
    result.capture_begin_ns = begin_ns;
    result.capture_end_ns = end_ns;
    result.environment_epoch = epoch;
    view.on_complete(view.user_data, &result);
    return MIRA_HOST_OK;
}

// 输入完成：Kotlin 携带 (status, receipt, side_effect)。由 HostBridge.nativeCompleteInput
// （见 runtime_glue.cpp）在主线程调用。生命周期==2（stop 排空中）仍接受——输入取消
// 的结算按设计经此路径回流；已结算/未知的完成计数并丢弃。
extern "C" MiraHostStatus miracle_host_complete_input(std::uint64_t correlation,
                                                      MiraHostStatus status,
                                                      std::uint32_t receipt,
                                                      std::uint32_t side_effect) {
    MiraAndroidHostV1 *host = nullptr;
    {
        std::lock_guard active_lock(g_active_mutex);
        host = g_active_host;
    }
    if (host == nullptr) {
        return MIRA_HOST_ERR_INVALID_STATE;
    }
    {
        std::lock_guard lock(host->mutex);
        if (host->lifecycle != 1 && host->lifecycle != 2) {
            host->late_completions += 1;
            return MIRA_HOST_ERR_INVALID_STATE;
        }
    }
    CallbackView view;
    if (!claim_pending(host, correlation, view)) {
        std::lock_guard lock(host->mutex);
        host->unknown_completions += 1;
        return MIRA_HOST_ERR_INVALID_STATE;
    }
    deliver_input_result(view, correlation, status, receipt, side_effect);
    return MIRA_HOST_OK;
}
