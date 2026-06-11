package com.nyora.windows.ai

/**
 * Optional post-translation cleanup. After OCR + machine translation produce a
 * draft line, a refiner rewrites it into more natural, idiomatic dialogue in the
 * target language — the same role Apple Intelligence plays in the macOS build and
 * ML Kit / on-device models play on Android.
 *
 * Two implementations ship:
 *  - [WindowsAiRefiner]  — on-device, free, private: the Windows AI / Phi Silica
 *    small language model (Copilot+ PCs with the Windows App SDK runtime).
 *  - [ByokRefiner]       — "bring your own key": any OpenAI-compatible chat API.
 *
 * Selection is handled by [AiRefinement]. Every call fails soft: on any error the
 * input line is returned unchanged so a speech bubble never disappears.
 */
interface AiRefiner {
    /** Human-readable provider label for the UI (e.g. "Windows AI (Phi Silica)"). */
    val label: String

    /** Whether this refiner is usable right now (probed lazily; may shell out). */
    suspend fun isAvailable(): Boolean

    /**
     * Polish already-translated [texts] into natural [targetLang] dialogue.
     * Returns one line per input, in the same order. On any failure (or a model
     * refusal) the corresponding input is returned unchanged.
     */
    suspend fun polish(texts: List<String>, targetLang: String): List<String>
}

/** Shared prompt + refusal handling used by every refiner. */
object RefinePrompts {

    /** Mirrors the macOS build's "professional manga localization editor" persona. */
    fun polishInstructions(targetLang: String): String =
        "You are a professional manga localization editor. The user gives you a " +
            "single line of dialogue that has already been translated into $targetLang. " +
            "Rewrite it to sound natural and idiomatic, as if originally written by a " +
            "fluent comic-book writer in $targetLang. Tighten phrasing, fix awkward word " +
            "order, and smooth machine-translation artifacts. Preserve the original " +
            "meaning, tone, register, and intensity (shouts stay shouts, whispers stay " +
            "whispers, formal stays formal). Keep the line roughly the same length so it " +
            "still fits in a speech bubble. Output ONLY the polished line — no quotes, no " +
            "original, no notes, no markdown. If the input is already perfect, return it " +
            "unchanged. If it is empty or unintelligible, return it unchanged."

    /** Strip stray wrapping quotes/markdown a model may add around its reply. */
    fun clean(s: String): String =
        s.trim().trim('"', '\'', '`', '「', '」', '『', '』').trim()

    /**
     * Heuristic detector for safety-filter refusals (on-device models sometimes
     * decline suggestive/violent manga content). A matched refusal means we keep
     * the machine-translation draft rather than painting "I can't help with that"
     * into a speech balloon. Ported from the macOS AppleIntelligenceRefiner.
     */
    fun isRefusal(s: String): Boolean {
        val lower = s.lowercase().trim()
        if (lower.isEmpty()) return false
        val prefixes = listOf(
            "i can't", "i cannot", "i'm not able", "i am not able", "i'm unable",
            "i am unable", "i'm sorry, but", "i am sorry, but", "sorry, i can",
            "sorry, but i", "unfortunately, i", "as an ai", "as a language model",
        )
        if (prefixes.any { lower.startsWith(it) }) return true
        val phrases = listOf(
            "can't assist with that request", "can't help with that request",
            "cannot assist with that request", "cannot help with that request",
            "unable to assist with that", "i don't feel comfortable",
            "i'm not comfortable", "violates my guidelines", "against my guidelines",
        )
        return phrases.any { lower.contains(it) }
    }
}
