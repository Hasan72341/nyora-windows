package com.nyora.windows.ai

/** Which AI backend (if any) polishes machine-translated dialogue. */
enum class AiMode { OFF, WINDOWS, BYOK }

/**
 * Resolves the active [AiRefiner] from the user's settings. Prefers on-device
 * Windows AI when selected and available, otherwise the bring-your-own-key path,
 * otherwise none (machine translation only).
 */
object AiRefinement {

    suspend fun resolve(
        mode: AiMode,
        byokBaseUrl: String,
        byokApiKey: String,
        byokModel: String,
    ): AiRefiner? = when (mode) {
        AiMode.OFF -> null
        AiMode.WINDOWS -> if (WindowsAiRefiner.isAvailable()) WindowsAiRefiner else null
        AiMode.BYOK -> {
            val refiner = ByokRefiner(byokBaseUrl, byokApiKey, byokModel)
            if (refiner.isAvailable()) refiner else null
        }
    }
}
