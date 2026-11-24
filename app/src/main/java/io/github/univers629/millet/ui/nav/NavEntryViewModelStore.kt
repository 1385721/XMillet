package io.github.univers629.millet.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/**
 * 为 `miuix-nav` 的 `NavDisplay` 提供条目级 [ViewModelStore] 托管注册表。
 *
 * `miuix-nav` 默认不给每个返回栈条目分配独立的 [ViewModelStoreOwner]，直接调用 `viewModel()`
 * 会挂到宿主 `Activity` 上导致二级页串数据或退栈后内存泄漏。本注册表挂在宿主 `Activity` 的
 * `ViewModelStore` 中以跨配置变更/小窗切换存活，并在路由出栈时立即清理对应子存储。
 */
class NavEntryStoreRegistry : ViewModel() {

    private val stores = HashMap<Any, ViewModelStore>()

    fun getOrCreateStore(entryKey: Any): ViewModelStore =
        stores.getOrPut(entryKey) { ViewModelStore() }

    /** 仅保留当前仍在返回栈内的路由存储，已出栈条目立即触发 `onCleared()`。 */
    fun retainOnly(activeKeys: Set<Any>) {
        val iterator = stores.entries.iterator()
        while (iterator.hasNext()) {
            val (key, store) = iterator.next()
            if (key !in activeKeys) {
                store.clear()
                iterator.remove()
            }
        }
    }

    internal fun activeStoreCount(): Int = stores.size

    override fun onCleared() {
        for (store in stores.values) {
            store.clear()
        }
        stores.clear()
        super.onCleared()
    }
}

/** 同步清理已从返回栈弹出的条目级 [ViewModelStore]。 */
@Composable
fun SyncNavBackStackStores(
    registry: NavEntryStoreRegistry,
    activeKeys: Set<Any>,
) {
    LaunchedEffect(registry, activeKeys) {
        registry.retainOnly(activeKeys)
    }
}

/** 为单个 `NavDisplay` 条目提供独立的 [LocalViewModelStoreOwner] 作用域。 */
@Composable
fun ProvideNavEntryViewModelStore(
    registry: NavEntryStoreRegistry,
    entryKey: Any,
    content: @Composable () -> Unit,
) {
    val parentOwner = LocalViewModelStoreOwner.current
    val entryStore = remember(registry, entryKey) { registry.getOrCreateStore(entryKey) }
    val entryOwner = remember(entryStore, parentOwner) {
        NavEntryViewModelStoreOwner(entryStore, parentOwner)
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides entryOwner) {
        content()
    }
}

private class NavEntryViewModelStoreOwner(
    override val viewModelStore: ViewModelStore,
    private val parentOwner: ViewModelStoreOwner?,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = (parentOwner as? HasDefaultViewModelProviderFactory)?.defaultViewModelProviderFactory
            ?: ViewModelProvider.NewInstanceFactory()

    override val defaultViewModelCreationExtras: CreationExtras
        get() = (parentOwner as? HasDefaultViewModelProviderFactory)?.defaultViewModelCreationExtras
            ?: CreationExtras.Empty
}
