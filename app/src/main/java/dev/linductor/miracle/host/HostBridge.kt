package dev.linductor.miracle.host

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeoutException

/**
 * native 宿主桥门面：双向 JNI 的 Kotlin 端。
 *
 * native → Kotlin（低频控制面）：[requestFrame]、[topologyJson]、[permissionState]、
 * [dispatchInput]、[cancelInput]、[inputState]。
 * Kotlin → native：[nativeCompleteFrame]（帧完成）、[nativeCompleteInput]（输入完成）、
 * [nativeNotifyEpochChanged]（旋转/投影/无障碍失效）、[environmentSelfTest]（P1）、
 * [inputContractProbe]/[inputTestOpen]/[inputTestDispatch]/[inputTestInterrupt]/
 * [inputTestClose]（P2 自检）。
 *
 * 本对象不承载业务状态机；frames 流仅供 UI 预览（权威链路是 mira EventStore）。
 */
object HostBridge {

    /** 单帧拷贝结果（RGBA8888，行已紧凑）。 */
    data class CapturedFrame(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val pixels: ByteArray,
        val beginNs: Long,
        val endNs: Long,
    )

    /** dispatchInput 受理结果（native 快速失败区分错误码用）。 */
    private const val DISPATCH_ACCEPTED = 0
    private const val DISPATCH_ACCESSIBILITY_OFF = 1
    private const val DISPATCH_FAILED = 2

    @Volatile
    private var provider: ScreenCaptureProvider? = null

    @Volatile
    private var inputDispatcher: InputDispatcher? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var nativeReady = false

    private val _frames = MutableStateFlow<List<CapturedFrame>>(emptyList())

    /** 最近完成的帧（UI 预览用，最多保留 4 帧）。 */
    val frames: StateFlow<List<CapturedFrame>> = _frames.asStateFlow()

