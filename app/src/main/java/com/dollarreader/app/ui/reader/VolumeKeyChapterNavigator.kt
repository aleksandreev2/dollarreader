package com.dollarreader.app.ui.reader

enum class VolumeChapterDirection {
    PREVIOUS,
    NEXT,
}

object VolumeKeyChapterNavigator {
    @Volatile
    private var activeHandler: ((VolumeChapterDirection) -> Unit)? = null

    val isActive: Boolean
        get() = activeHandler != null

    fun register(handler: (VolumeChapterDirection) -> Unit): () -> Unit {
        activeHandler = handler
        return {
            if (activeHandler === handler) {
                activeHandler = null
            }
        }
    }

    fun dispatch(direction: VolumeChapterDirection): Boolean {
        val handler = activeHandler ?: return false
        handler(direction)
        return true
    }
}
