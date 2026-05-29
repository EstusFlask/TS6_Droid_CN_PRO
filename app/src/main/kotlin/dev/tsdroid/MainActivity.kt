package dev.tsdroid

import android.os.Bundle
import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.tsdroid.data.SettingsStore
import dev.tsdroid.viewmodel.ConnectionViewModel
import dev.tsdroid.ui.theme.TsDroidTheme
import dev.tsdroid.ui.screen.AppNavigation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    private val connectionViewModel: ConnectionViewModel by viewModels()
    private val TAG = "MainActivity"

    override fun attachBaseContext(newBase: Context) {
        val languageTag = runBlocking(Dispatchers.IO) {
            SettingsStore(newBase).language.first()
        }
        val locale = java.util.Locale.forLanguageTag(languageTag)
        java.util.Locale.setDefault(locale)
        val config = newBase.resources.configuration
        config.setLocale(locale)
        config.setLocales(android.os.LocaleList(locale))
        val updatedContext = newBase.createConfigurationContext(config)
        super.attachBaseContext(updatedContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TsDroidTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: hiding floating window")
        connectionViewModel.hideFloatingWindow()
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: showing floating window")
        if (!isChangingConfigurations) {
            connectionViewModel.showFloatingWindow()
        }
    }
}
