package dev.linductor.miracle.runtime

/**
 * native 桥入口门面。UI 层只经 [RuntimeSmoke]/ViewModel 使用本对象，不直接触碰 JNI 细节
 * （架构设计 RULE-03：UI 只经门面访问运行时）。
 *
 * 加载失败（架构/ABI 不匹配、库缺失）不抛出：以 [Availability] 显式表达，
 * 由 UI 呈现为明确降级状态。
 */
object NativeBridge {

    /** native 库加载状态。 */
    enum class Availability { Available, Unavailable }

    @Volatile
    private var availability: Availability? = null

    fun availability(): Availability {
        return availability ?: run {
            val loaded = try {
                System.loadLibrary("miracle_host")
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            }
            val value = if (loaded) Availability.Available else Availability.Unavailable
            availability = value
            value
        }
    }

    /** 运行 RuntimeBaseline 自检；native 不可用时抛出 [IllegalStateException]。 */
    fun selfTestJson(): String {
        check(availability() == Availability.Available) {
            "libmiracle_host.so 不可用（加载失败），无法执行自检"
        }
        return runtimeSelfTest()
    }

    private external fun runtimeSelfTest(): String
}
