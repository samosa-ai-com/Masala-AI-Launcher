package com.samosaai.masala.permissions

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * A transparent, UI-less host for one permission dialog (see [PermissionGate]). The dialog is the
 * only thing the user sees; the activity finishes the moment they answer.
 */
class PermissionRequestActivity : ComponentActivity() {
    private val marker: String
        get() = intent.getStringExtra(PermissionGate.EXTRA_MARKER).orEmpty()

    private val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        PermissionGate.complete(marker, granted)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wanted = marker
        if (wanted.isEmpty()) {
            finish()
            return
        }
        runCatching { ask.launch(wanted) }.onFailure {
            // Nothing to show (a permission the platform refuses to ask for): say so and leave.
            Log.w("PermissionGate", "Can't ask for $wanted", it)
            PermissionGate.complete(wanted, false)
            finish()
        }
    }
}
