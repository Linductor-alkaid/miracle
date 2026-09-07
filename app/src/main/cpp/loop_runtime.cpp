// Miracle P3 闭环运行时：mira AgentLoop 组装 + 宿主传输 + R3 确认协议。
//
// 职责（P3 计划"关键实现决策"1/2/3/4/5/11）：
//  - LoopRuntime：唯一 Executor（4 线程/队列 128）+ AndroidHostAdapter + ModelGateway +
//    OpenAiCompatibleProvider + admission + MemoryEventStore + ModelDoneVerifier。
//  - 传输：公共 IHttpTransport 接口的宿主实现（响应体经 on_chunk 回调流出）。
//      KotlinHttpTransport——HTTPS 执行在 Kotlin（HttpURLConnection，系统信任库），
//      C++ 侧封送 + 有界协作等待（50ms 轮询取消/完成）；官方 transport 头已随
//      mira cbed6ad 导出（MIR-006 关闭），切换官方 socket/mbedtls 栈为后续独立变更；
//      ScriptedTransport——干跑探针，按脚本返回合法 decision wire JSON。
//  - 帧工件（mira DEC-012/013，MIR-004/007 关闭）：HostFrameStore 注入
//    AndroidHostAdapter，commit 时把宿主随帧登记的 PNG 载荷重新发布为 image/png
//    工件（descriptor payload 元数据随 store 记录流动，方言层据此生成
//    data:image/png wire）；无登记载荷时原始帧按 image/x-host-frame 如实发布。
//    StoreArtifactSource 经同一 store 供 gateway/provider 取 wire 字节。
//  - AgentLoop::run 经 submit_cancellable 提交（StopToken → OperationContext 协作取消）；
//    takeover：admission 失效 → request_task_cancel → adapter interrupt（RELEASE_ALL）→
//    确认失效。
//  - R3 确认（DEC-004）：mira ConfirmationAuthority 为协议权威；challenge 绑定
//    digest/nonce/task_epoch/environment_epoch，60s 到期，consume 即失效（单次有效）。
//  - 粗相位信号：capture/input 受理（host_abi_impl）与 transport 执行（本文件）上投
//    Kotlin；终态经 AgentLoopResult JSON。
//  - 本文件不创建任何线程；阻塞等待仅在 mira Executor worker 上（gateway.infer 同步链）。
//  - 锁序约定：g_loop_mutex → g_confirmations.mutex（begin/resolve 先取 epoch 再入
//    确认锁，任何路径不得反向嵌套）；g_transport_registry_mutex 为独立叶子锁——
//    锁内只提升 weak_ptr，不调用传输实例方法，也不与上述两锁嵌套。
#include "host_abi_impl.hpp"
#include "host_jni.hpp"
#include "loop_runtime.hpp"

#include "frame_encoding.hpp"

#include <mira/adapters/android/android_host_adapter.hpp>
#include <mira/agent_loop.hpp>
#include <mira/artifact_store.hpp>
#include <mira/core_contracts.hpp>
#include <mira/environment.hpp>
#include <mira/event_store.hpp>
#include <mira/json.hpp>
#include <mira/model_contracts.hpp>
#include <mira/model_gateway.hpp>
#include <mira/model_profile.hpp>
#include <mira/model_provider.hpp>
#include <mira/model_schema.hpp>
#include <mira/model_transport.hpp>
#include <mira/security.hpp>
#include <mira/version.hpp>

#include <executor/executor.hpp>
#include <executor/stop_token.hpp>

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <future>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

constexpr char kLogTag[] = "miracle/loop";

using clock = std::chrono::steady_clock;

std::string json_escape(const std::string &value) {
    std::string out;
    out.reserve(value.size() + 8);
    for (char c : value) {
        switch (c) {
        case '"':
            out += "\\\"";
            break;
        case '\\':
            out += "\\\\";
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
            if (static_cast<unsigned char>(c) < 0x20) {
                char buffer[8];
                std::snprintf(buffer, sizeof(buffer), "\\u%04x", c);
                out += buffer;
            } else {
                out += c;
            }
        }
    }
    return out;
}

// ---- Kotlin 上投（低频控制面；只做封送，异常不穿越 JNI） ----

