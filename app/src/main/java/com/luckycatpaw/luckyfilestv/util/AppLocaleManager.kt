package com.luckycatpaw.luckyfilestv.util

import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object AppLocaleManager {

    const val ENGLISH = "en"
    const val GERMAN = "de"

    /**
     * Applies the app language.
     *
     * [AppCompatDelegate] forwards to the framework `LocaleManager` on API 33+ and backports the
     * behaviour down to API 21 below that, where it applies the locale to the running
     * [androidx.appcompat.app.AppCompatActivity] instances and persists it itself via the
     * `autoStoreLocales` service declared in the manifest. The framework `LocaleManager` alone
     * would leave every device below API 33 — a large share of the TV boxes in the field — without
     * any way to switch the language.
     *
     * Applying a language recreates the active activities, so this has to run on the main thread.
     */
    @MainThread
    fun apply(languageTag: String?) {
        val requestedLocales = when (languageTag) {
            ENGLISH, GERMAN -> LocaleListCompat.forLanguageTags(languageTag)
            else -> LocaleListCompat.getEmptyLocaleList()
        }

        if (AppCompatDelegate.getApplicationLocales() != requestedLocales) {
            AppCompatDelegate.setApplicationLocales(requestedLocales)
        }
    }
}
