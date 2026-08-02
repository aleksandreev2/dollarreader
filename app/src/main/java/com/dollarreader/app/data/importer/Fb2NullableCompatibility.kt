package com.dollarreader.app.data.importer

internal fun String?.normalizeText(): String =
    this.orEmpty().replace(Regex("""\s+"""), " ").trim()

internal val Any?.hasChildren: Boolean
    get() {
        val value = this ?: return false
        return runCatching {
            value.javaClass.getDeclaredField("hasChildren").apply { isAccessible = true }
                .getBoolean(value)
        }.getOrDefault(false)
    }