// 包一层 {"kind":...,"payload":...} 后经 HostBridge.onLoopEvent 上投。
void emit_loop_event(const char *kind, const std::string &json_payload) {
    std::string wrapped = "{\"kind\":\"";
    wrapped += kind;
    wrapped += "\",\"payload\":";
    wrapped += json_payload.empty() ? "{}" : json_payload;
    wrapped += "}";
    miracle::bridge::AttachedEnv attach;
    JNIEnv *env = attach.env();
    jclass bridge = miracle::bridge::host_bridge_class();
    if (env == nullptr || bridge == nullptr) {
        return;
    }
    jmethodID method = env->GetStaticMethodID(bridge, "onLoopEvent", "(Ljava/lang/String;)V");
    if (method == nullptr) {
        env->ExceptionClear();
        return;
    }
    jstring text = env->NewStringUTF(wrapped.c_str());
    if (text != nullptr) {
        env->CallStaticVoidMethod(bridge, method, text);
        env->DeleteLocalRef(text);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

// ---- 密钥解析（内存凭据；不入日志/事件） ----

class HostSecretResolver final : public mira::ISecretResolver {
  public:
    explicit HostSecretResolver(std::string key) : key_(std::move(key)) {}
    mira::Result<std::string> resolve(const mira::SecretRef &reference) override {
        if (reference.name != "miracle.model.key" || key_.empty()) {
            mira::Error error;
            error.code = mira::ErrorCode::PermissionDenied;
            error.domain = "miracle.bridge";
            error.safe_message = "model credential is not configured";
            return error;
        }
        return key_;
    }

  private:
    std::string key_;
};

// fail-closed 工件源：连通性自检（文本-only，无截图部件）使用；请求触达即如实报错。
class FailClosedArtifactSource final : public mira::IArtifactSource {
  public:
    mira::Result<std::vector<std::byte>> fetch(const mira::ArtifactRef &) override {
        mira::Error error;
        error.code = mira::ErrorCode::UnsupportedCapability;
        error.domain = "miracle.bridge";
        error.safe_message = "artifact fetch is unavailable in this stack";
        return error;
    }
};

// 注入 AndroidHostAdapter 的帧载荷 store（mira DEC-012 注入点 + DEC-013 宿主编码）。
// 委托内部 MemoryArtifactStore（ArtifactWriter 构造对官方 store 友元封闭，本类型经
// 公共 begin/commit/open 完成转码，不触碰内部表示）：
//  1. adapter 以 spec{image/x-host-frame} 写入原始帧字节并 commit；
//  2. commit 按原始字节 sha256 查 [frame_encoding_registry]；命中则把宿主登记的 PNG
//     载荷以 spec{image/png} 重新发布并回收原始工件，返回 PNG 描述符（payload 媒体
//     类型/字节数/摘要由本记录决定，ScreenFrameDescriptor 与 wire 随之流动）；
//  3. 未命中（宿主未编码，如无 Kotlin 上投的路径）原样返回原始描述符——诚实标注，
//     方言层对非 image/* 才拒绝，image/x-host-frame 过门但真实端点可能仍拒（上游
//     既定边界，见 DEC-013）。
class HostFrameStore final : public mira::IArtifactStore {
  public:
    explicit HostFrameStore(std::size_t capacity_bytes) : inner_(capacity_bytes) {}

    mira::Result<mira::ArtifactWriter> begin(const mira::ArtifactWriteSpec &spec) override {
        return inner_.begin(spec);
    }

    mira::Result<mira::ArtifactDescriptor> commit(mira::ArtifactWriter &writer) override {
        auto raw = inner_.commit(writer);
        if (!raw.has_value() ||
            raw.value().media_type != "image/x-host-frame") {
            return raw;
        }
        const auto encoded =
            miracle::bridge::frame_encoding_registry().peek(raw.value().digest);
        if (!encoded.has_value() || encoded.value().empty()) {
            return raw;
        }
        mira::ArtifactWriteSpec png_spec;
        png_spec.media_type = "image/png";
        png_spec.max_bytes = encoded.value().size();
        auto publisher = inner_.begin(png_spec);
        if (!publisher.has_value()) {
            return raw; // 容量不足等：回退原始工件（仍可消费）
        }
        if (const auto written = publisher.value().write(encoded.value());
            !written.has_value()) {
            return raw;
        }
        auto published = inner_.commit(publisher.value());
        if (!published.has_value()) {
            return raw;
        }
        // 转码成功后回收原始工件容量（尽力而为，失败不回滚已发布结果）。
        (void)inner_.erase(mira::ArtifactErasureRequest{raw.value().id, "transcoded to png"});
        return published;
    }

    mira::Result<mira::ArtifactReader> open(const mira::ArtifactDescriptor &descriptor) const override {
        return inner_.open(descriptor);
    }

    mira::Result<mira::ErasureReceipt> erase(const mira::ArtifactErasureRequest &request) override {
        return inner_.erase(request);
    }

  private:
    mira::MemoryArtifactStore inner_;
};

// wire 侧工件源：provider/方言层经 ArtifactRef（id+digest+byte_size+media_type，
// 即帧描述符发布的载荷元数据）回读字节，仅以公共 store API 取有界载荷。
class StoreArtifactSource final : public mira::IArtifactSource {
  public:
    explicit StoreArtifactSource(std::shared_ptr<mira::IArtifactStore> store)
        : store_(std::move(store)) {}

    mira::Result<std::vector<std::byte>> fetch(const mira::ArtifactRef &reference) override {
        if (!store_) {
            mira::Error error;
            error.code = mira::ErrorCode::Unavailable;
            error.domain = "miracle.bridge";
            error.safe_message = "frame artifact store is not available";
            return error;
        }
        mira::ArtifactDescriptor descriptor;
        descriptor.id = reference.id;
        descriptor.digest = reference.digest;
        descriptor.byte_size = reference.byte_size;
        descriptor.media_type = reference.media_type;
        auto reader = store_->open(descriptor);
        if (!reader.has_value()) {
            mira::Error error = reader.error();
            error.domain = "miracle.bridge";
            return error;
        }
        return reader.value().bytes();
    }

  private:
    std::shared_ptr<mira::IArtifactStore> store_;
};

// ---- Kotlin 传输（公共 IHttpTransport 的宿主实现） ----

struct HttpExchange final {
    std::mutex mutex;
    std::condition_variable cv;
    bool done = false;
    bool cancel_notified = false;
    std::int32_t status = 0; // -1 取消 -2 超时 -3 网络 -4 配置/协议；>=0 HTTP 状态码
    std::string headers_json; // [{"name":"...","value":"..."}]
    std::string body;
};

constexpr std::size_t kMaxInFlightExchanges = 8;
constexpr int kExchangePollMs = 50;
constexpr int kExchangeCancelGraceMs = 3'000;

// 进程级唯一 exchange id（前置：execute 内联使用）。全局唯一是回流路由的
// 正确性前提——否则会话栈与 connectivity 自检的局部实例各自从 1 起号，
// 同一 id 会错误投递到另一实例的在途交换。
std::atomic<std::uint64_t> g_next_exchange_id{1};

class KotlinHttpTransport final : public mira::IHttpTransport {
  public:
    // 构造一律经 create()：实例登记进进程级注册表，完成回流按 exchange id 路由
    // 到所属实例（会话栈与 connectivity 自检的局部实例均经同一条回流路径）。
    static std::shared_ptr<KotlinHttpTransport> create();

    ~KotlinHttpTransport() override;

    mira::Result<mira::HttpResponseInfo>
    execute(const mira::HttpRequest &request, const mira::TransportLimits &limits,
            const mira::OperationContext &context, const mira::HttpChunkCallback &on_chunk,
            mira::TransportTrace &trace) override {
        // 决策 4：transport 执行＝Reasoning 相位信号。
        emit_loop_event("phase", "{\"phase\":\"reasoning\"}");

        if (context.cancelled()) {
            return transport_error(mira::ErrorCode::Cancelled, "cancelled before exchange");
        }
        const auto started = clock::now();
        std::chrono::milliseconds remaining{120'000};
        if (context.deadline.has_value()) {
            const auto bounded =
                std::chrono::duration_cast<std::chrono::milliseconds>(*context.deadline - started);
            remaining = bounded.count() > 0 ? bounded : std::chrono::milliseconds(1);
        }
        const auto total_limit =
            std::min<std::int64_t>(limits.deadlines.total.count(), remaining.count());

        // 请求 JSON：凭据仅以 SecretRef 名称出现（Kotlin 侧解析注入 Authorization）。
        std::string headers;
        for (const auto &[name, value] : request.headers) {
            headers += "{\"name\":\"" + json_escape(name) + "\",\"value\":\"" +
                       json_escape(value) + "\"},";
        }
        if (!headers.empty()) {
            headers.pop_back();
        }
        char request_json[896];
        std::snprintf(request_json, sizeof(request_json),
                      "{\"method\":\"%.16s\",\"url\":\"%.384s\",\"headers\":[%s],"
                      "\"has_authorization\":%s,\"total_timeout_ms\":%lld,"
                      "\"max_response_bytes\":%llu}",
                      request.method.c_str(), request.url.c_str(), headers.c_str(),
                      request.authorization.has_value() ? "true" : "false",
                      static_cast<long long>(total_limit),
                      static_cast<unsigned long long>(limits.max_response_bytes));

        auto exchange = std::make_shared<HttpExchange>();
        const std::uint64_t exchange_id =
            g_next_exchange_id.fetch_add(1, std::memory_order_relaxed);
        {
            std::lock_guard lock(registry_mutex_);
            if (registry_.size() >= kMaxInFlightExchanges) {
                return transport_error(mira::ErrorCode::ResourceExhausted,
                                       "exchange registry capacity exhausted");
            }
            registry_[exchange_id] = exchange;
        }

        const jint accepted = start_exchange(exchange_id, request_json, request.body);
        if (accepted != 0) {
            remove_exchange(exchange_id);
            return transport_error(
                mira::ErrorCode::Unavailable,
                accepted == 1 ? "host transport unavailable" : "host transport rejected");
        }

        // 有界协作等待：50ms 轮询完成/取消/超时；取消时通知 Kotlin disconnect()。
        trace.write_started = true;
        std::int32_t status = 0;
        std::string headers_back;
        std::string body_back;
        bool done = false;
        {
            std::unique_lock lock(exchange->mutex);
            const auto hard_deadline =
                started + std::chrono::milliseconds(total_limit + kExchangeCancelGraceMs);
            while (!exchange->done) {
                (void)exchange->cv.wait_for(lock, std::chrono::milliseconds(kExchangePollMs));
                if (exchange->done) {
                    break;
                }
                const bool expired = clock::now() >= hard_deadline;
                const bool cancelled = context.cancelled();
                if ((expired || cancelled) && !exchange->cancel_notified) {
                    exchange->cancel_notified = true;
                    notify_cancel(exchange_id); // fire-and-forget，Kotlin 保证有界收尾回流
                }
                if (expired) {
                    break; // 取消宽限后仍未回流：按超时结算（迟到完成将被丢弃）。
                }
            }
            done = exchange->done;
            status = exchange->status;
            headers_back = exchange->headers_json;
            body_back = exchange->body;
        }
        remove_exchange(exchange_id);

        if (!done) {
            return transport_error(mira::ErrorCode::DeadlineExceeded, "exchange timed out");
        }
        if (status < 0) {
            switch (status) {
            case -1:
                return transport_error(mira::ErrorCode::Cancelled, "exchange cancelled");
            case -2:
                return transport_error(mira::ErrorCode::DeadlineExceeded, "exchange timed out");
            case -3:
                return transport_error(mira::ErrorCode::Unavailable, "network failure");
            default:
                return transport_error(mira::ErrorCode::Unavailable,
                                       "endpoint rejected the exchange");
            }
        }
        trace.write_completed = true;
        trace.headers_received = true;
        if (on_chunk != nullptr && !body_back.empty()) {
            on_chunk(body_back);
        }
        mira::HttpResponseInfo info;
        info.status = status;
        info.body_bytes = body_back.size();
        info.redirects_followed = 0;
        auto parsed = mira::parse_json(headers_back);
        if (parsed.has_value()) {
            if (const auto *items = parsed.value().as_array()) {
                for (const auto &item : *items) {
                    const auto *name = item.find("name");
                    const auto *value = item.find("value");
                    const std::string *name_text = name == nullptr ? nullptr : name->as_string();
                    const std::string *value_text =
                        value == nullptr ? nullptr : value->as_string();
                    if (name_text == nullptr || value_text == nullptr) {
                        continue;
                    }
                    std::string lower = *name_text;
                    std::transform(lower.begin(), lower.end(), lower.begin(),
                                   [](unsigned char c) { return std::tolower(c); });
                    info.headers.emplace_back(lower, *value_text);
                }
            }
        }
        return info;
    }

    // Kotlin 完成回流（nativeHttpExchangeComplete → loop::complete_http_exchange）。
    void complete(std::uint64_t exchange_id, std::int32_t status,
                  const std::string &headers_json, const std::string &body) {
        std::shared_ptr<HttpExchange> exchange;
        {
            std::lock_guard lock(registry_mutex_);
            const auto found = registry_.find(exchange_id);
            if (found == registry_.end()) {
                return; // 已结算/清理：迟到完成丢弃（有界语义）。
            }
            exchange = found->second;
        }
        std::lock_guard lock(exchange->mutex);
        exchange->status = status;
        exchange->headers_json = headers_json;
        exchange->body = body;
        exchange->done = true;
        exchange->cv.notify_all();
    }

    void shutdown() {
        std::lock_guard lock(registry_mutex_);
        for (auto &entry : registry_) {
            std::lock_guard exchange_lock(entry.second->mutex);
            if (!entry.second->done) {
                entry.second->status = -1;
                entry.second->done = true;
                entry.second->cv.notify_all();
            }
        }
        registry_.clear();
    }

  private:
    jint start_exchange(std::uint64_t exchange_id, const std::string &request_json,
                        const std::string &body) {
        miracle::bridge::AttachedEnv attach;
        JNIEnv *env = attach.env();
        jclass bridge = miracle::bridge::host_bridge_class();
        if (env == nullptr || bridge == nullptr) {
            return 2;
        }
        jmethodID start =
            env->GetStaticMethodID(bridge, "httpExchangeStart", "(JLjava/lang/String;[B)I");
        if (start == nullptr) {
            env->ExceptionClear();
            return 2;
        }
        jstring request_text = env->NewStringUTF(request_json.c_str());
        if (request_text == nullptr) {
            env->ExceptionClear();
            return 2;
        }
        jbyteArray body_array = env->NewByteArray(static_cast<jsize>(body.size()));
        if (body_array == nullptr) {
            env->ExceptionClear();
            env->DeleteLocalRef(request_text);
            return 2;
        }
        if (!body.empty()) {
            env->SetByteArrayRegion(body_array, 0, static_cast<jsize>(body.size()),
                                    reinterpret_cast<const jbyte *>(body.data()));
        }
        const jint accepted = env->CallStaticIntMethod(
            bridge, start, static_cast<jlong>(exchange_id), request_text, body_array);
        env->DeleteLocalRef(request_text);
        env->DeleteLocalRef(body_array);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return 2;
        }
        return accepted;
    }

    static mira::Error transport_error(mira::ErrorCode code, const char *message) {
        mira::Error error;
        error.code = code;
        error.domain = "miracle.transport";
        error.safe_message = message;
        return error;
    }

    void remove_exchange(std::uint64_t exchange_id) {
        std::lock_guard lock(registry_mutex_);
        registry_.erase(exchange_id);
    }

    void notify_cancel(std::uint64_t exchange_id) {
        miracle::bridge::AttachedEnv attach;
        JNIEnv *env = attach.env();
        jclass bridge = miracle::bridge::host_bridge_class();
        if (env == nullptr || bridge == nullptr) {
            return;
        }
        jmethodID cancel = env->GetStaticMethodID(bridge, "httpExchangeCancel", "(J)V");
        if (cancel == nullptr) {
            env->ExceptionClear();
            return;
        }
        env->CallStaticVoidMethod(bridge, cancel, static_cast<jlong>(exchange_id));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }

    std::mutex registry_mutex_;
    std::unordered_map<std::uint64_t, std::shared_ptr<HttpExchange>> registry_;
};

// 进程级传输注册表（weak_ptr）：complete_http_exchange 在锁内提升为强引用后
// 锁外调用，调用期间实例不会析构；已 shutdown/析构的实例 miss 回流（幂等 no-op）。
std::mutex g_transport_registry_mutex;
std::vector<std::weak_ptr<KotlinHttpTransport>> g_transport_registry;

std::shared_ptr<KotlinHttpTransport> KotlinHttpTransport::create() {
    auto transport = std::make_shared<KotlinHttpTransport>();
    {
        std::lock_guard lock(g_transport_registry_mutex);
        g_transport_registry.emplace_back(transport);
    }
    return transport;
}

KotlinHttpTransport::~KotlinHttpTransport() {
    shutdown();
    std::lock_guard lock(g_transport_registry_mutex);
    std::erase_if(g_transport_registry, [](const std::weak_ptr<KotlinHttpTransport> &weak) {
        return weak.expired();
    });
}

// ---- 脚本化传输（干跑探针；ChatCompletions wire JSON，决策来自脚本） ----

std::string chat_completions_body(const std::string &decision_json, std::uint64_t index) {
    char id[48];
    std::snprintf(id, sizeof(id), "script_%llu", static_cast<unsigned long long>(index));
    std::string body = "{\"id\":\"";
    body += id;
    body += "\",\"object\":\"chat.completion\",\"model\":\"scripted\",\"choices\":[{"
            "\"message\":{\"role\":\"assistant\",\"content\":\"";
    body += json_escape(decision_json);
    body += "\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":16,"
            "\"completion_tokens\":8}}";
    return body;
}

class ScriptedTransport final : public mira::IHttpTransport {
  public:
    explicit ScriptedTransport(std::vector<std::string> decision_scripts)
        : decisions_(std::move(decision_scripts)) {}

    mira::Result<mira::HttpResponseInfo>
    execute(const mira::HttpRequest & /*request*/, const mira::TransportLimits & /*limits*/,
            const mira::OperationContext &context, const mira::HttpChunkCallback &on_chunk,
            mira::TransportTrace &trace) override {
        emit_loop_event("phase", "{\"phase\":\"reasoning\"}");
        if (context.cancelled()) {
            mira::Error error;
            error.code = mira::ErrorCode::Cancelled;
            error.domain = "miracle.script";
            error.safe_message = "scripted exchange cancelled";
            return error;
        }
        std::string decision;
        {
            std::lock_guard lock(mutex_);
            if (index_ >= decisions_.size()) {
                mira::Error error;
                error.code = mira::ErrorCode::ResourceExhausted;
                error.domain = "miracle.script";
                error.safe_message = "script exhausted";
                return error;
            }
            index_ += 1;
            decision = decisions_[index_ - 1];
        }
        trace.write_started = true;
        trace.write_completed = true;
        trace.headers_received = true;
        const std::string body = chat_completions_body(decision, index_);
        if (on_chunk != nullptr) {
            on_chunk(body);
        }
        mira::HttpResponseInfo info;
        info.status = 200;
        info.headers.emplace_back("content-type", "application/json");
        info.body_bytes = body.size();
        info.redirects_followed = 0;
        return info;
    }

  private:
    std::mutex mutex_;
    std::vector<std::string> decisions_;
    std::uint64_t index_ = 0;
};

// ---- R3 确认（DEC-004；mira ConfirmationAuthority 为协议权威） ----

struct PendingR3 final {
    std::uint64_t correlation = 0;
    mira::ConfirmationChallenge challenge;
    mira::ProposedEffect effect;
    mira::ResourceDescriptor target;
    mira::PrincipalContext principal;
    std::string action_summary;
    std::string risk_reason;
    bool settled = false;
};

constexpr std::chrono::seconds kConfirmationLifetime{60};
constexpr std::size_t kMaxPendingConfirmations = 4;

struct ConfirmationRegistry final {
    std::mutex mutex;
    mira::ConfirmationAuthority authority;
    std::vector<std::shared_ptr<PendingR3>> pending;
};

ConfirmationRegistry g_confirmations;

// ---- LoopRuntime ----

enum class LoopState { Closed, Open, Running, Takeover };

struct LoopRuntime final {
    executor::Executor executor;
    std::shared_ptr<HostSecretResolver> secrets;
    std::shared_ptr<mira::ModelProfile> profile;
    std::unique_ptr<mira::ModelGateway> gateway;
    std::shared_ptr<mira::SimpleAdmissionGate> admission;
    std::shared_ptr<mira::MemoryEventStore> events;
    std::shared_ptr<KotlinHttpTransport> kotlin_transport;
    mira::ModelDoneVerifier verifier;
    // 帧载荷 store：注入 adapter（必须先于 adapter 声明——注入 store 的生命周期
    // 覆盖 adapter，成员逆序析构保证 adapter 先销毁）。
    std::shared_ptr<HostFrameStore> frame_store;
    std::shared_ptr<mira::adapters::android::AndroidHostAdapter> adapter;

    mira::AgentLoopConfig loop_config;
    mira::AgentLoopSpec spec;
    mira::RuntimeId runtime_id;
    std::uint64_t total_timeout_ms = 300'000;
    LoopState state = LoopState::Closed;
    std::string goal;
    std::string last_result_json;
    std::optional<executor::TaskHandle> cancel_handle;
    std::future<std::string> result_future;
};

std::mutex g_loop_mutex;
std::unique_ptr<LoopRuntime> g_loop;

bool kotlin_transport_ready() {
    miracle::bridge::AttachedEnv attach;
    JNIEnv *env = attach.env();
    jclass bridge = miracle::bridge::host_bridge_class();
    if (env == nullptr || bridge == nullptr) {
        return false;
    }
    jmethodID method = env->GetStaticMethodID(bridge, "transportReady", "()Z");
    if (method == nullptr) {
        env->ExceptionClear();
        return false;
    }
    const jboolean ready = env->CallStaticBooleanMethod(bridge, method);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return ready == JNI_TRUE;
}

std::string parse_string_field(const mira::JsonValue &root, const char *key) {
    const auto *field = root.find(key);
    if (field == nullptr) {
        return {};
    }
    const std::string *text = field->as_string();
    return text == nullptr ? std::string{} : *text;
}

std::int64_t parse_int_field(const mira::JsonValue &root, const char *key,
                             std::int64_t fallback) {
    const auto *field = root.find(key);
    if (field == nullptr) {
        return fallback;
    }
    const auto value = field->as_integer();
    return value.has_value() ? *value : fallback;
}

// 按设置构建 profile（能力位诚实：Configured 级证据，见 P3 计划决策 7）。
std::shared_ptr<mira::ModelProfile> build_profile(const std::string &endpoint,
                                                  const std::string &api_prefix,
                                                  const std::string &model, bool chat_dialect) {
    auto profile = std::make_shared<mira::ModelProfile>();
    profile->id = mira::ModelProfileId::generate();
    profile->display_name = "miracle";
    profile->version = mira::SemanticVersion{1, 0, 0};
    profile->dialect = chat_dialect ? mira::ProtocolDialect::OpenAIChatCompletionsV1
                                    : mira::ProtocolDialect::OpenAIResponsesV1;
    profile->endpoint_origin = endpoint;
    profile->api_prefix = api_prefix.empty() ? "/v1" : api_prefix;
    profile->model_selector = model;
    profile->credential = mira::SecretRef{"miracle.model.key"};
    const auto configured = mira::CapabilityEvidence::Configured;
    profile->capabilities.text = mira::CapabilityFlag{true, configured, "user endpoint"};
    profile->capabilities.image_input =
        mira::CapabilityFlag{true, configured, "user endpoint (host-encoded png wire)"};
    profile->capabilities.strict_json_schema =
        mira::CapabilityFlag{true, configured, "user endpoint"};
    profile->capabilities.sse = mira::CapabilityFlag{false, configured, "non-stream"};
    profile->default_data_policy.store = false;
    return profile;
}

const char *phase_name(mira::StepPhase phase) {
    switch (phase) {
    case mira::StepPhase::Observed:
        return "observed";
    case mira::StepPhase::Reasoned:
        return "reasoned";
    case mira::StepPhase::Acted:
        return "acted";
    case mira::StepPhase::Verified:
        return "verified";
    case mira::StepPhase::Recovering:
        return "recovering";
    }
    return "unknown";
}

std::string loop_result_json(const mira::AgentLoopResult &result, std::uint64_t events_count,
                             const std::string &bridge_stats_json) {
    std::string steps;
    for (const auto &record : result.steps) {
        if (!steps.empty()) {
            steps += ",";
        }
        char buffer[640];
        std::snprintf(buffer, sizeof(buffer),
                      "{\"step\":%u,\"phase\":\"%.12s\",\"summary\":\"%.160s\","
                      "\"verified\":%s,\"note\":\"%.160s\"}",
                      record.step, phase_name(record.phase),
                      json_escape(record.action_summary).c_str(),
                      record.verified ? "true" : "false", json_escape(record.note).c_str());
        steps += buffer;
    }
    char header[896];
    std::snprintf(header, sizeof(header),
                  "{\"outcome\":\"%s\",\"summary\":\"%.320s\",\"steps_count\":%u,"
                  "\"recoveries\":%u,\"repairs\":%u,\"events\":%llu,\"steps\":[%s],"
                  "\"bridge\":%s,\"mira_version\":\"%u.%u.%u\"}",
                  mira::loop_outcome_name(result.outcome).c_str(),
                  json_escape(result.safe_summary).c_str(),
                  static_cast<unsigned>(result.steps.size()), result.recoveries,
                  result.repairs,
                  static_cast<unsigned long long>(events_count), steps.c_str(),
                  bridge_stats_json.empty() ? "{}" : bridge_stats_json.c_str(),
                  mira::kVersion.major, mira::kVersion.minor, mira::kVersion.patch);
    return header;
}

// 组装模型对象栈（open 与 connectivity 共用；transport 与工件源由调用方注入）。
struct AssembledStack final {
    std::shared_ptr<HostSecretResolver> secrets;
    std::shared_ptr<mira::ModelProfile> profile;
    mira::ModelRouter router;
    std::unique_ptr<mira::ModelGateway> gateway;
    std::shared_ptr<mira::SimpleAdmissionGate> admission;
    std::shared_ptr<mira::MemoryEventStore> events;
};

bool assemble_stack(AssembledStack &stack, executor::Executor &executor,
                    const std::shared_ptr<mira::ModelProfile> &profile,
                    const std::shared_ptr<mira::IHttpTransport> &transport,
                    const std::shared_ptr<mira::IArtifactSource> &artifacts,
                    const std::string &api_key) {
    stack.secrets = std::make_shared<HostSecretResolver>(api_key);
    stack.profile = profile;
    stack.router.register_profile(profile);
    stack.gateway = std::make_unique<mira::ModelGateway>(executor, stack.router, artifacts,
                                                         mira::PriceTable{},
                                                         mira::ModelGatewayConfig{});
    stack.gateway->register_provider(std::make_shared<mira::OpenAiCompatibleProvider>(
        profile, transport, artifacts));
    stack.admission = std::make_shared<mira::SimpleAdmissionGate>();
    stack.gateway->set_admission_gate(stack.admission);
    stack.events = std::make_shared<mira::MemoryEventStore>(10'000);
    stack.gateway->set_event_store(stack.events, mira::RuntimeId::generate(),
                                   mira::SessionId::generate());
    const auto valid = profile->validate();
    if (!valid.has_value()) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "profile invalid: %s",
                            valid.error().safe_message.c_str());
        return false;
    }
    return true;
}

} // namespace

