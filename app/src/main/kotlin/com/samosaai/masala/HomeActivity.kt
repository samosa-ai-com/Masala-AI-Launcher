package com.samosaai.masala

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import android.window.OnBackInvokedDispatcher

/** Placeholder home screen. Phase 1 replaces it with the Compose launcher. */
class HomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = getString(R.string.app_name)
                textSize = 32f
                gravity = Gravity.CENTER
            },
        )
        // With targetSdk 36, predictive back is on and onBackPressed is no longer called.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {}
        }
    }

    // Back does nothing on the home screen. Only called below API 33; the callback above covers the rest.
    @Deprecated("Back does nothing on the home screen")
    @SuppressLint("GestureBackNavigation", "MissingSuperCall")
    override fun onBackPressed() = Unit
}
