package com.spendsnap

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import com.spendsnap.detection.CaptureNotifier
import com.spendsnap.ui.SpendSnapRoot
import com.spendsnap.ui.theme.SpendSnapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent { SpendSnapTheme { SpendSnapRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getLongExtra(CaptureNotifier.EXTRA_OPEN_TRANSACTION, 0L)?.takeIf { it > 0 }?.let {
            container.openTransaction.value = it
            return
        }
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let {
            container.pendingScreenshot.value = it
        }
    }
}
