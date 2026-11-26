package io.github.univers629.millet.ui.vm

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.univers629.millet.core.CloudListCodec
import io.github.univers629.millet.core.PrefsKeys
import io.github.univers629.millet.ui.data.PolicyRepository
import io.github.univers629.millet.ui.data.RuntimeAndAppsRepository
import io.github.univers629.millet.ui.screen.InstalledAppUiModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class CloudWhitelistUiState(
    val cloudLists: CloudListCodec.CloudLists? = null,
    val apps: List<InstalledAppUiModel> = emptyList(),
    val blockCloudDoze: Boolean = false,
    val blockCloudFrozen: Boolean = true,
    val isRefreshing: Boolean = false,
)

/** 官方云白名单子页面 ViewModel。 */
class CloudWhitelistViewModel(
    private val policyRepo: PolicyRepository,
    private val runtimeRepo: RuntimeAndAppsRepository,
) : ViewModel() {

    val uiState: StateFlow<CloudWhitelistUiState> = combine(
        runtimeRepo.effectiveCloudListsFlow,
        runtimeRepo.installedAppsFlow,
        policyRepo.prefsFlow,
        runtimeRepo.isAppsRefreshingFlow,
        runtimeRepo.isProcessesRefreshingFlow,
    ) { cloudLists, apps, prefs, appsRefreshing, procsRefreshing ->
        CloudWhitelistUiState(
            cloudLists = cloudLists,
            apps = apps,
            blockCloudDoze = prefs.blockCloudDozeWhitelist,
            blockCloudFrozen = prefs.blockCloudFrozenWhitelist,
            isRefreshing = appsRefreshing || procsRefreshing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = CloudWhitelistUiState(
            cloudLists = runtimeRepo.effectiveCloudListsFlow.value,
            apps = runtimeRepo.installedAppsFlow.value,
            blockCloudDoze = policyRepo.prefsFlow.value.blockCloudDozeWhitelist,
            blockCloudFrozen = policyRepo.prefsFlow.value.blockCloudFrozenWhitelist,
        ),
    )

    fun setCloudDozeEnabled(enabled: Boolean) {
        policyRepo.setBoolean(PrefsKeys.BLOCK_CLOUD_DOZE_WHITELIST, !enabled)
    }

    fun setCloudFrozenEnabled(enabled: Boolean) {
        policyRepo.setBoolean(PrefsKeys.BLOCK_CLOUD_FROZEN_WHITELIST, !enabled)
    }

    fun refresh() {
        viewModelScope.launch {
            runtimeRepo.refreshRuntimeAndProcesses(visible = true)
            runtimeRepo.refreshInstalledPackages(force = true, visible = true)
        }
    }
}
