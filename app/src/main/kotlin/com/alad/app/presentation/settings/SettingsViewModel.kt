package com.alad.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.alad.app.data.repository.UserPreferencesRepository
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: UserPreferencesRepository) : ViewModel() {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("SettingsViewModel", "Unhandled coroutine error", throwable)
    }

    private val _userApiKey = MutableStateFlow("")
    val userApiKey: StateFlow<String> = _userApiKey.asStateFlow()

    private val _volumeRatio = MutableStateFlow(1.0f)
    val volumeRatio: StateFlow<Float> = _volumeRatio.asStateFlow()

    private val _originalVolume = MutableStateFlow(0.30f)
    val originalVolume: StateFlow<Float> = _originalVolume.asStateFlow()

    private val _voicePersona = MutableStateFlow("Puck")
    val voicePersona: StateFlow<String> = _voicePersona.asStateFlow()

    private val _dubbingMode = MutableStateFlow(UserPreferencesRepository.MODE_HYBRID)
    val dubbingMode: StateFlow<String> = _dubbingMode.asStateFlow()

    private val _showDualSubtitles = MutableStateFlow(true)
    val showDualSubtitles: StateFlow<Boolean> = _showDualSubtitles.asStateFlow()

    // FIX22: dubbing provider (Gemini Live / Groq) + Groq settings.
    private val _dubbingProvider = MutableStateFlow(UserPreferencesRepository.PROVIDER_GEMINI)
    val dubbingProvider: StateFlow<String> = _dubbingProvider.asStateFlow()

    private val _groqApiKey = MutableStateFlow("")
    val groqApiKey: StateFlow<String> = _groqApiKey.asStateFlow()

    private val _deepseekApiKey = MutableStateFlow("")
    val deepseekApiKey: StateFlow<String> = _deepseekApiKey.asStateFlow()

    private val _groqTranslateModel = MutableStateFlow("fast")
    val groqTranslateModel: StateFlow<String> = _groqTranslateModel.asStateFlow()

    private val _groqVoiceGender = MutableStateFlow(UserPreferencesRepository.GROQ_GENDER_FEMALE)
    val groqVoiceGender: StateFlow<String> = _groqVoiceGender.asStateFlow()

    init {
        viewModelScope.launch(exceptionHandler) {
            try {
                _userApiKey.value = repository.apiKeyFlow.first()
                _volumeRatio.value = repository.volumeRatioFlow.first()
                _originalVolume.value = repository.originalVolumeFlow.first()
                _voicePersona.value = repository.voicePersonaFlow.first()
                _dubbingMode.value = repository.dubbingModeFlow.first()
                _showDualSubtitles.value = repository.showDualSubtitlesFlow.first()
                _dubbingProvider.value = repository.dubbingProviderFlow.first()
                _groqApiKey.value = repository.groqApiKeyFlow.first()
                _deepseekApiKey.value = repository.deepseekApiKeyFlow.first()
                _groqTranslateModel.value = repository.groqTranslateModelFlow.first()
                _groqVoiceGender.value = repository.groqVoiceGenderFlow.first()
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Failed to load preferences", e)
            }
        }
    }

    fun updateApiKey(key: String) {
        _userApiKey.value = key
        viewModelScope.launch(exceptionHandler) {
            repository.updateApiKey(key)
        }
    }

    fun updateVoicePersona(voice: String) {
        _voicePersona.value = voice
        viewModelScope.launch(exceptionHandler) {
            repository.updateVoicePersona(voice)
        }
    }

    fun updateDubbingMode(mode: String) {
        _dubbingMode.value = mode
        viewModelScope.launch(exceptionHandler) {
            repository.updateDubbingMode(mode)
        }
    }

    fun updateShowDualSubtitles(show: Boolean) {
        _showDualSubtitles.value = show
        viewModelScope.launch(exceptionHandler) {
            repository.updateShowDualSubtitles(show)
            com.alad.app.data.repository.DubbingSessionRepository.setShowDualSubtitles(show)
        }
    }

    fun updateDubbingProvider(provider: String) {
        _dubbingProvider.value = provider
        viewModelScope.launch(exceptionHandler) {
            repository.updateDubbingProvider(provider)
        }
    }

    fun updateGroqApiKey(key: String) {
        _groqApiKey.value = key
        viewModelScope.launch(exceptionHandler) {
            repository.updateGroqApiKey(key)
        }
    }

    fun updateDeepseekApiKey(key: String) {
        _deepseekApiKey.value = key
        viewModelScope.launch(exceptionHandler) {
            repository.updateDeepseekApiKey(key)
        }
    }

    fun updateGroqTranslateModel(model: String) {
        _groqTranslateModel.value = model
        viewModelScope.launch(exceptionHandler) {
            repository.updateGroqTranslateModel(model)
        }
    }

    fun updateGroqVoiceGender(gender: String) {
        _groqVoiceGender.value = gender
        viewModelScope.launch(exceptionHandler) {
            repository.updateGroqVoiceGender(gender)
        }
    }

    fun saveSettings() {
        viewModelScope.launch(exceptionHandler) {
            try {
                repository.updateApiKey(_userApiKey.value)
                repository.updateVoicePersona(_voicePersona.value)
                repository.updateDubbingMode(_dubbingMode.value)
                repository.updateShowDualSubtitles(_showDualSubtitles.value)
                repository.updateDubbingProvider(_dubbingProvider.value)
                repository.updateGroqApiKey(_groqApiKey.value)
                repository.updateDeepseekApiKey(_deepseekApiKey.value)
                repository.updateGroqTranslateModel(_groqTranslateModel.value)
                repository.updateGroqVoiceGender(_groqVoiceGender.value)
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "Failed to save preferences", e)
            }
        }
    }
}

class SettingsViewModelFactory(private val repository: UserPreferencesRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
