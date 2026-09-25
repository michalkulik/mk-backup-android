package com.michalkulik.mkbackup.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.michalkulik.mkbackup.ui.theme.MkBackupTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MkBackupTheme {
                MkBackupApp(context = this)
            }
        }
    }
}
