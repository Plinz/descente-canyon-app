package fr.descentecanyon.app.domain.model

import java.text.Normalizer

fun String.normalizeForSearch(): String = Normalizer.normalize(this, Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "")
    .lowercase()
    .replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()