    /**
     * 加载 native 库（幂等）。无障碍服务可独立于 UI 启动（系统绑定先于任何
     * Activity），因此本门面不依赖 NativeBridge 的懒加载路径，自持加载守卫。
     * 返回 false＝库不可用（加载失败），调用方按 fail-closed 处理，不抛出。
     */
    fun ensureNative(): Boolean {
        if (nativeReady) {
            return true
        }
        return try {
            System.loadLibrary("miracle_host")
            nativeReady = true
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /** 绑定宿主能力提供者（由 AgentForegroundService 调用）。 */
    fun bind(value: ScreenCaptureProvider) {
        provider = value
        value.setEpochListener { nativeNotifyEpochChanged() }
    }

    /** 解除绑定；提供者的释放由其所有者（服务）负责。 */
    fun unbind() {
        provider = null
        _frames.value = emptyList()
    }

    /** 绑定输入提供者（由 MiracleAccessibilityService 调用）。 */
    fun bindInput(dispatcher: InputDispatcher, context: Context) {
        inputDispatcher = dispatcher
        appContext = context.applicationContext
    }

    /** 解除输入绑定（服务断开/销毁时调用）。 */
    fun unbindInput() {
        inputDispatcher = null
    }

    /** 能力变化通知（epoch 递增 + on_capabilities_changed 广播，native 侧合并）。 */
    fun notifyHostCapabilitiesChanged() {
        if (!ensureNative()) {
            android.util.Log.w("miracle/hostbridge", "capabilities changed: native unavailable")
            return
        }
        nativeNotifyEpochChanged()
    }

    /** native 调用：受理一次截屏请求。立即返回，完成经 [nativeCompleteFrame] 回流。 */
    @JvmStatic
    fun requestFrame(correlation: Long, deadlineNs: Long): Boolean {
        val current = provider
        if (current == null) {
            android.util.Log.w("miracle/hostbridge", "requestFrame: provider not bound")
            return false
        }
        return try {
            current.requestFrame(correlation, deadlineNs) { result ->
                complete(correlation, result)
            }
            true
        } catch (error: Throwable) {
            android.util.Log.w("miracle/hostbridge", "requestFrame dispatch failed", error)
            false
        }
    }

    /**
     * native 调用：受理一次输入派发（UTF-8 JSON 事件数组，结构见
     * [HostAbiInput.parseEvents]）。立即返回，完成经 [nativeCompleteInput] 回流。
     *
     * deadlineNs 约定：0＝无超时；负值＝已过期；正值＝剩余时长（ns）。
     */
    @JvmStatic
    fun dispatchInput(correlation: Long, deadlineNs: Long, eventsJson: ByteArray): Int {
        val json = try {
            eventsJson.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            return DISPATCH_FAILED
        }
        val events = HostAbiInput.parseEvents(json)
        if (events == null) {
            android.util.Log.w("miracle/hostbridge", "dispatchInput: invalid events payload")
            return DISPATCH_FAILED
        }
        val dispatcher = inputDispatcher
        if (dispatcher == null) {
            return if (MiracleAccessibilityService.isConnected()) {
                DISPATCH_FAILED // 服务在但派发器未就绪（瞬态）
            } else {
                DISPATCH_ACCESSIBILITY_OFF
            }
        }
        return try {
            if (dispatcher.dispatch(correlation, events, deadlineNs) { completion ->
                    nativeCompleteInput(
                        correlation, completion.status, completion.receipt,
                        if (completion.sideEffect) 1 else 0,
                    )
                }
            ) {
                DISPATCH_ACCEPTED
            } else {
                DISPATCH_FAILED
            }
        } catch (error: Throwable) {
            android.util.Log.w("miracle/hostbridge", "dispatchInput failed", error)
            DISPATCH_FAILED
        }
    }

    /** native 调用：协作取消一次输入派发（fire-and-forget）。 */
    @JvmStatic
    fun cancelInput(correlation: Long) {
        try {
            inputDispatcher?.cancel(correlation)
        } catch (error: Throwable) {
            android.util.Log.w("miracle/hostbridge", "cancelInput failed", error)
        }
    }

    /** native 调用：0=就绪 1=无障碍未启用 2=瞬态未就绪。 */
    @JvmStatic
    fun inputState(): Int = when {
        inputDispatcher != null -> 0
        MiracleAccessibilityService.isConnected() -> 2
        else -> 1
    }

    /** native 调用：显示拓扑（无投影会话经 [DisplayGeometry] 兜底，cap 为 0）。 */
    @JvmStatic
    fun topologyJson(): String {
        provider?.let { return it.topologyJson() }
        val context = appContext ?: return "{}"
        return try {
            DisplayGeometry.topologyJson(context)
        } catch (error: Throwable) {
            android.util.Log.w("miracle/hostbridge", "topologyJson fallback failed", error)
            "{}"
        }
    }

    /** native 调用：0=granted 1=revoked 2=unknown。 */
    @JvmStatic
    fun permissionState(): Int {
        val current = provider ?: return 2
        return if (current.isActive) 0 else 1
    }

    // ---- P3：模型传输 / 闭环事件 / R3 准入（native 下行 → AgentRuntime 门面） ----

    /** native 调用：传输就绪（loopOpen(kotlin)/连通性自检前置）。 */
    @JvmStatic
    fun transportReady(): Boolean = dev.linductor.miracle.runtime.AgentRuntime.transportReady()

    /** native 调用：受理一次 HTTPS 交换（异步；完成经 [nativeHttpExchangeComplete] 回流）。 */
    @JvmStatic
    fun httpExchangeStart(exchangeId: Long, requestJson: String, body: ByteArray): Int =
        dev.linductor.miracle.runtime.AgentRuntime.httpExchangeStart(exchangeId, requestJson, body)

    /** native 调用：协作取消一次交换（disconnect）。 */
    @JvmStatic
    fun httpExchangeCancel(exchangeId: Long) =
        dev.linductor.miracle.runtime.AgentRuntime.httpExchangeCancel(exchangeId)

    /** native 上投：闭环事件（相位/终态/确认请求与结算/会话状态）。 */
    @JvmStatic
    fun onLoopEvent(wrappedJson: String) {
        // 真机取证证据流（P2 实践：Compose 语义树对 uiautomator 陈旧，以 logcat JSON
        // 为准）；payload 均为 safe 级内容（无凭据/无 type 文本）。
        android.util.Log.i("miracle/loop", wrappedJson)
        dev.linductor.miracle.runtime.AgentRuntime.handleNativeEvent(wrappedJson)
    }

    /** native 调用：R3 准入询问（0=放行 1=需确认 2=拒绝）。 */
    @JvmStatic
    fun consentCheckInput(eventsJson: String): Int =
        dev.linductor.miracle.runtime.AgentRuntime.consentCheck(eventsJson)

    private fun complete(correlation: Long, result: Result<CapturedFrame>) {
        result.onSuccess { frame ->
            android.util.Log.i(
                "miracle/hostbridge",
                "frame ${frame.width}x${frame.height} rot=${frame.rotation} " +
                    "${frame.pixels.size}B in ${(frame.endNs - frame.beginNs) / 1_000_000}ms",
            )
            _frames.value = (_frames.value + frame).takeLast(4)
            nativeCompleteFrame(
                correlation, 1, frame.width, frame.height, frame.rotation,
                frame.pixels, frame.beginNs, frame.endNs, 0,
            )
        }.onFailure { error ->
            android.util.Log.w(
                "miracle/hostbridge",
                "frame failed for correlation $correlation: $error",
            )
            nativeCompleteFrame(correlation, 0, 0, 0, 0, ByteArray(0), 0, 0, mapError(error))
        }
    }

    // MiraHostStatus：3=UNAVAILABLE 4=PERMISSION_DENIED 6=DEADLINE_EXCEEDED
    private fun mapError(error: Throwable): Int = when (error) {
        is TimeoutException -> 6
        is SecurityException -> 4
        else -> 3
    }

    @JvmStatic
    external fun nativeCompleteFrame(
        correlation: Long,
        ok: Int,
        width: Int,
        height: Int,
        rotation: Int,
        pixels: ByteArray,
        beginNs: Long,
        endNs: Long,
        errCode: Int,
    )

    @JvmStatic
    external fun nativeCompleteInput(
        correlation: Long,
        status: Int,
        receipt: Int,
        sideEffect: Int,
    )

    @JvmStatic
    external fun nativeNotifyEpochChanged()

    /** P1 环境自检：Executor + AndroidHostAdapter + observe×2 + 干净关闭。 */
    @JvmStatic
    external fun environmentSelfTest(): String

    /** P2 直接 ABI 契约探针（独立 host 实例；非法参数/过期 deadline/RELEASE_ALL/中途取消）。 */
    @JvmStatic
    external fun inputContractProbe(tapX: Double, tapY: Double): String

    /** P2 adapter 会话：打开（Executor + AndroidHostAdapter）。1=ok 其余为错误码。 */
    @JvmStatic
    external fun inputTestOpen(): Int

    /** P2 adapter 会话：单步派发（kind 见 HostAbiInput；坐标为 [0,1] 规范值）。 */
    @JvmStatic
    external fun inputTestDispatch(
        kind: Int,
        x: Double,
        y: Double,
        x2: Double,
        y2: Double,
        text: String,
        durationMs: Int,
        timeoutMs: Int,
    ): String

    /** P2 adapter 会话：interrupt()（协作取消全部在途操作）。 */
    @JvmStatic
    external fun inputTestInterrupt(): Int

    /** P2 adapter 会话：关闭（adapter 销毁 + executor shutdown，返回统计 JSON）。 */
    @JvmStatic
    external fun inputTestClose(): String

    // ---- P3：闭环运行时与模型连通性（阻塞 JNI；调用方保证非主线程） ----

    /** 打开闭环运行时（config 见 AgentRuntime/ModelConfig.toNativeJson）。1=ok 负数=错误码。 */
    @JvmStatic
    external fun loopOpen(configJson: String): Int

    /** 提交目标（max_steps<=0 用配置默认）。1=ok 负数=错误码。 */
    @JvmStatic
    external fun loopSubmit(goal: String, maxSteps: Int): Int

    /** 协作取消当前任务。 */
    @JvmStatic
    external fun loopCancel(): Int

    /** Human Takeover（阻断新决策 + RELEASE_ALL + 确认失效）。 */
    @JvmStatic
    external fun loopTakeover(): Int

    /** 关闭运行时，返回统计 JSON。 */
    @JvmStatic
    external fun loopClose(): String

    /** 当前状态 JSON。 */
    @JvmStatic
    external fun loopState(): String

    /** 模型连通性自检（阻塞；文本-only 决策请求），返回结果 JSON。 */
    @JvmStatic
    external fun modelConnectivityTest(configJson: String): String

    /** R3 确认回流：0=放行 1=拒绝 2=未找到 -3=校验失败 -4=派发失败。 */
    @JvmStatic
    external fun consentResolve(challenge: String, nonce: String, approve: Boolean): Int

    /** native 传输完成回流（Kotlin HttpTransportBinding → native）。 */
    @JvmStatic
    external fun nativeHttpExchangeComplete(
        exchangeId: Long,
        status: Int,
        headersJson: String,
        body: ByteArray,
    )
}
