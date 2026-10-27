package com.samosaai.masala.i18n

import com.samosaai.masala.R

/** From the private prototype's `Language.nameRes` (LanguageScreen.kt): the language's name as the user reads it. */
val Language.nameRes: Int
    get() = when (this) {
        Language.ENGLISH -> R.string.language_english
        Language.SPANISH -> R.string.language_spanish
        Language.FRENCH -> R.string.language_french
        Language.GERMAN -> R.string.language_german
        Language.HINDI -> R.string.language_hindi
        Language.JAPANESE -> R.string.language_japanese
        Language.CHINESE -> R.string.language_chinese
        Language.ITALIAN -> R.string.language_italian
        Language.PORTUGUESE -> R.string.language_portuguese
        Language.RUSSIAN -> R.string.language_russian
    }
