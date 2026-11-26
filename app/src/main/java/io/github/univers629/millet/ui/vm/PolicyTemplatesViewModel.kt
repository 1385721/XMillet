package io.github.univers629.millet.ui.vm

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.univers629.millet.core.PolicyFlag
import io.github.univers629.millet.core.PolicyTemplate
import io.github.univers629.millet.ui.data.PolicyRepository
import io.github.univers629.millet.ui.data.RuntimeAndAppsRepository
import io.github.univers629.millet.ui.screen.InstalledAppUiModel
import io.github.univers629.millet.ui.util.PolicyMembershipSnapshot
import io.github.univers629.millet.ui.util.PolicyTemplateApplier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class PolicyTemplatesUiState(
    val templates: List<PolicyTemplate> = emptyList(),
    val usageByTemplate: Map<String, Int> = emptyMap(),
)

/** 策略模板总览页 ViewModel。 */
class PolicyTemplatesViewModel(
    private val policyRepo: PolicyRepository,
    runtimeRepo: RuntimeAndAppsRepository,
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    val uiState: StateFlow<PolicyTemplatesUiState> = combine(
        policyRepo.templatesFlow,
        policyRepo.prefsFlow,
        runtimeRepo.installedAppsFlow,
    ) { templates, prefs, apps ->
        val snapshot = PolicyMembershipSnapshot.fromSnapshot(prefs)
        val flagsByApp = apps.mapNotNull { app ->
            snapshot.flagsOf(app).takeIf { it.isNotEmpty() }?.let { app to it }
        }
        val usage = templates.associate { template ->
            template.id to flagsByApp.count { (_, flags) -> template.matches(flags) }
        }
        PolicyTemplatesUiState(
            templates = templates,
            usageByTemplate = usage,
        )
    }
        .flowOn(computeDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = PolicyTemplatesUiState(templates = policyRepo.templatesFlow.value),
        )

    fun createCustomTemplate(name: String, flags: Set<PolicyFlag>): PolicyTemplate? =
        policyRepo.saveCustomTemplate(id = null, name = name, flags = flags)
}

@Immutable
data class PolicyTemplateDetailUiState(
    val template: PolicyTemplate? = null,
    val allTemplates: List<PolicyTemplate> = emptyList(),
    val apps: List<InstalledAppUiModel> = emptyList(),
    val snapshot: PolicyMembershipSnapshot = PolicyMembershipSnapshot(emptyMap()),
    val users: List<InstalledAppUiModel> = emptyList(),
)

/** 策略模板详情页 ViewModel（按 `templateId` 作用域化）。 */
class PolicyTemplateDetailViewModel(
    private val templateId: String,
    private val policyRepo: PolicyRepository,
    private val runtimeRepo: RuntimeAndAppsRepository,
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    val uiState: StateFlow<PolicyTemplateDetailUiState> = combine(
        policyRepo.templatesFlow,
        policyRepo.prefsFlow,
        runtimeRepo.installedAppsFlow,
    ) { allTemplates, prefs, apps ->
        val template = allTemplates.firstOrNull { it.id == templateId }
        val snapshot = PolicyMembershipSnapshot.fromSnapshot(prefs)
        val users = if (template == null) {
            emptyList()
        } else {
            apps.filter { PolicyTemplateApplier.isTargetable(it) && template.matches(snapshot.flagsOf(it)) }
                .sortedWith(compareBy<InstalledAppUiModel> { it.appName.lowercase() }.thenBy { it.isDualApp })
        }
        PolicyTemplateDetailUiState(
            template = template,
            allTemplates = allTemplates,
            apps = apps,
            snapshot = snapshot,
            users = users,
        )
    }
        .flowOn(computeDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = PolicyTemplateDetailUiState(
                template = policyRepo.templatesFlow.value.firstOrNull { it.id == templateId },
                allTemplates = policyRepo.templatesFlow.value,
                apps = runtimeRepo.installedAppsFlow.value,
                snapshot = PolicyMembershipSnapshot.fromSnapshot(policyRepo.prefsFlow.value),
            ),
        )

    fun applyTemplate(
        context: Context,
        targets: List<InstalledAppUiModel>,
        flags: Set<PolicyFlag>,
    ): PolicyTemplateApplier.Result {
        val appContext = context.applicationContext ?: context
        val result = PolicyTemplateApplier.apply(appContext, targets, flags) {
            policyRepo.refreshAllFromPrefs()
            viewModelScope.launch {
                runtimeRepo.refreshBatteryUnrestricted()
            }
        }
        policyRepo.refreshAllFromPrefs()
        return result
    }

    fun saveCustomTemplate(name: String, flags: Set<PolicyFlag>): PolicyTemplate? =
        policyRepo.saveCustomTemplate(id = templateId, name = name, flags = flags)

    fun deleteCustomTemplate() {
        policyRepo.deleteCustomTemplate(templateId)
    }
}