namespace miracle::bridge::loop {

bool active() {
    std::lock_guard lock(g_loop_mutex);
    return g_loop != nullptr &&
           (g_loop->state == LoopState::Running || g_loop->state == LoopState::Takeover);
}

void emit_phase_signal(const char *phase) {
    std::string payload = std::string("{\"phase\":\"") + phase + "\"}";
    emit_loop_event("phase", payload);
}

void complete_http_exchange(std::uint64_t exchange_id, std::int32_t status,
                            const std::string &headers_json, const std::string &body) {
    // 按 id 路由到所属传输实例：会话栈（g_loop）与 connectivity 自检的局部实例
    // 都在注册表内。锁内只提升强引用，锁外调用（complete 不触碰 g_loop 锁，
    // 避免与注册表锁形成嵌套序）；id 全局唯一保证恰好一个 registry 命中，
    // 其余实例按未知 id 丢弃（迟到/孤儿完成的既有语义不变）。
    std::vector<std::shared_ptr<KotlinHttpTransport>> live;
    {
        std::lock_guard lock(g_transport_registry_mutex);
        for (const auto &weak : g_transport_registry) {
            if (auto transport = weak.lock()) {
                live.push_back(std::move(transport));
            }
        }
    }
    for (const auto &transport : live) {
        transport->complete(exchange_id, status, headers_json, body);
    }
}

// —— R3 确认 ——
// 锁序：先在 g_loop_mutex 下读 epoch/session（立即释放），再入确认注册表。

struct ConfirmationEpochs {
    mira::SessionId session;
    mira::TaskId task;
    std::uint64_t task_epoch = 1;
    std::uint64_t env_epoch = 1;
};

ConfirmationEpochs confirmation_epochs() {
    ConfirmationEpochs epochs;
    epochs.session = mira::SessionId::generate();
    epochs.task = mira::TaskId::generate();
    std::lock_guard loop_lock(g_loop_mutex);
    if (g_loop != nullptr) {
        epochs.session = g_loop->spec.session_id;
        epochs.task = g_loop->spec.task_id;
        epochs.task_epoch = g_loop->spec.task_epoch;
        epochs.env_epoch =
            g_loop->adapter == nullptr ? 1 : g_loop->adapter->environment_epoch();
    }
    return epochs;
}

std::string begin_confirmation(std::uint64_t correlation, const std::string &events_json,
                               const std::string &digest_hex, const std::string &action_summary,
                               const std::string &risk_reason) {
    const ConfirmationEpochs epochs = confirmation_epochs();
    auto item = std::make_shared<PendingR3>();
    item->correlation = correlation;
    item->effect.action = "android.input.dispatch";
    item->effect.parameters = events_json;
    item->effect.risk = mira::ActionRisk::R3Sensitive;
    item->effect.has_side_effect = true;
    item->target.type = "device";
    item->target.id = "primary";
    item->target.scope = "display0";
    item->principal.host_id = mira::HostInstanceId::generate();
    item->principal.auth_strength = mira::AuthenticationStrength::Session;
    item->action_summary = action_summary;
    item->risk_reason = risk_reason;

    auto challenge = g_confirmations.authority.issue(
        item->principal, epochs.session, epochs.task, epochs.task_epoch, epochs.env_epoch,
        item->effect, item->target, 1, kConfirmationLifetime);
    if (!challenge.has_value()) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "confirmation issue failed: %s",
                            challenge.error().safe_message.c_str());
        return {};
    }
    item->challenge = challenge.value();
    {
        std::lock_guard lock(g_confirmations.mutex);
        if (g_confirmations.pending.size() >= kMaxPendingConfirmations) {
            return {}; // 有界：同时至多 4 个待确认（单任务串行场景足够）。
        }
        g_confirmations.pending.push_back(item);
    }

    const auto expires_at = std::chrono::system_clock::to_time_t(item->challenge.expires_at);
    char payload[960];
    std::snprintf(payload, sizeof(payload),
                  "{\"challenge\":\"%.64s\",\"nonce\":\"%.64s\",\"digest\":\"%.64s\","
                  "\"summary\":\"%.192s\",\"risk\":\"%.160s\",\"expires_at\":%lld,"
                  "\"correlation\":%llu,\"lifetime_ms\":%lld}",
                  item->challenge.id.to_string().c_str(),
                  item->challenge.nonce.to_string().c_str(), digest_hex.c_str(),
                  json_escape(action_summary).c_str(), json_escape(risk_reason).c_str(),
                  static_cast<long long>(expires_at),
                  static_cast<unsigned long long>(correlation),
                  static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(
                                             kConfirmationLifetime)
                                             .count()));
    emit_loop_event("confirmation_request", payload);
    return item->challenge.id.to_string();
}

