package com.dollarreader.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dollarreader.app.data.LibraryBackupRestoreService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LibraryBackupRestoreService.applyPendingRestore(applicationContext)
        enableEdgeToEdge()
        setContent {
            DollarReaderApp()
        }
    }
}
