package io.github.univers629.millet.ui.vm

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.univers629.millet.core.PrefsSnapshot
import io.github.univers629.millet.ui.BackupRestoreHelper
import io.github.univers629.millet.ui.data.PolicyRepository
import io.github.univers629.millet.ui.data.RuntimeAndAppsRepository
import io.github.univers629.millet.ui.util.AppUpdateController
import io.github.univers629.millet.ui.util.AppUpdateStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class SettingsUiState(
    val prefs: PrefsSnapshot = PrefsSnapshot(),
    val updateStatus: AppUpdateStatus = AppUpdateStatus.Idle,
    val availableUpdateVersion: String? = null,
)

/**
 * 设置页 ViewModel：直连 [PolicyRepository.prefsFlow] 与 [AppUpdateController.uiState]，
 * 彻底取代局部拷贝状态与 `configVersion++`。
 */
class SettingsViewModel(
    private val policyRepo: PolicyRepository,
    private val runtimeRepo: RuntimeAndAppsRepository,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        policyRepo.prefsFlow,
        AppUpdateController.uiState,
    ) { prefs, update ->
        SettingsUiState(
            prefs = prefs,
            updateStatus = update.status,
            availableUpdateVersion = update.availableRelease?.versionName,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SettingsUiState(prefs = policyRepo.prefsFlow.value),
    )

    fun setBooleanPref(key: String, value: Boolean) {
        policyRepo.setBoolean(key, value)
    }

    fun setDetailedLogging(enabled: Boolean) {
        policyRepo.setDetailedLogging(enabled)
    }

    fun setLogLevel(level: String, syncIsLog: Boolean) {
        policyRepo.setLogLevel(level, syncIsLog)
    }

    fun importBackupJson(context: Context, json: String) {
        val appContext = context.applicationContext ?: context
        BackupRestoreHelper.importFromJson(appContext, json)
        policyRepo.refreshAllFromPrefs()
        viewModelScope.launch {
            runtimeRepo.refreshBatteryUnrestricted()
            runtimeRepo.refreshRuntimeAndProcesses(visible = false)
        }
    }

    fun resetToDefaults(context: Context) {
        val appContext = context.applicationContext ?: context
        BackupRestoreHelper.resetToDefaults(appContext)
        policyRepo.refreshAllFromPrefs()
        viewModelScope.launch {
            runtimeRepo.refreshBatteryUnrestricted()
            runtimeRepo.refreshRuntimeAndProcesses(visible = false)
        }
    }
}