std::int32_t resolve_confirmation(const std::string &challenge_hex, const std::string &nonce_hex,
                                  bool approve) {
    std::shared_ptr<PendingR3> item;
    {
        std::lock_guard lock(g_confirmations.mutex);
        for (const auto &candidate : g_confirmations.pending) {
            if (candidate->challenge.id.to_string() == challenge_hex && !candidate->settled) {
                item = candidate;
                break;
            }
        }
    }
    if (item == nullptr) {
        return 2; // 未找到/已失效（超期清扫、takeover 或重复 resolve）。
    }
    mira::ConfirmationResponse response;
    response.challenge_id = item->challenge.id;
    if (const auto nonce = mira::Id128::parse(nonce_hex); nonce.has_value()) {
        response.nonce = *nonce;
    }
    response.decision =
        approve ? mira::ConfirmationDecision::Approve : mira::ConfirmationDecision::Reject;
    const ConfirmationEpochs epochs = confirmation_epochs();
    const auto consumed = g_confirmations.authority.consume(
        item->challenge, response, item->principal, item->effect, item->target,
        epochs.task_epoch, epochs.env_epoch, 1);
    {
        std::lock_guard lock(g_confirmations.mutex);
        item->settled = true;
    }
    if (!consumed.has_value()) {
        // 校验失败（nonce 不匹配/超期/epoch 变化）：失效并按拒绝结算。
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "confirmation consume failed: %s",
                            consumed.error().safe_message.c_str());
        emit_loop_event("confirmation_settled",
                        "{\"challenge\":\"" + challenge_hex + "\",\"outcome\":\"invalid\"}");
        return -3;
    }
    emit_loop_event("confirmation_settled",
                    "{\"challenge\":\"" + challenge_hex + "\",\"outcome\":\"" +
                        (approve ? "approved" : "rejected") + "\"}");
    return approve ? 0 : 1;
}

