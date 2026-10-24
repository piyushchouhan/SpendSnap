package com.spendsnap

import android.app.Application
import android.content.Context
import android.net.Uri
import com.spendsnap.ai.AiEngineKind
import com.spendsnap.ai.AiInsights
import com.spendsnap.ai.GeminiClient
import com.spendsnap.ai.GeminiNanoEngine
import com.spendsnap.data.AppDatabase
import com.spendsnap.data.TransactionRepository
import com.spendsnap.domain.PaymentApp
import com.spendsnap.ocr.OcrProcessor
import kotlinx.coroutines.flow.MutableStateFlow

class SpendSnapApp : Application() {
    val container by lazy { AppContainer(this) }
}

val Context.container: AppContainer get() = (applicationContext as SpendSnapApp).container

class AppContainer(context: Context) {
    val repository = TransactionRepository(AppDatabase.create(context))
    val settings = AppSettings(context)
    val ocr by lazy { OcrProcessor(context) }
    val nano by lazy { GeminiNanoEngine() }
    val cloud by lazy { GeminiClient(BuildConfig.GEMINI_API_KEY, BuildConfig.GEMINI_MODEL) }
    val ai by lazy { AiInsights(repository, settings) { if (settings.aiEngine.value == AiEngineKind.ON_DEVICE) nano else cloud } }

    /** Screenshot shared to the app (or picked in-app), waiting to be OCR'd. Never copied or stored. */
    val pendingScreenshot = MutableStateFlow<Uri?>(null)

    /** Entry to open, set when the user taps a capture notification. */
    val openTransaction = MutableStateFlow<Long?>(null)
}

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun flag(key: String, default: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getBoolean(key, default)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) =
            prefs.edit().putBoolean(key, value).apply()
    }

    /** Phase-0 diagnostics: dumps payment-app screen text to logcat (tag SpendSnapDump). Off by default. */
    var diagnosticsEnabled by flag("diagnostics", false)
    var googlePayEnabled by flag("detect_gpay", true)
    var phonePeEnabled by flag("detect_phonepe", true)

    /** When on, automatically detected payments are not counted until the user checks them. */
    var reviewBeforeSaving by flag("review_before_saving", false)

    /** Capture notifications (design 09). The Android permission is still required. */
    var paymentAlerts by flag("payment_alerts", true)

    fun detects(app: PaymentApp): Boolean = when (app) {
        PaymentApp.GOOGLE_PAY -> googlePayEnabled
        PaymentApp.PHONEPE -> phonePeEnabled
        PaymentApp.CASH, PaymentApp.OTHER -> false
    }

    /**
     * AI Insights sends limited data to Google's Gemini API, so it is off until the user turns it on.
     * Observable so the Insights tab and Settings stay in sync.
     */
    val aiEnabled = MutableStateFlow(prefs.getBoolean("ai_enabled", false))

    fun setAiEnabled(on: Boolean) {
        aiEnabled.value = on
        prefs.edit().putBoolean("ai_enabled", on).apply()
    }

    var autoCategorize by flag("ai_categorize", true)

    /** On-device (Gemini Nano) by default: nothing leaves the phone unless the user picks Cloud. */
    val aiEngine = MutableStateFlow(AiEngineKind.entries.firstOrNull { it.name == prefs.getString("ai_engine", null) } ?: AiEngineKind.ON_DEVICE)

    fun setAiEngine(kind: AiEngineKind) {
        aiEngine.value = kind
        prefs.edit().putString("ai_engine", kind.name).apply()
    }

    private fun text(key: String) = object : kotlin.properties.ReadWriteProperty<Any?, String?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getString(key, null)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String?) =
            prefs.edit().putString(key, value).apply()
    }

    /** Last Gemini summary and the facts it was written from, so reopening the tab doesn't call the API. */
    var summaryText by text("ai_summary_text")
    var summaryKey by text("ai_summary_key")
    var planText by text("ai_plan_text")
    var planKey by text("ai_plan_key")

    var dismissedTips: Set<String>
        get() = prefs.getStringSet("ai_dismissed_tips", emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet("ai_dismissed_tips", value).apply()

    val theme = MutableStateFlow(ThemeMode.entries.firstOrNull { it.name == prefs.getString("theme", null) } ?: ThemeMode.SYSTEM)

    fun setTheme(mode: ThemeMode) {
        theme.value = mode
        prefs.edit().putString("theme", mode.name).apply()
    }
}
