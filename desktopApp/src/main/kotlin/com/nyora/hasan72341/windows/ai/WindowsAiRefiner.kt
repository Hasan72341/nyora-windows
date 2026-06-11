package com.nyora.windows.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * On-device refinement via Windows AI (Phi Silica) on Copilot+ PCs, reached by
 * shelling out to Windows PowerShell running the bundled `windows_ai.ps1`.
 *
 * This is free, private, and offline when it works — but availability is genuinely
 * best-effort: it needs a Copilot+ PC with the Windows App SDK runtime, so on most
 * machines [isAvailable] returns false and [AiRefinement] falls back to BYOK. The
 * probe result is cached for the process lifetime.
 */
object WindowsAiRefiner : AiRefiner {

    override val label: String = "Windows AI (Phi Silica)"

    private val json = Json { ignoreUnknownKeys = true }

    private val HOST_CANDIDATES = listOf(
        "powershell.exe",
        "powershell",
        "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
    )

    private val scriptPath: String? by lazy {
        runCatching {
            val text = WindowsAiRefiner::class.java.getResourceAsStream("/windows_ai.ps1")
                ?.bufferedReader()?.use { it.readText() } ?: return@runCatching null
            val tmp = Files.createTempFile("nyora_win_ai_", ".ps1")
            tmp.toFile().deleteOnExit()
            Files.writeString(tmp, text)
            tmp.toAbsolutePath().toString()
        }.getOrNull()
    }

    @Volatile private var cachedAvailable: Boolean? = null

    private fun findHost(): String? {
        for (host in HOST_CANDIDATES) {
            val ok = runCatching {
                val p = ProcessBuilder(host, "-NoProfile", "-Command", "exit 0")
                    .redirectErrorStream(true).start()
                if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); false }
                else p.exitValue() == 0
            }.getOrDefault(false)
            if (ok) return host
        }
        return null
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        cachedAvailable?.let { return@withContext it }
        val result = runCatching {
            val host = findHost() ?: return@runCatching false
            val script = scriptPath ?: return@runCatching false
            val out = run(host, listOf(script, "-Mode", "probe"), timeoutSeconds = 30) ?: return@runCatching false
            parseProbe(out)
        }.getOrDefault(false)
        cachedAvailable = result
        result
    }

    override suspend fun polish(texts: List<String>, targetLang: String): List<String> = withContext(Dispatchers.IO) {
        if (texts.isEmpty() || !isAvailable()) return@withContext texts
        val host = findHost() ?: return@withContext texts
        val script = scriptPath ?: return@withContext texts

        runCatching {
            // Build per-line prompts (instruction + line) joined for one model batch.
            val instruction = RefinePrompts.polishInstructions(targetLang)
            val prompts = texts.map { line ->
                if (line.trim().length < 2) "" else "$instruction\n\nLine: ${line.trim()}"
            }
            val input = Files.createTempFile("nyora_ai_in_", ".txt")
            try {
                Files.writeString(input, prompts.joinToString("\n<<<NYORA_SEP>>>\n"))
                val out = run(
                    host,
                    listOf(script, "-Mode", "generate", "-InputPath", input.toAbsolutePath().toString()),
                    timeoutSeconds = 120,
                ) ?: return@runCatching texts
                val payload = parseGenerate(out) ?: return@runCatching texts
                if (!payload.available || payload.results.size != texts.size) return@runCatching texts
                texts.indices.map { i ->
                    val cleaned = RefinePrompts.clean(payload.results[i])
                    if (cleaned.isEmpty() || RefinePrompts.isRefusal(cleaned)) texts[i] else cleaned
                }
            } finally {
                runCatching { Files.deleteIfExists(input) }
            }
        }.getOrDefault(texts)
    }

    private fun run(host: String, args: List<String>, timeoutSeconds: Long): String? {
        return runCatching {
            val cmd = mutableListOf(host, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File")
            cmd.addAll(args)
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
            out
        }.getOrNull()
    }

    private fun lastJsonLine(stdout: String): String? =
        stdout.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") && it.endsWith("}") }

    private fun parseProbe(stdout: String): Boolean {
        val line = lastJsonLine(stdout) ?: return false
        return runCatching { json.decodeFromString<ProbeResult>(line).available }.getOrDefault(false)
    }

    private fun parseGenerate(stdout: String): GenerateResult? {
        val line = lastJsonLine(stdout) ?: return null
        return runCatching { json.decodeFromString<GenerateResult>(line) }.getOrNull()
    }

    @Serializable private data class ProbeResult(val available: Boolean = false)
    @Serializable private data class GenerateResult(
        val available: Boolean = false,
        val results: List<String> = emptyList(),
    )
}