std::uint64_t confirmation_correlation(const std::string &challenge_hex) {
    // 不按 settled 过滤：resolve 之后仍需查询 correlation 以驱动派发/结算；
    // 停放表（host_abi_impl）是"该操作是否还需处理"的权威。
    std::lock_guard lock(g_confirmations.mutex);
    for (const auto &item : g_confirmations.pending) {
        if (item->challenge.id.to_string() == challenge_hex) {
            return item->correlation;
        }
    }
    return 0;
}

std::string expire_confirmations() {
    std::vector<std::pair<std::uint64_t, std::string>> expired;
    const auto now = std::chrono::system_clock::now();
    {
        std::lock_guard lock(g_confirmations.mutex);
        for (const auto &item : g_confirmations.pending) {
            if (item->settled || item->challenge.expires_at > now) {
                continue;
            }
            item->settled = true;
            expired.emplace_back(item->correlation, item->challenge.id.to_string());
        }
        // 有界回收：保留最近 16 条（防重放观察），更早条目丢弃。
        if (g_confirmations.pending.size() > 16) {
            g_confirmations.pending.erase(g_confirmations.pending.begin(),
                                          g_confirmations.pending.end() - 16);
        }
    }
    for (const auto &[correlation, challenge] : expired) {
        emit_loop_event("confirmation_settled",
                        "{\"challenge\":\"" + challenge + "\",\"outcome\":\"expired\"}");
    }
    std::string json = "[";
    for (const auto &[correlation, challenge] : expired) {
        if (json.size() > 1) {
            json += ",";
        }
        json += std::to_string(correlation);
    }
    json += "]";
    return json;
}

