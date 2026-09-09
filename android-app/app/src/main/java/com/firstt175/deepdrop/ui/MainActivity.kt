package com.firstt175.deepdrop.ui

import android.content.Context
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.firstt175.deepdrop.prefs.AppLanguagePrefs
import com.firstt175.deepdrop.ui.theme.LsfgTheme

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguagePrefs.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LsfgTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val navController = rememberNavController()
                    LsfgNavHost(navController)
                }
            }
        }
    }

    /**
     * App-wide controller mapping so every screen gets Back/Select "for free"
     * without each Composable wiring its own key handler:
     *  - B (KEYCODE_BUTTON_B) → same as the system Back action.
     *  - A (KEYCODE_BUTTON_A) → forwarded as DPAD_CENTER, which is what
     *    Compose's focusable/clickable components already treat as "press",
     *    so a focused item can be activated with the controller.
     * Anything else (D-pad, L1/R1, ...) falls through untouched so screen-
     * level handlers (e.g. GameLauncherScreen's tab switch) still see it.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fromGamepad = event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        if (fromGamepad) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (event.action == KeyEvent.ACTION_UP) {
                        onBackPressedDispatcher.onBackPressed()
                    }
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_A -> {
                    return super.dispatchKeyEvent(
                        KeyEvent(event.action, KeyEvent.KEYCODE_DPAD_CENTER),
                    )
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
