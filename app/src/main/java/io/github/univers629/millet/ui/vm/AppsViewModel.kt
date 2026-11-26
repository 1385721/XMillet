package io.github.univers629.millet.ui.vm

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.univers629.millet.core.CloudListCodec
import io.github.univers629.millet.core.PolicyTemplate
import io.github.univers629.millet.core.PrefsSnapshot
import io.github.univers629.millet.ui.data.PolicyRepository
import io.github.univers629.millet.ui.data.RuntimeAndAppsRepository
import io.github.univers629.millet.ui.screen.AppCategoryFilter
import io.github.univers629.millet.ui.screen.InstalledAppUiModel
import io.github.univers629.millet.ui.screen.PolicyHubSummary
import io.github.univers629.millet.ui.util.PolicyMembershipSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class AppsUiState(
    val filteredApps: List<InstalledAppUiModel> = emptyList(),
    val categoryCounts: List<Int> = List(AppCategoryFilter.entries.size) { 0 },
    val templateCount: Int = 0,
    val hubSummary: PolicyHubSummary = PolicyHubSummary(
        usageByTemplate = emptyMap(),
        appliedApps = 0,
        cloudInstalledCount = 0,
    ),
    val cloudLists: CloudListCodec.CloudLists? = null,
    val searchQuery: String = "",
    val selectedCategoryIndex: Int = 0,
    val showSystemApps: Boolean = false,
    val isRefreshing: Boolean = false,
)

/**
 * 应用策略页 ViewModel：在后台线程完成 7 大分类计数、搜索过滤与策略模板汇总计算。
 */
class AppsViewModel(
    policyRepo: PolicyRepository,
    private val runtimeRepo: RuntimeAndAppsRepository,
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val searchQueryFlow = MutableStateFlow("")
    private val selectedCategoryIndexFlow = MutableStateFlow(0)
    private val showSystemAppsFlow = MutableStateFlow(false)

    val uiState: StateFlow<AppsUiState> = combine(
        runtimeRepo.installedAppsFlow,
        policyRepo.prefsFlow,
        policyRepo.templatesFlow,
        runtimeRepo.effectiveCloudListsFlow,
        runtimeRepo.isAppsRefreshingFlow,
        searchQueryFlow,
        selectedCategoryIndexFlow,
        showSystemAppsFlow,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val apps = values[0] as List<InstalledAppUiModel>
        val prefs = values[1] as PrefsSnapshot
        @Suppress("UNCHECKED_CAST")
        val templates = values[2] as List<PolicyTemplate>
        val cloudLists = values[3] as CloudListCodec.CloudLists?
        val isRefreshing = values[4] as Boolean
        val searchQuery = values[5] as String
        val selectedCategoryIndex = values[6] as Int
        val showSystemApps = values[7] as Boolean

        val selectedCategory = AppCategoryFilter.entries.getOrElse(selectedCategoryIndex) { AppCategoryFilter.ALL }
        val q = searchQuery.trim().lowercase()
        val filteredApps = apps.filter { app ->
            val sysMatch = showSystemApps || !app.isSystem || app.hasAnyConfig
            val catMatch = selectedCategory.matches(app)
            val queryMatch = q.isEmpty() ||
                app.appName.lowercase().contains(q) ||
                app.packageName.lowercase().contains(q) ||
                app.uid.toString().contains(q)
            sysMatch && catMatch && queryMatch
        }
        val categoryCounts = AppCategoryFilter.entries.map { cat ->
            apps.count { app -> (showSystemApps || !app.isSystem || app.hasAnyConfig) && cat.matches(app) }
        }
        val hubSummary = computeHubSummary(prefs, apps, templates)
        AppsUiState(
            filteredApps = filteredApps,
            categoryCounts = categoryCounts,
            templateCount = templates.size,
            hubSummary = hubSummary,
            cloudLists = cloudLists,
            searchQuery = searchQuery,
            selectedCategoryIndex = selectedCategoryIndex,
            showSystemApps = showSystemApps,
            isRefreshing = isRefreshing,
        )
    }
        .flowOn(computeDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = AppsUiState(),
        )

    fun setSearchQuery(query: String) {
        searchQueryFlow.value = query
    }

    fun setSelectedCategoryIndex(index: Int) {
        selectedCategoryIndexFlow.value = index.coerceIn(0, AppCategoryFilter.entries.lastIndex)
    }

    fun toggleShowSystemApps() {
        showSystemAppsFlow.value = !showSystemAppsFlow.value
    }

    fun resetFilters() {
        searchQueryFlow.value = ""
        selectedCategoryIndexFlow.value = 0
    }

    fun refresh() {
        viewModelScope.launch {
            runtimeRepo.refreshInstalledPackages(force = true, visible = true)
        }
    }

    private fun computeHubSummary(
        prefs: PrefsSnapshot,
        apps: List<InstalledAppUiModel>,
        templates: List<PolicyTemplate>,
    ): PolicyHubSummary {
        val snapshot = PolicyMembershipSnapshot.fromSnapshot(prefs)
        val flagsByApp = apps.mapNotNull { app ->
            snapshot.flagsOf(app).takeIf { it.isNotEmpty() }?.let { app to it }
        }
        val usage = templates.associate { template ->
            template.id to flagsByApp.count { (_, flags) -> template.matches(flags) }
        }
        val applied = flagsByApp.count { (_, flags) -> templates.any { it.matches(flags) } }
        val cloudInstalled = apps.asSequence()
            .filter { it.inCloudDozeWhite || it.inCloudFrozenWhite || it.inCloudLaunchAllow }
            .map { it.packageName }
            .distinct()
            .count()
        return PolicyHubSummary(
            usageByTemplate = usage,
            appliedApps = applied,
            cloudInstalledCount = cloudInstalled,
        )
    }
}
