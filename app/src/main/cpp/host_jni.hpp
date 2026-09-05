// miracle bridge 内部共享：JVM/类引用缓存（JNI_OnLoad 设置，全进程只读）。
// 仅限 app/src/main/cpp 内部使用，不进入公共头。
#pragma once

#include <jni.h>

namespace miracle::bridge {

// JNI_OnLoad 设置；运行期只读。
[[nodiscard]] JavaVM *java_vm();
void set_java_vm(JavaVM *vm);

// dev.linductor.miracle.host.HostBridge 的全局引用（JNI_OnLoad 设置）。
[[nodiscard]] jclass host_bridge_class();
void set_host_bridge_class(jclass ref);

// 在任意线程取得 JNIEnv：已附加线程复用，未附加线程临时附加并在析构时分离。
// 仅供低频控制面调用（capabilities/topology 查询）；不得长期持有。
class AttachedEnv final {
  public:
    AttachedEnv();
    ~AttachedEnv();
    AttachedEnv(const AttachedEnv &) = delete;
    AttachedEnv &operator=(const AttachedEnv &) = delete;
    [[nodiscard]] JNIEnv *env() const { return env_; }

  private:
    JNIEnv *env_ = nullptr;
    bool attached_ = false;
};

} // namespace miracle::bridge