void invalidate_confirmations_for_takeover() {
    std::vector<std::string> challenges;
    {
        std::lock_guard lock(g_confirmations.mutex);
        for (const auto &item : g_confirmations.pending) {
            if (!item->settled) {
                item->settled = true;
                item->correlation = 0; // 0＝不放行派发
                challenges.push_back(item->challenge.id.to_string());
            }
        }
    }
    for (const auto &challenge : challenges) {
        emit_loop_event("confirmation_settled",
                        "{\"challenge\":\"" + challenge + "\",\"outcome\":\"takeover\"}");
    }
}

// —— 会话管理 ——

std::int32_t open(const std::string &config_json) {
    {
        std::lock_guard lock(g_loop_mutex);
        if (g_loop != nullptr) {
            return -1;
        }
    }
    const auto parsed = mira::parse_json(config_json);
    if (!parsed.has_value()) {
        return -4;
    }
    const mira::JsonValue &config = parsed.value();
    const std::string endpoint = parse_string_field(config, "endpoint");
    const std::string api_prefix = parse_string_field(config, "api_prefix");
    const std::string model = parse_string_field(config, "model");
    const std::string api_key = parse_string_field(config, "api_key");
    const std::string dialect = parse_string_field(config, "dialect");
    const std::string transport_kind = parse_string_field(config, "transport");
    const bool scripted = transport_kind == "scripted";
    if (!scripted) {
        if (endpoint.rfind("https://", 0) != 0 || model.empty() || api_key.empty()) {
            return -4; // 真实传输：端点必须 https 且配置完整（fail-closed）。
        }
    }

    auto runtime = std::make_unique<LoopRuntime>();
    const std::int64_t max_steps = parse_int_field(config, "max_steps", 16);
    runtime->loop_config.max_steps =
        static_cast<std::uint32_t>(std::clamp<std::int64_t>(max_steps, 1, 128));
    runtime->loop_config.max_recoveries_per_step = 1;
    runtime->loop_config.model_call_deadline = std::chrono::milliseconds(
        scripted ? 5'000 : parse_int_field(config, "call_timeout_ms", 30'000));
    runtime->loop_config.observation_max_age = std::chrono::milliseconds{2'000};

    executor::ExecutorConfig executor_config;
    executor_config.min_threads = 4;
    executor_config.max_threads = 4;
    executor_config.queue_capacity = 128;
    if (!runtime->executor.initialize(executor_config)) {
        return -2;
    }

    // 注入帧载荷 store（mira DEC-012）：容量按 PNG 载荷预算（原始帧转码后即回收；
    // 未编码回退路径下等同 mira 默认 64MiB 的两倍，覆盖 max_steps=128 的极端序列）。
    runtime->frame_store = std::make_shared<HostFrameStore>(128ULL * 1024ULL * 1024ULL);
    mira::adapters::android::AndroidHostAdapterOptions adapter_options;
    adapter_options.artifact_store = runtime->frame_store;
    auto created =
        mira::adapters::android::AndroidHostAdapter::create(runtime->executor, adapter_options);
    if (!created.has_value()) {
        (void)runtime->executor.shutdown(true);
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "adapter create failed: %s",
                            created.error().safe_message.c_str());
        return -2; // 含进程内单 host 冲突（其他自检会话占用）。
    }
    runtime->adapter = std::move(created.value());

    std::shared_ptr<mira::IHttpTransport> transport;
    if (scripted) {
        std::vector<std::string> decisions;
        if (const auto *script = config.find("script"); script != nullptr &&
                                                          script->is_array()) {
            for (const auto &entry : *script->as_array()) {
                decisions.push_back(mira::to_json_string(entry));
            }
        }
        if (decisions.empty()) {
            (void)runtime->adapter.reset();
            (void)runtime->executor.shutdown(true);
            return -4;
        }
        transport = std::make_shared<ScriptedTransport>(std::move(decisions));
        runtime->profile = build_profile("https://scripted.local", "/v1", "scripted", true);
    } else {
        if (!kotlin_transport_ready()) {
            (void)runtime->adapter.reset();
            (void)runtime->executor.shutdown(true);
            return -3;
        }
        runtime->kotlin_transport = KotlinHttpTransport::create();
        transport = runtime->kotlin_transport;
        runtime->profile = build_profile(endpoint, api_prefix, model, dialect == "chat");
    }

    // wire 工件源接帧 store：截图部件的 data URL 字节经公共 store API 回读。
    auto artifacts = std::static_pointer_cast<mira::IArtifactSource>(
        std::make_shared<StoreArtifactSource>(runtime->frame_store));
    AssembledStack stack;
    if (!assemble_stack(stack, runtime->executor, runtime->profile, transport, artifacts,
                        scripted ? std::string{"scripted"} : api_key)) {
        (void)runtime->adapter.reset();
        (void)runtime->executor.shutdown(true);
        return -4;
    }
    runtime->secrets = stack.secrets;
    runtime->gateway = std::move(stack.gateway);
    runtime->admission = stack.admission;
    runtime->events = stack.events;

    runtime->spec.task_id = mira::TaskId::generate();
    runtime->spec.session_id = mira::SessionId::generate();
    runtime->spec.task_epoch = 1;
    runtime->spec.profile_id = runtime->profile->id;
    runtime->runtime_id = mira::RuntimeId::generate();
    // 总超时：显式配置需为正。缺省/非正值回退按步数×调用期限推得的预算——
    // 0 不是合法总超时（会把会话 deadline 设成立即过期，observe 即被拒）。
    const std::int64_t configured_timeout_ms = parse_int_field(config, "timeout_ms", 0);
    runtime->total_timeout_ms = configured_timeout_ms > 0
        ? static_cast<std::uint64_t>(configured_timeout_ms)
        : static_cast<std::uint64_t>(runtime->loop_config.max_steps + 2) *
              static_cast<std::uint64_t>(
                  runtime->loop_config.model_call_deadline.count() + 15'000);
    runtime->state = LoopState::Open;

    {
        std::lock_guard lock(g_loop_mutex);
        if (g_loop != nullptr) {
            return -1;
        }
        g_loop = std::move(runtime);
    }
    emit_loop_event("session", "{\"state\":\"open\"}");
    return 1;
}

