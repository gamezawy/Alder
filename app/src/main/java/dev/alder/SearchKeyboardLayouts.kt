package dev.alder

internal object SearchKeyboardLayouts {
    val supportedLanguages = listOf("ar", "es", "fr", "de", "it", "pt-BR")

    fun rows(language: String): List<List<String>> = when (language.lowercase()) {
        "ar" -> listOf(
            listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج", "د", "ذ"),
            listOf("ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ط"),
            listOf("ئ", "ء", "ؤ", "ر", "ة", "ى", "و", "ز", "ظ", "أ")
        )
        "es" -> listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm").map { it.map(Char::toString) }
        "fr" -> listOf("azertyuiop", "qsdfghjklm", "wxcvbn").map { it.map(Char::toString) }
        "de" -> listOf("qwertzuiop", "asdfghjkl", "yxcvbnm").map { it.map(Char::toString) }
        "it", "pt-br" -> listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").map { it.map(Char::toString) }
        else -> listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").map { it.map(Char::toString) }
    }

    fun switchLabel(language: String): String = when (language.lowercase()) {
        "ar" -> "ع"
        else -> language.substringBefore('-').uppercase()
    }

    fun symbols(count: Int): List<String> {
        val available = "1234567890@#$%&*()!?.,:/+-_=;:'".map(Char::toString)
        return List(count) { available.getOrNull(it) ?: "·" }
    }
}
