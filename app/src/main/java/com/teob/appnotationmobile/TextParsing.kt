package com.teob.appnotationmobile

import java.text.Normalizer
import java.util.Locale

fun normalizeHeader(raw: String): String {
    return Normalizer.normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
}

fun cleanStudentName(raw: String): String {
    return raw
        .trim()
        .trim('"')
        .trim()
        .replace(Regex("\\s+"), " ")
        .trim(';', ',', '\t', ' ')
}

/**
 * Normalise un texte de cellule Excel pour comparaison robuste :
 * - minuscules (Locale.ROOT)
 * - suppression des accents (NFD + \p{Mn})
 * - remplacement des retours à la ligne par des espaces
 * - suppression des espaces insécables ( ,  , etc.)
 * - collapse des espaces multiples
 * - trim
 */
fun normalizeCellText(raw: String): String {
    var text = raw
        .lowercase(Locale.ROOT)
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace(' ', ' ')  // espace insécable
        .replace(' ', ' ')  // espace insécable étroit
        .replace(' ', ' ')  // espace figure
        .replace("​", "")   // espace zero-width (supprimé)
        .replace("﻿", "")   // BOM zero-width (supprimé)
        .trim()
    text = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
    text = text.replace(Regex("\\s+"), " ").trim()
    return text
}

/**
 * Vérifie si un texte normalisé contient un mot-clé normalisé.
 */
fun normalizedContains(text: String, keyword: String): Boolean {
    return text.contains(normalizeCellText(keyword))
}
