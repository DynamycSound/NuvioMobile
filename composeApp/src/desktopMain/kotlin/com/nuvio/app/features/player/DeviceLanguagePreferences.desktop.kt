package com.nuvio.app.features.player

import java.util.Locale

internal actual object DeviceLanguagePreferences {
    actual fun preferredLanguageCodes(): List<String> {
        val languages = mutableListOf<String>()
        listOf(Locale.getDefault(Locale.Category.DISPLAY), Locale.getDefault()).forEach { locale ->
            languages += locale.toLanguageTag()
            languages += locale.language
        }
        if (languages.all { it.isBlank() }) {
            languages += Locale.ENGLISH.language
        }
        return languages
            .mapNotNull(::normalizeLanguageCode)
            .distinct()
    }
}
