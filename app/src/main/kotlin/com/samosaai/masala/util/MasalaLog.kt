package com.samosaai.masala.util

import android.util.Log
import com.samosaai.masala.BuildConfig

/**
 * From the private prototype's `the private prototypeLog`: `d` is a no-op in release builds. `BuildConfig.DEBUG` folds to a constant
 * `false` there, and the message is a lambda, so its string is never built either. Raw `Log.w` and
 * `Log.e` stay in use for failures worth reporting from the field.
 */
object MasalaLog {
    // inline: in release the whole call (lambda allocation included) is removed at the call site.
    inline fun d(tag: String, message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(tag, message())
    }
}
