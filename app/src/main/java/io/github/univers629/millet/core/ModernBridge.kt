package io.github.univers629.millet.core

import io.github.libxposed.api.XposedModule

/**
 * Modern LibXposed (API 102) 全局上下文持有器。
 */
object ModernBridge {
    @Volatile
    var xposedModule: XposedModule? = null

    val isModernActive: Boolean
        get() = xposedModule != null

    val frameworkApiVersion: Int
        get() = xposedModule?.apiVersion ?: 102

    val frameworkName: String
        get() = xposedModule?.frameworkName ?: ""

    val frameworkVersion: String
        get() = xposedModule?.frameworkVersion ?: ""
}

