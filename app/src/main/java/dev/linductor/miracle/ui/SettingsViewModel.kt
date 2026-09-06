package dev.linductor.miracle.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.linductor.miracle.settings.ModelConfig
import dev.linductor.miracle.settings.ModelConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页 ViewModel：模型配置（端点/前缀/模型/方言/步数/密钥）持久化。
 * API key 只以 Keystore 密文落盘；加载后仅在内存/输入框暂存。
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val config: ModelConfig = ModelConfig(),
        val apiKeyInput: String = "",
        val saved: Boolean = false,
        val error: String? = null,
        val disclosureAccepted: Boolean = false,
    )

    private val store = ModelConfigStore(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val config = withContext(Dispatchers.IO) { store.load() }
            _state.value = _state.value.copy(
                config = config,
                disclosureAccepted = store.disclosureAccepted(),
                saved = false,
                error = null,
            )
        }
    }

    fun update(transform: (ModelConfig) -> ModelConfig) {
        _state.value = _state.value.copy(config = transform(_state.value.config), saved = false)
    }

    fun onApiKeyInput(value: String) {
        _state.value = _state.value.copy(apiKeyInput = value, saved = false)
    }

    /** 套用提供商预设（端点/前缀/方言/模型建议值）；API key 输入不受影响。 */
    fun applyPreset(preset: dev.linductor.miracle.settings.ProviderPreset) {
        update(preset::applyTo)
    }

    /** 保存；apiKeyInput 留空＝保留既有密钥。 */
    fun save() {
        val current = _state.value
        val config = current.config
        if (!config.httpsValid) {
            _state.value = current.copy(error = "端点必须以 https:// 开头")
            return
        }
        if (config.model.isBlank()) {
            _state.value = current.copy(error = "模型名不能为空")
            return
        }
        if (!current.config.hasApiKey && current.apiKeyInput.isBlank()) {
            _state.value = current.copy(error = "API key 不能为空（首次配置）")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val keyToStore = current.apiKeyInput.ifBlank { null }
                store.save(config, keyToStore)
                val reloaded = store.load()
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    _state.value = UiState(
                        config = reloaded,
                        apiKeyInput = "",
                        saved = true,
                        error = null,
                        disclosureAccepted = store.disclosureAccepted(),
                    )
                }
            } catch (error: Exception) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(
                        saved = false,
                        error = "保存失败：${error.message ?: "Keystore 异常"}",
                    )
                }
            }
        }
    }

    /** 接受首启披露（一次性；可重置复验引导）。 */
    fun acceptDisclosure() {
        store.setDisclosureAccepted(true)
        _state.value = _state.value.copy(disclosureAccepted = true)
    }

    fun resetDisclosure() {
        store.setDisclosureAccepted(false)
        _state.value = _state.value.copy(disclosureAccepted = false)
    }
}
