# Nyora for Windows

The Windows build is the **same Compose Multiplatform desktop app as the Linux
port** (`nyora-linux`), adapted for Windows. There is no separate UI codebase and
no C# / WinUI layer anymore — the previous WinUI 3 + helper-JAR approach was
removed in favour of reusing the Linux Compose code directly.

## Architecture

- **`:shared`** — JVM-only Kotlin Multiplatform module. It compiles the Nyora
  engine (GraalVM JS + Nyora parsers + SQLDelight) straight from
  `../nyora-mac/shared/src` (`commonMain` + `jvmMain`), exactly like the Linux
  port. One patch in `nyora-mac/shared` covers macOS, Linux, and Windows.
- **`:desktopApp`** — Compose Multiplatform for Desktop (JVM). Package
  `com.nyora.windows`. It runs **in-process**: it starts `NyoraRestServer`
  directly, uses `NyoraFacade` for all data, and proxies page images through the
  embedded HTTP server. No subprocess, no separate helper to launch.

### Data locations (Windows)

The shared engine already detects Windows and writes under `%APPDATA%\Nyora`:

- Library DB: `%APPDATA%\Nyora\nyora.db`
- Helper port file: `%APPDATA%\Nyora\helper.port`
- UI prefs: `%APPDATA%\Nyora\appPrefs.json` (`AppState.configDir()`)

## Build & run

Requires **JDK 17+** (with `jpackage` for installers).

```powershell
cd nyora-windows

# Run from source
./gradlew :desktopApp:run

# Compile check only (works on any OS, incl. macOS/Linux dev hosts)
./gradlew :desktopApp:compileKotlin

# Package an MSI installer with a bundled JRE (needs WiX Toolset v3 on PATH)
./gradlew :desktopApp:packageReleaseMsi
# -> desktopApp/build/compose/binaries/main-release/msi/*.msi
```

`x64` and `ARM64` are both pure-JVM; `jpackage` produces an installer for the
build host's architecture (build x64 on an x64 runner, ARM64 on `windows-11-arm`).
CI (`.github/workflows/build-windows.yml`) builds both.

> **MSI note:** `jpackage` needs **WiX Toolset v3** (`candle.exe`/`light.exe`) on
> PATH. WiX **4+ is not compatible**. Install with `choco install wixtoolset
> --version=3.11.2`.

## In-image translation

The reader's translate toggle runs an on-device pipeline that mirrors the macOS
and Android builds:

1. **OCR — Windows OCR engine (`Windows.Media.Ocr`, a.k.a. "Windows ML" text
   recognition).** Reached from the JVM by shelling out to **Windows PowerShell
   5.1** running the bundled `windows_ocr.ps1` (resource), which decodes the page,
   runs the system OCR engine for the selected source language, and returns line
   boxes as JSON. The JVM clusters those lines into speech bubbles. This is the
   same subprocess pattern the Linux build uses for Tesseract.
   - Pick the **source (OCR) language** in the reader's translation settings
     (`ja`, `zh-Hans`, `zh-Hant`, `ko`, `en`). **The matching Windows OCR language
     pack must be installed — see [OCR language packs](#ocr-language-packs-required)
     below.** Without it Nyora shows an "add a Windows OCR language pack" hint and
     leaves the page untranslated; it will *not* fall back to a different-language
     recognizer (running, say, the English engine on Japanese only yields garbage).
   - If PowerShell or the OCR engine can't be reached, the reader shows an
     "OCR unavailable" hint and keeps working untranslated — it never crashes.
2. **Machine translation — unofficial Google Translate** (`translate.googleapis.com`
   keyless `translate_a/single` endpoint), the same logic as the macOS app and
   Linux build. Fails soft to the original text.
3. **AI refinement (optional)** — see below.

The translated bubbles are repainted over the page (sampled balloon background +
contrasting text + ray-cast bubble polygon), identical to the Linux/macOS overlay.

## OCR language packs (required)

In-image translation reads text with the built-in **Windows OCR** engine, which
needs the language pack for the script you are reading. **English is the only OCR
pack installed by default** on most machines — Japanese, Korean, and Chinese must
be added, or OCR has nothing to recognise the page with.

Open **PowerShell as Administrator** and run the line for your source language:

| Source language        | Install command |
|------------------------|-----------------|
| Japanese               | `Add-WindowsCapability -Online -Name "Language.OCR~~~ja-JP~0.0.1.0"` |
| Korean                 | `Add-WindowsCapability -Online -Name "Language.OCR~~~ko-KR~0.0.1.0"` |
| Chinese (Simplified)   | `Add-WindowsCapability -Online -Name "Language.OCR~~~zh-CN~0.0.1.0"` |
| Chinese (Traditional)  | `Add-WindowsCapability -Online -Name "Language.OCR~~~zh-TW~0.0.1.0"` |
| English                | `Add-WindowsCapability -Online -Name "Language.OCR~~~en-US~0.0.1.0"` *(usually already installed)* |

Prefer the GUI? **Settings ▸ Time & language ▸ Language & region** — add the
language (or open its **⋯ ▸ Language options**), then install **Optical character
recognition**.

See which OCR packs you already have:

```powershell
Get-WindowsCapability -Online | Where-Object Name -like "Language.OCR*" | Select-Object Name, State
```

**Restart Nyora after installing a pack.** Note: Windows OCR is tuned for
horizontal printed text — vertical Japanese dialogue and stylized sound effects
are its weak spot, so expect some errors on those even with the correct pack
installed.

## AI refinement

After machine translation, an optional AI pass rewrites each line into more
natural, idiomatic dialogue (the role Apple Intelligence plays on macOS). Choose
the mode in **Settings ▸ Translation ▸ AI Refinement** (or the reader's translation
panel):

- **Windows AI (Phi Silica)** — on-device, free, private. Uses the Windows App SDK
  small language model on **Copilot+ PCs**, reached via `windows_ai.ps1`.
  - **Honest caveat:** calling Phi Silica from an unpackaged JVM/PowerShell host is
    *best-effort*. It needs a Copilot+ PC with the Windows App SDK runtime (and the
    WinRT namespace has moved across SDK versions, so the script probes both known
    names). When the probe fails — which it will on most machines — Nyora reports
    "Windows AI unavailable" and falls back to machine translation. For reliable AI
    polish on non-Copilot+ hardware, use a Custom key.
- **Custom key (BYOK)** — any **OpenAI-compatible** Chat Completions endpoint
  (`{baseUrl}/chat/completions`): OpenAI, OpenRouter, Groq, Together, or a local
  LM Studio / Ollama `/v1` server. Enter base URL, API key, and model. The key is
  stored locally in `appPrefs.json`. (Anthropic users can point the base URL at an
  OpenAI-compatible proxy.)
- **Off** — machine translation only.

Every refinement call fails soft: on any error or model refusal the machine
translation draft is kept, so a speech bubble never disappears.
