package com.nyora.windows.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    private const val MAX_PROCESS_OUTPUT_BYTES = 1 * 1024 * 1024
    private const val TERMINATE_GRACE_SECONDS = 3L

    private val scriptText: String? by lazy {
        WindowsAiRefiner::class.java.getResourceAsStream("/windows_ai.ps1")
            ?.bufferedReader()?.use { it.readText() }
    }

    /**
     * PowerShell needs a filesystem script, but it must live only for the one
     * invocation that uses it. JVM shutdown-hook cleanup leaks a file per long-running app
     * session and is especially harmful when refinement is toggled repeatedly.
     */
    private fun extractScript(): java.nio.file.Path? = runCatching {
        val text = scriptText ?: return@runCatching null
        val tmp = Files.createTempFile("nyora_win_ai_", ".ps1")
        try {
            Files.writeString(tmp, text)
            tmp
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw error
        }
    }.getOrNull()

    private fun <T> withExtractedScript(block: (String) -> T): T? {
        val script = extractScript() ?: return null
        return try {
            block(script.toAbsolutePath().toString())
        } finally {
            runCatching { Files.deleteIfExists(script) }
        }
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
            withExtractedScript { script ->
                val out = run(host, listOf(script, "-Mode", "probe"), timeoutSeconds = 30)
                    ?: return@withExtractedScript false
                parseProbe(out)
            } ?: false
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            false
        }
        cachedAvailable = result
        result
    }

    override suspend fun polish(texts: List<String>, targetLang: String): List<String> = withContext(Dispatchers.IO) {
        if (texts.isEmpty() || !isAvailable()) return@withContext texts
        val host = findHost() ?: return@withContext texts

        withExtractedScript { script ->
            try {
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
                    ) ?: return@withExtractedScript texts
                    val payload = parseGenerate(out) ?: return@withExtractedScript texts
                    if (!payload.available || payload.results.size != texts.size) return@withExtractedScript texts
                    texts.indices.map { i ->
                        val cleaned = RefinePrompts.clean(payload.results[i])
                        if (cleaned.isEmpty() || RefinePrompts.isRefusal(cleaned)) texts[i] else cleaned
                    }
                } finally {
                    runCatching { Files.deleteIfExists(input) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                texts
            }
        } ?: texts
    }

    /**
     * Run PowerShell with an effective timeout and a bounded, concurrently drained
     * stdout/stderr pipe. Reading before waitFor would let a hung child block
     * forever; leaving the pipe undrained would let a chatty child deadlock.
     */
    private fun run(host: String, args: List<String>, timeoutSeconds: Long): String? {
        val command = mutableListOf(host, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File")
        command.addAll(args)
        val process = runCatching {
            ProcessBuilder(command).redirectErrorStream(true).start()
        }.getOrNull() ?: return null

        val output = ByteArrayOutputStream()
        val outputExceeded = AtomicBoolean(false)
        val outputFailed = AtomicBoolean(false)
        val drainer = Thread({
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_PROCESS_OUTPUT_BYTES) {
                            outputExceeded.set(true)
                            process.destroyForcibly()
                            break
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } catch (_: Throwable) {
                // Closing the stream while timing out is expected. A live process
                // plus a failed drain is still rejected below.
                outputFailed.set(true)
            }
        }, "NyoraWindowsAiOutput").apply {
            isDaemon = true
            start()
        }

        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                terminate(process)
                return null
            }
            drainer.join(TimeUnit.SECONDS.toMillis(TERMINATE_GRACE_SECONDS))
            if (drainer.isAlive || outputExceeded.get() || outputFailed.get()) return null
            return output.toString(Charsets.UTF_8)
        } catch (interrupted: InterruptedException) {
            terminate(process)
            Thread.currentThread().interrupt()
            throw CancellationException("Windows AI process interrupted").also { it.initCause(interrupted) }
        } catch (_: Throwable) {
            terminate(process)
            return null
        } finally {
            runCatching { process.inputStream.close() }
            if (drainer.isAlive) drainer.interrupt()
        }
    }

    private fun terminate(process: Process) {
        if (!process.isAlive) return
        process.destroy()
        if (!runCatching { process.waitFor(TERMINATE_GRACE_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)) {
            process.destroyForcibly()
            runCatching { process.waitFor(TERMINATE_GRACE_SECONDS, TimeUnit.SECONDS) }
        }
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
