// 宿主帧编码登记（bridge 内部，仅 app/src/main/cpp 使用）。
//
// mira DEC-013 的"宿主负责编码"路径：Kotlin 截屏时把 RGBA 帧同步编码为 PNG 并随帧
// 完成上投；native 以原始帧字节的 sha256 为键登记编码结果，注入 loop 的
// HostFrameStore（loop_runtime.cpp）在 commit 时据此把原始帧重新发布为 image/png
// 工件——descriptor 的 payload 媒体类型/字节数/摘要由 store 记录决定，模型 wire
// 层（方言门 + data URL）随之取到 image/png。
#pragma once

#include <mira/event_store.hpp>

#include <cstddef>
#include <deque>
#include <mutex>
#include <optional>
#include <vector>

namespace miracle::bridge {

class FrameEncodingRegistry final {
  public:
    FrameEncodingRegistry() = default;
    FrameEncodingRegistry(const FrameEncodingRegistry &) = delete;
    FrameEncodingRegistry &operator=(const FrameEncodingRegistry &) = delete;

    // 登记一帧的已编码载荷（键＝原始帧字节 sha256）。容量有限：超过
    // [kMaxEntries] 帧逐出最旧；重复内容（静态画面复用同帧）幂等覆盖。
    void put(const mira::Sha256Digest &key, std::vector<std::byte> encoded) {
        std::lock_guard lock(mutex_);
        for (auto &entry : entries_) {
            if (entry.key == key) {
                entry.payload = std::move(encoded);
                return;
            }
        }
        if (entries_.size() >= kMaxEntries) {
            entries_.pop_front();
        }
        entries_.push_back(Entry{key, std::move(encoded)});
    }

    // 按键查询已编码载荷（不消费：同帧多次 commit 都能命中）。
    [[nodiscard]] std::optional<std::vector<std::byte>> peek(const mira::Sha256Digest &key) const {
        std::lock_guard lock(mutex_);
        for (const auto &entry : entries_) {
            if (entry.key == key) {
                return entry.payload;
            }
        }
        return std::nullopt;
    }

    void clear() {
        std::lock_guard lock(mutex_);
        entries_.clear();
    }

    static constexpr std::size_t kMaxEntries = 4;

  private:
    struct Entry final {
        mira::Sha256Digest key;
        std::vector<std::byte> payload;
    };

    mutable std::mutex mutex_;
    std::deque<Entry> entries_;
};

// 进程内单例（宿主帧完成在 JNI 线程写入，loop worker 在 commit 时读取）。
[[nodiscard]] FrameEncodingRegistry &frame_encoding_registry();

} // namespace miracle::bridge
