package com.samosaai.masala

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

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
    }

    @Deprecated("Back does nothing on the home screen")
    @Suppress("MissingSuperCall")
    override fun onBackPressed() = Unit
}
