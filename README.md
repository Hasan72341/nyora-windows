# Nyora for Windows

A native **Windows** build of the Nyora manga reader, built with **Compose
Multiplatform for Desktop** over the shared Kotatsu engine. Ships as a
self-contained `.exe` installer with its own Java runtime bundled — nothing else
to install.

> **Download:** grab the installer from the [Releases page](https://github.com/Hasan72341/nyora-windows/releases/latest):
> - `Nyora-Windows-x64.exe` — Intel / AMD 64-bit
> - `Nyora-Windows-arm64.exe` — ARM64 (Snapdragon, etc.)
>
> 32-bit x86 isn't supported (Compose Desktop / JDK 17 are 64-bit only).

## Features

### Sources & reading
- **Huge source catalogue** — browse, search and filter hundreds of online manga/manhwa/manhua sources via the shared Kotatsu parser engine.
- **Standard & Webtoon reader** — paged (LTR/RTL) and vertical webtoon modes, zoom, double-page spreads and per-title settings.
- **AI page translation** — translate a whole page at once using the built-in **Windows OCR** engine to detect text, then translate and typeset it back over the art.
- **Dynamic colour correction** — adjust brightness, contrast and colour filters live while reading.

### Library, tracking & sync
- **Favourites in custom categories**, **reading history**, resume-where-you-left-off, and **incognito** mode.
- **Offline downloads** — download chapters for offline reading.
- **Tracker integration** — sync reading progress with online trackers.
- **Backup & restore** — export/import your library.
- **Cloud sync** — sign in with Google (loopback OAuth) and your library, favourites, categories, history and progress sync across all your Nyora devices (Supabase backend).
- **Native Windows polish** — dark title bar + Mica backdrop on Windows 11, remembers window size/position, confirm-before-quit, keep-screen-on.
- **Themes** — light / dark / system.

## Build from source

Requires **JDK 17+** (with `jpackage`), **WiX Toolset v3** on PATH, and the
`nyora-shared` submodule.

```powershell
git clone --recurse-submodules https://github.com/Hasan72341/nyora-windows.git
cd nyora-windows
.\gradlew.bat :desktopApp:run                 # run directly
.\gradlew.bat :desktopApp:packageReleaseExe   # build the .exe installer
```

The installer lands in `desktopApp/build/compose/binaries/main-release/exe/`.

Releases are built by GitHub Actions (`.github/workflows/build-windows.yml`):
pushing a `v*` tag builds the `.exe` for x64 and ARM64 on native Windows runners
and publishes them to a Release.

## Author & license

Developed and maintained by **Md Hasan Raza** — [GitHub](https://github.com/Hasan72341) · [Instagram](https://instagram.com/md_hasan_raza____) · [LinkedIn](https://www.linkedin.com/in/md-hasan-raza) · hasanraza96@outlook.com

Licensed under the **GNU General Public License v3.0**. Nyora is a fork of [Kotatsu](https://github.com/KotatsuApp/Kotatsu) and is not affiliated with any of the manga sources it can access.
