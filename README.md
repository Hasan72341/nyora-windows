<div align="center">

<img src="https://nyora.pages.dev/icon.png" width="112" alt="Nyora"/>

# Nyora — Windows

### Read like the world can wait.

A native **Windows** manga reader built from scratch with **Compose Multiplatform** — hundreds of online sources, AI page translation, and a self-contained installer with its own Java runtime. Nothing else to install.

[![License: Apache 2.0](https://img.shields.io/github/license/Hasan72341/nyora-windows?color=blue)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/Hasan72341/nyora-windows?label=download&color=0ae448)](https://github.com/Hasan72341/nyora-windows/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/Hasan72341/nyora-windows/total?color=9d95ff)](https://github.com/Hasan72341/nyora-windows/releases)
[![Stars](https://img.shields.io/github/stars/Hasan72341/nyora-windows?style=social)](https://github.com/Hasan72341/nyora-windows/stargazers)

**[⬇️ Download for Windows](https://github.com/Hasan72341/nyora-windows/releases/latest)** · **[🌐 nyora.pages.dev](https://nyora.pages.dev)**

</div>

---

## ✨ Features

- 📚 **Hundreds of online sources** — browse, search & filter manga, manhwa & manhua.
- 🌐 **AI page translation** — built-in **Windows OCR** detects the text, then translates and typesets it back over the page.
- 📖 **Standard & Webtoon reader** — LTR / RTL / vertical, zoom, double-page, per-title settings.
- 🎨 **Dynamic colour correction** — adjust brightness, contrast & colour live.
- 🗂️ Favourites in custom categories, reading history, resume, **incognito**, offline downloads.
- 🔄 **Tracker integration** + ☁️ **cloud sync** (sign in with Google; library & progress sync across devices).
- 🪟 **Native Windows polish** — dark title bar + Mica backdrop on Win11, remembers window size/position, confirm-before-quit.
- 🌗 Light / dark / system themes.

## ⬇️ Install

Download the installer from the **[Releases page](https://github.com/Hasan72341/nyora-windows/releases/latest)**:

- **`Nyora-Windows-x64.exe`** — Intel / AMD 64-bit
- **`Nyora-Windows-arm64.exe`** — ARM64 (Snapdragon, etc.)

A Java runtime is bundled — no separate install. *(32-bit x86 isn't supported.)*

## 🛠️ Build from source

Requires **JDK 17+**, **WiX Toolset v3** on PATH, and the `nyora-shared` submodule.

```powershell
git clone --recurse-submodules https://github.com/Hasan72341/nyora-windows.git
cd nyora-windows
.\gradlew.bat :desktopApp:run                 # run
.\gradlew.bat :desktopApp:packageReleaseExe   # build the .exe installer
```

## 🧩 Nyora on every platform

| Platform | Repo | Get it |
|---|---|---|
| 🪟 Windows | **nyora-windows** *(you are here)* | [.exe (x64/ARM64)](https://github.com/Hasan72341/nyora-windows/releases/latest) |
| 🤖 Android | [nyora-android](https://github.com/Hasan72341/nyora-android) | [APK](https://github.com/Hasan72341/nyora-android/releases/latest) |
| 🍎 macOS | [nyora-mac](https://github.com/Hasan72341/nyora-mac) | [.dmg / `brew`](https://github.com/Hasan72341/nyora-mac/releases/latest) |
| 🐧 Linux | [nyora-linux](https://github.com/Hasan72341/nyora-linux) | [deb · rpm · curl](https://github.com/Hasan72341/nyora-linux/releases/latest) |
| 📱 iOS / iPadOS | [nyora-ios](https://github.com/Hasan72341/nyora-ios) | [sideload IPA](https://github.com/Hasan72341/nyora-ios/releases/latest) |
| 🌍 Web | — | [nyoraweb.pages.dev](https://nyoraweb.pages.dev) |

## 🏗️ Tech

Kotlin · **Compose Multiplatform for Desktop** · a shared Kotlin engine (`nyora-shared`) running the source parsers + a loopback REST API · jpackage self-contained installer.

## 🤝 Contributing

Issues & PRs welcome. ⭐ **Star the repo** if you like Nyora!

## 📄 License

Licensed under the **Apache License 2.0** (see [`LICENSE`](LICENSE)). Original code, built from scratch — source-compatible with Tachiyomi/Kotatsu-style sources but not a fork.

## 🙏 Credits

Developed & maintained by **Md Hasan Raza** — [GitHub](https://github.com/Hasan72341) · [Instagram](https://instagram.com/md_hasan_raza____) · [LinkedIn](https://www.linkedin.com/in/md-hasan-raza) · hasanraza96@outlook.com

> Nyora is not affiliated with any of the manga sources it can access.