std::int32_t submit(const std::string &goal, std::int32_t max_steps) {
    std::unique_lock lock(g_loop_mutex);
    if (g_loop == nullptr || g_loop->state != LoopState::Open || goal.empty()) {
        return -1;
    }
    LoopRuntime &runtime = *g_loop;
    runtime.goal = goal;
    runtime.spec.goal = goal;
    if (max_steps > 0) {
        runtime.loop_config.max_steps =
            static_cast<std::uint32_t>(std::min<std::int32_t>(max_steps, 128));
    }
    runtime.admission->activate(runtime.spec.task_id, runtime.spec.task_epoch);
    runtime.state = LoopState::Running;
    runtime.last_result_json.clear();

    const std::uint64_t total_timeout_ms = runtime.total_timeout_ms;
    // 提交协作可取消的 loop 任务；句柄与 future 留存（StopToken 是取消通道；
    // future 仅供 close 在 JNI 线程有界排空，不得在 executor worker 上等待）。
    auto submission = runtime.executor.submit_cancellable(
        [&runtime, total_timeout_ms](executor::StopToken token) -> std::string {
            mira::OperationContext context;
            context.session = runtime.spec.session_id;
            context.task = runtime.spec.task_id;
            context.task_epoch = runtime.spec.task_epoch;
            context.operation = mira::OperationId::generate();
            context.started_at = mira::Timestamp::now();
            context.deadline = clock::now() + std::chrono::milliseconds(total_timeout_ms);
            context.cancellation_requested = [&token]() { return token.stop_requested(); };

            mira::AgentLoop loop(runtime.adapter, *runtime.gateway, runtime.loop_config);
            loop.set_event_store(runtime.events, runtime.runtime_id, runtime.spec.session_id);
            auto outcome = loop.run(runtime.spec, context, runtime.verifier);
            mira::AgentLoopResult result;
            if (outcome.has_value()) {
                result = std::move(outcome.value());
            } else {
                result.outcome = mira::LoopOutcome::Failed;
                result.safe_summary = "loop failed: " + outcome.error().safe_message;
            }

            std::string bridge_stats;
            miracle_host_debug_stats_json(bridge_stats);
            const std::uint64_t events = runtime.events->size();
            const std::string json = loop_result_json(result, events, bridge_stats);
            {
                std::lock_guard state_lock(g_loop_mutex);
                runtime.last_result_json = json;
                if (runtime.state == LoopState::Running ||
                    runtime.state == LoopState::Takeover) {
                    runtime.state = LoopState::Open; // 终态幂等：会话保留供下一次提交
                }
            }
            // 终态上投（锁外；Kotlin 侧只读状态流，不同步回调 native）。
            emit_loop_event("result", json);
            return json;
        });
    if (!submission.handle.valid()) {
        runtime.state = LoopState::Open;
        return -2;
    }
    runtime.cancel_handle = submission.handle;
    runtime.result_future = std::move(submission.future);
    return 1;
}

std::int32_t cancel() {
    std::lock_guard lock(g_loop_mutex);
    if (g_loop == nullptr) {
        return -1;
    }
    g_loop->admission->deactivate(g_loop->spec.task_id);
    if (g_loop->cancel_handle.has_value() && g_loop->cancel_handle->valid()) {
        (void)g_loop->executor.request_task_cancel(*g_loop->cancel_handle);
    }
    return 1;
}

std::int32_t takeover() {
    std::lock_guard lock(g_loop_mutex);
    if (g_loop == nullptr) {
        return -1;
    }
    g_loop->state = LoopState::Takeover;
    g_loop->admission->deactivate(g_loop->spec.task_id);
    if (g_loop->cancel_handle.has_value() && g_loop->cancel_handle->valid()) {
        (void)g_loop->executor.request_task_cancel(*g_loop->cancel_handle);
    }
    if (g_loop->adapter != nullptr) {
        (void)g_loop->adapter->interrupt(mira::make_control_context()); // RELEASE_ALL
    }
    invalidate_confirmations_for_takeover();
    return 1;
}

std::string close() {
    std::unique_ptr<LoopRuntime> runtime;
    {
        std::lock_guard lock(g_loop_mutex);
        if (g_loop == nullptr) {
            return "{\"ok\":false,\"error\":\"not open\"}";
        }
        runtime = std::move(g_loop);
        g_loop = nullptr;
    }
    // 关闭顺序（P3 计划决策 3）：admission 失效 → 协作取消 → loop 任务有界排空
    // （JNI 线程等待，不在 executor worker 上自等待）→ adapter 销毁（host stop/destroy，
    // 在途操作结算）→ 传输关闭 → executor shutdown(true)。
    runtime->admission->deactivate(runtime->spec.task_id);
    if (runtime->cancel_handle.has_value() && runtime->cancel_handle->valid()) {
        (void)runtime->executor.request_task_cancel(*runtime->cancel_handle);
    }
    if (runtime->result_future.valid()) {
        (void)runtime->result_future.wait_for(std::chrono::seconds(10));
    }
    runtime->adapter.reset();
    runtime->frame_store.reset(); // adapter 之后释放注入 store（宿主编码登记随之清空）
    miracle::bridge::frame_encoding_registry().clear();
    if (runtime->kotlin_transport != nullptr) { // scripted 干跑无 Kotlin 传输
        (void)runtime->kotlin_transport->shutdown();
    }
    const auto shutdown = runtime->executor.shutdown(true);
    const bool shutdown_ok = shutdown == executor::ShutdownResult::Completed;
    invalidate_confirmations_for_takeover();
    (void)expire_confirmations();
    std::string bridge_stats;
    miracle_host_debug_stats_json(bridge_stats);
    char summary[512];
    std::snprintf(summary, sizeof(summary),
                  "{\"ok\":%s,\"state\":\"%s\",\"goal\":\"%.128s\","
                  "\"last_result\":%s,\"bridge\":%s,\"shutdown\":\"%s\"}",
                  shutdown_ok ? "true" : "false",
                  runtime->state == LoopState::Running
                      ? "running"
                      : runtime->state == LoopState::Takeover ? "takeover" : "open",
                  json_escape(runtime->goal).c_str(),
                  runtime->last_result_json.empty() ? "null"
                                                    : runtime->last_result_json.c_str(),
                  bridge_stats.empty() ? "{}" : bridge_stats.c_str(),
                  shutdown_ok ? "Completed" : "Incomplete");
    emit_loop_event("session", "{\"state\":\"closed\"}");
    return summary;
}

