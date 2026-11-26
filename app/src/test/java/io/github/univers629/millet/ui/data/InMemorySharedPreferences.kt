package io.github.univers629.millet.ui.data

import android.content.SharedPreferences
import java.util.Collections
import java.util.WeakHashMap

/**
 * 纯 JVM 内存级 [SharedPreferences] 测试替身：支持强/弱监听器语义与防御性集合拷贝。
 */
class InMemorySharedPreferences : SharedPreferences {

    private val values = LinkedHashMap<String, Any?>()
    private val listeners = Collections.newSetFromMap(
        WeakHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Boolean>()
    )

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(key: String?, defValue: String?): String? =
        (values[key] as? String) ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        val raw = values[key] as? Set<String> ?: return defValues
        return LinkedHashSet(raw)
    }

    override fun getInt(key: String?, defValue: Int): Int =
        (values[key] as? Int) ?: defValue

    override fun getLong(key: String?, defValue: Long): Long =
        (values[key] as? Long) ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        (values[key] as? Float) ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        (values[key] as? Boolean) ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        if (listener != null) listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        if (listener != null) listeners.remove(listener)
    }

    private inner class EditorImpl : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private var clearFirst = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = values?.let { LinkedHashSet(it) }
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = REMOVE_SENTINEL
        }

        override fun clear(): SharedPreferences.Editor = apply {
            clearFirst = true
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            val changedKeys = LinkedHashSet<String>()
            if (clearFirst) {
                changedKeys.addAll(values.keys)
                values.clear()
            }
            for ((k, v) in pending) {
                if (v === REMOVE_SENTINEL || v == null) {
                    if (values.remove(k) != null) changedKeys.add(k)
                } else if (values[k] != v) {
                    values[k] = v
                    changedKeys.add(k)
                }
            }
            val snapshotListeners = listeners.toList()
            for (k in changedKeys) {
                for (listener in snapshotListeners) {
                    listener.onSharedPreferenceChanged(this@InMemorySharedPreferences, k)
                }
            }
        }
    }

    private companion object {
        val REMOVE_SENTINEL = Any()
    }
}
