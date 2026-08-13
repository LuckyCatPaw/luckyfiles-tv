package com.luckycatpaw.luckyfilestv.util

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList

object AppLocaleManager {

    const val ENGLISH = "en"
    const val GERMAN = "de"

    fun apply(context: Context, languageTag: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        val localeManager = context.getSystemService(LocaleManager::class.java)
        val requestedLocales = when (languageTag) {
            ENGLISH, GERMAN -> LocaleList.forLanguageTags(languageTag)
            else -> LocaleList.getEmptyLocaleList()
        }

        if (localeManager.applicationLocales != requestedLocales) {
            localeManager.applicationLocales = requestedLocales
        }
    }
}
