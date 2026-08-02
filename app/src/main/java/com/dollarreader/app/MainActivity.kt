package com.dollarreader.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dollarreader.app.data.LibraryBackupRestoreService
import com.dollarreader.app.ui.reader.VolumeChapterDirection
import com.dollarreader.app.ui.reader.VolumeKeyChapterNavigator

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LibraryBackupRestoreService.applyPendingRestore(applicationContext)
        enableEdgeToEdge()
        setContent {
            DollarReaderApp()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> VolumeChapterDirection.PREVIOUS
            KeyEvent.KEYCODE_VOLUME_DOWN -> VolumeChapterDirection.NEXT
            else -> null
        }
        if (direction != null && VolumeKeyChapterNavigator.isActive) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                VolumeKeyChapterNavigator.dispatch(direction)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
