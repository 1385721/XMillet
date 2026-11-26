package io.github.univers629.millet.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

class NavEntryViewModelStoreTest {

    private class SampleSubPageViewModel(val label: String) : ViewModel() {
        var cleared: Boolean = false
            private set

        override fun onCleared() {
            cleared = true
            super.onCleared()
        }
    }

    private fun obtain(store: ViewModelStore, label: String): SampleSubPageViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
                return SampleSubPageViewModel(label) as T
            }
        }
        return ViewModelProvider(store, factory)[SampleSubPageViewModel::class.java]
    }

    @Test
    fun distinctEntriesStayIsolatedAndPoppingTriggersOnCleared() {
        val registry = NavEntryStoreRegistry()
        val mainKey = "main"
        val wechatKey = "com.tencent.mm" to 10123
        val qqKey = "com.tencent.mobileqq" to 10124

        val wechatVm = obtain(registry.getOrCreateStore(wechatKey), "微信")
        val qqVm = obtain(registry.getOrCreateStore(qqKey), "QQ")

        assertNotSame(wechatVm, qqVm)
        assertEquals("微信", wechatVm.label)
        assertEquals("QQ", qqVm.label)
        assertEquals(2, registry.activeStoreCount())

        // 模拟配置变更后保留当前栈内条目：ViewModel 实例与状态均保留
        registry.retainOnly(setOf(mainKey, wechatKey, qqKey))
        assertSame(wechatVm, obtain(registry.getOrCreateStore(wechatKey), "unused"))
        assertFalse(wechatVm.cleared)
        assertFalse(qqVm.cleared)

        // 模拟 QQ 详情页出栈：仅 QQ 的 ViewModel 立即被清理
        registry.retainOnly(setOf(mainKey, wechatKey))
        assertFalse(wechatVm.cleared)
        assertTrue(qqVm.cleared)
        assertEquals(1, registry.activeStoreCount())

        // 宿主销毁时全部清理
        val clearRegistry = NavEntryStoreRegistry::class.java.getDeclaredMethod("onCleared")
        clearRegistry.isAccessible = true
        clearRegistry.invoke(registry)
        assertTrue(wechatVm.cleared)
        assertEquals(0, registry.activeStoreCount())
    }
}