std::string state_json() {
    std::lock_guard lock(g_loop_mutex);
    if (g_loop == nullptr) {
        return "{\"state\":\"closed\"}";
    }
    std::size_t pending_confirmations = 0;
    {
        std::lock_guard confirmations_lock(g_confirmations.mutex);
        for (const auto &item : g_confirmations.pending) {
            if (!item->settled) {
                pending_confirmations += 1;
            }
        }
    }
    char buffer[384];
    std::snprintf(buffer, sizeof(buffer),
                  "{\"state\":\"%s\",\"goal\":\"%.128s\",\"pending_confirmations\":%zu}",
                  g_loop->state == LoopState::Running
                      ? "running"
                      : g_loop->state == LoopState::Takeover ? "takeover" : "open",
                  json_escape(g_loop->goal).c_str(), pending_confirmations);
    return buffer;
}

std::string connectivity(const std::string &config_json) {
    const auto started = clock::now();
    const auto parsed = mira::parse_json(config_json);
    if (!parsed.has_value()) {
        return "{\"ok\":false,\"stage\":\"config\",\"error\":\"invalid config json\"}";
    }
    const mira::JsonValue &config = parsed.value();
    const std::string endpoint = parse_string_field(config, "endpoint");
    const std::string api_prefix = parse_string_field(config, "api_prefix");
    const std::string model = parse_string_field(config, "model");
    const std::string api_key = parse_string_field(config, "api_key");
    const std::string dialect = parse_string_field(config, "dialect");
    if (endpoint.rfind("https://", 0) != 0 || model.empty() || api_key.empty()) {
        return "{\"ok\":false,\"stage\":\"config\",\"error\":\"endpoint/model/api_key required\"}";
    }
    if (!kotlin_transport_ready()) {
        return "{\"ok\":false,\"stage\":\"transport\",\"error\":\"host transport unavailable\"}";
    }

    executor::Executor executor;
    executor::ExecutorConfig executor_config;
    executor_config.min_threads = 2;
    executor_config.max_threads = 2;
    executor_config.queue_capacity = 16;
    if (!executor.initialize(executor_config)) {
        return "{\"ok\":false,\"stage\":\"executor\",\"error\":\"initialize failed\"}";
    }
    auto transport = KotlinHttpTransport::create();
    auto profile = build_profile(endpoint, api_prefix, model, dialect == "chat");
    // 连通性自检文本-only（无 ImagePart）：fail-closed 工件源，触达即如实报错。
    auto artifacts = std::static_pointer_cast<mira::IArtifactSource>(
        std::make_shared<FailClosedArtifactSource>());
    AssembledStack stack;
    const bool assembled = assemble_stack(stack, executor, profile, transport, artifacts, api_key);
    if (!assembled) {
        (void)executor.shutdown(true);
        return "{\"ok\":false,\"stage\":\"profile\",\"error\":\"profile validation failed\"}";
    }

    // 文本-only 决策请求（无 ImagePart；截图 wire 路径经 HostFrameStore 由 loop 栈使用）。
    const auto connectivity_task = mira::TaskId::generate();
    stack.admission->activate(connectivity_task, 1);
    mira::ModelRequest request;
    request.contract_version = mira::SchemaVersion{1, 0};
    request.request_id = mira::ModelRequestId::generate();
    request.operation_id = mira::OperationId::generate();
    request.task_id = connectivity_task;
    request.task_epoch = 1;
    request.profile_id = profile->id;
    mira::ModelInputItem system_item;
    system_item.role = mira::ModelRole::System;
    system_item.provenance.source = "miracle.connectivity.v1";
    mira::TextPart system_text;
    system_text.text = "You are a connectivity probe. Reply with exactly one JSON object and "
                       "nothing else.";
    system_item.content.emplace_back(std::move(system_text));
    mira::ModelInputItem user_item;
    user_item.role = mira::ModelRole::User;
    user_item.provenance.source = "miracle.connectivity.v1";
    mira::TextPart user_text;
    user_text.text =
        "Reply with exactly {\"action\":\"done\",\"reason\":\"connectivity\"}.";
    user_item.content.emplace_back(std::move(user_text));
    request.input = {std::move(system_item), std::move(user_item)};
    const auto schema = mira::agent_decision_schema();
    request.output_contract.mode = mira::OutputMode::StrictJsonSchema;
    request.output_contract.schema_id =
        mira::SchemaId::parse("6d6972612d6465636973696f6e2d7631").value_or(mira::SchemaId{});
    request.output_contract.schema_version = mira::SemanticVersion{1, 0, 0};
    request.output_contract.schema = schema;
    request.output_contract.canonical_schema_digest = mira::canonical_json_digest(schema.root);
    request.generation.max_output_tokens = 256;
    request.data_policy.store = false;

    mira::OperationContext context;
    context.task = request.task_id;
    context.task_epoch = 1;
    context.operation = mira::OperationId::generate();
    context.started_at = mira::Timestamp::now();
    context.deadline = clock::now() + std::chrono::milliseconds(
                                          parse_int_field(config, "call_timeout_ms", 30'000));

    const auto outcome = stack.gateway->infer(request, context, mira::InferOptions{});
    transport->shutdown();
    (void)executor.shutdown(true);

    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                             clock::now() - started)
                             .count();
    if (!outcome.has_value()) {
        char buffer[512];
        std::snprintf(buffer, sizeof(buffer),
                      "{\"ok\":false,\"stage\":\"infer\",\"error\":\"%.192s\",\"ms\":%lld}",
                      json_escape(outcome.error().safe_message).c_str(),
                      static_cast<long long>(elapsed));
        return buffer;
    }
    const mira::ModelCallOutcome &value = outcome.value();
    const bool decided = value.admitted && value.parse.outcome ==
                                              mira::DecisionParseOutcome::Decision &&
                         value.parse.decision.has_value();
    char buffer[768];
    std::snprintf(buffer, sizeof(buffer),
                  "{\"ok\":%s,\"stage\":\"infer\",\"admitted\":%s,"
                  "\"attempts\":%u,\"decision\":%s,\"violations\":%zu,"
                  "\"rejection\":\"%.128s\",\"usage_in\":%llu,\"usage_out\":%llu,"
                  "\"ms\":%lld,\"mira_version\":\"%u.%u.%u\"}",
                  decided ? "true" : "false", value.admitted ? "true" : "false",
                  value.attempts, decided ? "true" : "false",
                  value.parse.violations.size(),
                  json_escape(value.rejection_reason).c_str(),
                  static_cast<unsigned long long>(value.response.usage.input_tokens.value_or(0)),
                  static_cast<unsigned long long>(value.response.usage.output_tokens.value_or(0)),
                  static_cast<long long>(elapsed), mira::kVersion.major,
                  mira::kVersion.minor, mira::kVersion.patch);
    return buffer;
}

} // namespace miracle::bridge::loop
