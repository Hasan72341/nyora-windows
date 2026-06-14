# Nyora — Microsoft Store (MSIX) Deployment Guide

This document is the end-to-end runbook for packaging the Nyora Windows desktop app as an
**MSIX** package and submitting it to the **Microsoft Store** via **Partner Center**. A
maintainer should be able to follow it top to bottom with no prior MSIX experience.

> Honest status: an MSIX packaging **system** (manifest, build script, signing helper, asset
> layout) is provided in this repository. The app is **not yet published** to the Microsoft
> Store. Actual submission requires a Microsoft Partner Center account and passing Store
> certification, including the content-policy review caveats noted in section 9.

---

## Table of contents

1. [Overview](#1-overview)
2. [Prerequisites](#2-prerequisites)
3. [One-time Partner Center setup](#3-one-time-partner-center-setup)
4. [Generate the visual assets](#4-generate-the-visual-assets)
5. [Build the MSIX](#5-build-the-msix)
6. [Test locally before submitting](#6-test-locally-before-submitting)
7. [Submit to the Store](#7-submit-to-the-store)
8. [Architecture and device coverage](#8-architecture-and-device-coverage)
9. [Content-policy caveat](#9-content-policy-caveat)
10. [Troubleshooting](#10-troubleshooting)

---

## 1. Overview

### Why MSIX

Nyora for Windows is a **Compose Multiplatform for Desktop (JVM)** application. Its build
configuration lives in [`desktopApp/build.gradle.kts`](../desktopApp/build.gradle.kts) with:

| Field | Value |
| --- | --- |
| App display name | Nyora |
| Vendor | Nyora |
| Version | 1.0.0 |
| Main class | `com.nyora.windows.MainKt` |
| Gradle `packageName` | `nyora` |
| `windows.packageName` | `Nyora` |
| `upgradeUuid` | `7E3B6C2A-1F44-4D58-9B0E-2C9A4F6D1E70` |
| Architectures | x64 and ARM64 (no 32-bit x86) |
| Runtime | Bundled JRE (`includeAllModules = true`) |
| Minimum OS | Windows 10 (10.0.17763.0) and Windows 11 |

The current Compose Desktop packaging produces an **MSI installer** and a **portable EXE**
(`TargetFormat.Msi`, `TargetFormat.Exe`) through `jpackage`. Those formats are perfect for
direct download and sideloading, but the **Microsoft Store only accepts MSIX**, and `jpackage`
does **not** produce MSIX.

This system therefore adds an **MSIX layer on top of the jpackage app-image**: we ask Compose
to emit a self-contained *app-image* (the `.exe` plus a bundled `runtime\` JRE), lay it out
under an `app\` subfolder together with an MSIX manifest and visual assets, then pack it with
the Windows SDK's `makeappx.exe`.

### What this system provides

| File | Purpose |
| --- | --- |
| [`msix/AppxManifest.xml`](../msix/AppxManifest.xml) | MSIX package manifest (identity, capabilities, entry point). |
| [`msix/assets/`](../msix/assets/) | Required Store tile/logo PNGs (generated from `nyora.png`). |
| [`scripts/build-msix.ps1`](../scripts/build-msix.ps1) | Builds the app-image, stages the layout, runs `makeappx pack` for one architecture, optionally signs. |
| [`scripts/build-msixbundle.ps1`](../scripts/build-msixbundle.ps1) | Collects the per-arch `.msix` and runs `makeappx bundle` → the single `.msixbundle` you upload to the Store. |
| [`scripts/New-NyoraSelfSignedCert.ps1`](../scripts/New-NyoraSelfSignedCert.ps1) | Creates a self-signed cert for **local** sideload testing only. |
| `docs/WINDOWS-STORE.md` | This guide. |

Key facts that make the system correct:

- The app-image folder and its `.exe` are **located dynamically** by searching for the `.exe`;
  nothing hardcodes a folder/exe name that could drift.
- Because it is a full-trust packaged desktop app, the manifest declares the `rescap` namespace
  and `<rescap:Capability Name="runFullTrust" />`, with
  `EntryPoint="Windows.FullTrustApplication"` and `Executable="app\Nyora.exe"`.
- The **Store re-signs** the uploaded package, so an expensive EV code-signing certificate is
  **not** required for submission. Signing is only needed for self-distribution and local
  sideload testing.

---

## 2. Prerequisites

You need a **Windows 10 or Windows 11** machine to build and package MSIX (the SDK tools are
Windows-only). Cross-building MSIX from macOS/Linux is not supported.

| Requirement | Notes |
| --- | --- |
| Windows 10 (build 17763+) or Windows 11 | Build/test host. |
| JDK 17 or newer | Required by the Compose/Gradle build. `java -version` should report 17+. |
| Windows SDK | Supplies `makeappx.exe` and `signtool.exe`. Install via the *Windows SDK* standalone installer or the *Desktop development with C++* / *Universal Windows Platform* workloads in Visual Studio Installer. |
| PowerShell 5.1+ (or PowerShell 7) | To run the scripts in [`scripts/`](../scripts/). |
| Microsoft Partner Center account | Required only for Store submission (not for local testing). One-time registration fee: roughly **$19 USD for an individual** account, **$99 USD for a company** account. |

The SDK packaging tools typically live at:

```
C:\Program Files (x86)\Windows Kits\10\bin\<version>\x64\makeappx.exe
C:\Program Files (x86)\Windows Kits\10\bin\<version>\x64\signtool.exe
```

The build script auto-discovers the newest `<version>` under that path. Confirm they exist:

```powershell
Get-ChildItem 'C:\Program Files (x86)\Windows Kits\10\bin' -Recurse -Filter makeappx.exe |
    Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
```

---

## 3. One-time Partner Center setup

The MSIX **identity** (`Name`, `Publisher`, `Publisher display name`) must **exactly match**
the values Microsoft Partner Center assigns to your reserved app. If they differ by even one
character the Store upload is rejected. You only do this once.

1. Sign in to **Partner Center** (<https://partner.microsoft.com>) and enroll in the
   **Windows & Xbox** (Microsoft Store) program if you have not already (the $19/$99 fee).
2. Go to **Apps and games → New product → MSIX or PWA app** and **reserve the app name**
   `Nyora` (or the closest available variant).
3. Open the reserved product and navigate to
   **Product management → Product identity**. Copy these three values:
   - **Package/Identity/Name** — paste as the manifest `Identity Name`.
   - **Package/Identity/Publisher** — a string of the form `CN=ABCD1234-...`; paste as the
     manifest `Identity Publisher` (it already starts with `CN=`).
   - **Package/Properties/PublisherDisplayName** — paste as the manifest
     `<PublisherDisplayName>`.
4. Open [`msix/AppxManifest.xml`](../msix/AppxManifest.xml) and replace the placeholders:

   | Placeholder in manifest | Replace with the Partner Center value |
   | --- | --- |
   | `PARTNER_CENTER_IDENTITY_NAME` | Package/Identity/Name |
   | `CN=PARTNER_CENTER_PUBLISHER_ID` | Package/Identity/Publisher (the full `CN=...`) |
   | `PARTNER_CENTER_PUBLISHER_DISPLAY_NAME` | Package/Properties/PublisherDisplayName |

   Do **not** change `Version` or `ProcessorArchitecture` here by hand — the build script
   patches those per build (section 5).

> Tip: keep the real identity values out of public commits if you prefer. You can leave the
> placeholders in the committed manifest and substitute them in CI from secrets, as long as the
> packaged manifest that you upload contains the exact Partner Center values.

---

## 4. Generate the visual assets

The MSIX manifest references tile and logo images that must exist in
[`msix/assets/`](../msix/assets/). These are produced **separately** from the master logo
`desktopApp/src/main/resources/nyora.png` (e.g. with an image editor, an icon-generation
script, or the Visual Studio asset generator) and committed as PNGs.

The PNGs that ship in `msix/assets/` today are the square tile/logo set below:

| File | Size (px) | Referenced by the manifest | Used for |
| --- | --- | --- | --- |
| `Square44x44Logo.png` | 44 x 44 | Yes (`Square44x44Logo`) | App list / taskbar icon. |
| `Square150x150Logo.png` | 150 x 150 | Yes (`Square150x150Logo`) | Medium Start tile. |
| `StoreLogo.png` | 50 x 50 | Yes (`<Logo>`) | Partner Center / Store listing. |
| `Square71x71Logo.png` | 71 x 71 | No (present; wire up a small tile if wanted) | Small Start tile. |
| `Square310x310Logo.png` | 310 x 310 | No (present; wire up a large tile if wanted) | Large Start tile. |

The three **Yes** rows are the assets the committed `AppxManifest.xml` actually points at
(`<Logo>assets\StoreLogo.png</Logo>` plus `Square150x150Logo` / `Square44x44Logo` on
`<uap:VisualElements>`), and they are sufficient to pass certification. `Square71x71Logo.png`
and `Square310x310Logo.png` are also committed so you can extend the tile set later, but the
manifest does **not** reference them yet — add the corresponding attributes/elements (and any
optional `Wide310x150Logo.png` / `SplashScreen.png` you generate) only after the PNGs exist,
or `makeappx` will fail on a missing asset.

Place every asset under `msix/assets/`. The build script copies that whole folder into the
package's `assets\` directory, and the manifest's `<Logo>` / `<uap:VisualElements>` paths point
at `assets\...`.

> Windows generates scaled variants (e.g. `Square44x44Logo.scale-200.png`) automatically when
> present, but the base sizes above are sufficient to pass certification. If you add scaled
> assets, keep them in the same `msix/assets/` folder.

---

## 5. Build the MSIX

All commands below run from the `nyora-windows` repository root on a Windows host.

### 5.1 Build a per-architecture MSIX

`scripts/build-msix.ps1` performs the whole flow: it runs
`./gradlew :desktopApp:createReleaseDistributable`, **dynamically locates** the produced
app-image folder and its `.exe` under
`desktopApp/build/compose/binaries/main-release/app/`, stages the package layout
(`app\` = the app-image, `AppxManifest.xml` at root, `assets\` = the logos), **patches**
`Version` and `ProcessorArchitecture` into the manifest, and finally runs `makeappx pack`.

Build the **x64** package:

```powershell
.\scripts\build-msix.ps1 -Architecture x64 -Version 1.0.0
```

Build the **ARM64** package (run on / for ARM64; produces an ARM64-targeted package):

```powershell
.\scripts\build-msix.ps1 -Architecture arm64 -Version 1.0.0
```

Defaults: `-Architecture x64`, `-Version 1.0.0`, `-Configuration release`. The output `.msix`
files land under `nyora-windows/dist/`, named like `Nyora-x64-1.0.0.0.msix` and
`Nyora-arm64-1.0.0.0.msix` (the version is normalised to four parts).

> Note: `ProcessorArchitecture` in MSIX must be lowercase `x64` / `arm64`. The script writes
> the correct casing into the staged manifest; the value in the committed manifest is just a
> placeholder that gets overwritten per build.

### 5.2 Bundle into a single .msixbundle (recommended)

**The `.msixbundle` is the deliverable you upload to the Store** — a single artifact that
carries every architecture, from which the Store delivers the right package to each device.
`scripts/build-msixbundle.ps1` wraps the whole thing: it collects the per-arch `.msix` files
from `dist/` and runs `makeappx bundle` for you.

On an **x64** machine (builds the x64 `.msix`, then bundles it):

```powershell
.\scripts\build-msixbundle.ps1 -BuildHostArch -Version 1.0.0
```

To ship **both** architectures in one bundle, build each arch on its **native** machine
(`jpackage` cannot cross-build — the bundled JRE is host-architecture), drop both
`Nyora-x64-1.0.0.0.msix` and `Nyora-arm64-1.0.0.0.msix` into the same `dist/` folder, then:

```powershell
.\scripts\build-msixbundle.ps1 -InputDir .\dist -Version 1.0.0
```

The result is `dist/Nyora-1.0.0.0.msixbundle` — that is what you upload to Partner Center.
A single-architecture bundle is valid too; it simply only serves that one architecture.

> Under the hood this runs `makeappx.exe bundle /bv <version> /d <folder-of-msix> /p <out.msixbundle>`,
> with `makeappx` auto-discovered from the Windows SDK. The script bundles in a clean temp folder,
> so stray files in `dist/` are ignored.

### 5.3 (Optional) Sign for self-distribution

Store submission does **not** require signing — the Store re-signs. Sign only when you want to
sideload or distribute the MSIX yourself. Pass a `.pfx` and password to the build script:

```powershell
.\scripts\build-msix.ps1 -Architecture x64 -Version 1.0.0 `
    -CertPath .\Nyora-SelfSigned.pfx -CertPassword 'YourPfxPassword'
```

The signing certificate's subject **CN must equal** the manifest `Publisher` value, or signing
fails. For local testing, generate that cert with the helper in section 6.

---

## 6. Test locally before submitting

You can install and run the MSIX on your own machine before ever touching the Store. This
requires a self-signed certificate that you trust locally.

### 6.1 Create a self-signed certificate

[`scripts/New-NyoraSelfSignedCert.ps1`](../scripts/New-NyoraSelfSignedCert.ps1) creates a
certificate whose **Subject CN matches the manifest Publisher**, exports it as a `.pfx`, and
prints the exact import + install steps. Run it in an elevated PowerShell, passing the same
`Publisher` you put in the manifest:

```powershell
.\scripts\New-NyoraSelfSignedCert.ps1 `
    -PublisherCN 'CN=PARTNER_CENTER_PUBLISHER_ID' `
    -PfxPath .\Nyora-SelfSigned.pfx `
    -PfxPassword 'YourPfxPassword'
```

For pure local testing (no Store identity yet) you may instead use a simple CN such as
`CN=Nyora` — but then the manifest `Publisher` must temporarily match that CN too, since MSIX
requires the signing CN and the manifest Publisher to be identical.

### 6.2 Trust the certificate

Import the certificate into the machine's **Trusted People** (or **Trusted Root**) store so
Windows will accept the signed package. The helper script prints this; the canonical command
is:

```powershell
# Elevated PowerShell
Import-PfxCertificate -FilePath .\Nyora-SelfSigned.pfx `
    -CertStoreLocation 'Cert:\LocalMachine\TrustedPeople' `
    -Password (ConvertTo-SecureString 'YourPfxPassword' -AsPlainText -Force)
```

### 6.3 Build a signed package and install it

```powershell
# Build + sign
.\scripts\build-msix.ps1 -Architecture x64 -Version 1.0.0 `
    -CertPath .\Nyora-SelfSigned.pfx -CertPassword 'YourPfxPassword'

# Install (sideload)
Add-AppxPackage -Path .\dist\Nyora-x64-1.0.0.0.msix
```

Launch **Nyora** from the Start menu and verify it runs (it should behave exactly like the MSI
build, since the bundled JRE is identical).

### 6.4 Uninstall after testing

```powershell
# Find the installed package family name
Get-AppxPackage *Nyora* | Select-Object Name, PackageFullName

# Remove it
Get-AppxPackage *Nyora* | Remove-AppxPackage
```

> Sideloading requires Developer Mode or sideload installation to be enabled
> (**Settings → Privacy & security → For developers**). This is only for local testing; Store
> installs need none of this.

---

## 7. Submit to the Store

1. In **Partner Center**, open the reserved Nyora product and create a new **submission**.
2. **Packages**: upload `Nyora-1.0.0.0.msixbundle` (or the individual `Nyora-x64-1.0.0.0.msix` /
   `Nyora-arm64-1.0.0.0.msix` files). Partner Center validates the identity against the reserved app — this
   is why the manifest `Name`/`Publisher` must match exactly. **The Store re-signs** your
   package with the Microsoft Store certificate, so an EV cert is not needed.
3. **Properties**: set the category (e.g. *Books & reference* or *Entertainment*) and declare
   any data the app collects (Nyora is offline-first; declare network/source access honestly).
4. **Age ratings**: complete the IARC questionnaire truthfully. Because Nyora aggregates
   third-party manga sources whose content can include mature material, choose a rating that
   reflects the most mature content reachable in the app.
5. **Store listing**: write a description, keywords, and upload **screenshots**. Use the real
   Windows captures already in this repo:

   ![Discover](screenshots/discover.png)
   ![Explore](screenshots/explore.png)
   ![Library](screenshots/library.png)
   ![Reader](screenshots/reader.png)
   ![Settings](screenshots/settings.png)
   ![Cloud Sync](screenshots/cloud-sync.png)

   Source files: `docs/screenshots/discover.png`, `explore.png`, `library.png`, `reader.png`,
   `settings.png`, `cloud-sync.png`. The Store requires desktop screenshots at a minimum
   resolution of 1366 x 768; these captures qualify.
6. **Submit for certification.** Microsoft runs automated and manual certification. Watch the
   submission dashboard for failures and resolve them (see sections 9 and 10). Once it passes,
   you choose to publish immediately or hold for a manual release.

### 7.1 Automate future submissions (CI)

Once the app is **published and live** (step 7 above, done once by hand), you can push every
future update from CI instead of dragging files into Partner Center. Two pieces are provided:

- **[`.github/workflows/store-release.yml`](../.github/workflows/store-release.yml)** — builds the
  x64 MSIX on `windows-latest` and the ARM64 MSIX on `windows-11-arm`, bundles them into one
  `.msixbundle`, then submits it with the Microsoft Store Developer CLI (`msstore`). Trigger it from
  the Actions tab (**Run workflow**, enter a version), or uncomment the `release: published` trigger
  to fire on every GitHub release.
- **[`scripts/submit-store.ps1`](../scripts/submit-store.ps1)** — the local equivalent: builds/uses a
  `.msixbundle` from `dist/` and runs `msstore reconfigure` + `msstore publish`.

**Two hard caveats from Microsoft** — read these before relying on automation:

1. **Free products only.** Store update automation via `msstore` is supported only for **free** apps.
   Nyora is free, so this works. Paid products are not yet supported.
2. **The product must already be live.** Automation publishes **updates** to an app that has already
   passed its **first** submission and gone live. Do that first submission manually (section 7).

**One-time setup — an Entra (Azure AD) app registration with Store access:**

1. In [Microsoft Entra admin center](https://entra.microsoft.com/) → **App registrations** → **New
   registration**. Note its **Application (client) ID** and your **Tenant ID** (Entra → Overview).
2. In the app registration → **Certificates & secrets** → **New client secret**. Copy the **value**
   immediately (it is shown once).
3. In **Partner Center → Account settings → User management → Microsoft Entra applications**, add that
   app registration and assign it the **Manager** role (this is what authorises it to submit).
4. Find your **Seller ID** in Partner Center → Account settings → Identifiers (a.k.a. Publisher ID).

**Add these GitHub repository secrets** (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `AZURE_AD_TENANT_ID` | Entra tenant ID |
| `AZURE_AD_APPLICATION_CLIENT_ID` | Entra app registration Application (client) ID |
| `AZURE_AD_APPLICATION_SECRET` | Entra app client secret value |
| `SELLER_ID` | Partner Center seller / publisher ID |

The **Store Product ID is public** (it is in your Partner Center URL, e.g.
`partner.microsoft.com/.../products/9MZTV9GXQ4V0/...`) and is set as `STORE_PRODUCT_ID` at the top of
the workflow — no secret needed. Locally, `submit-store.ps1` reads the same four values from
matching environment variables, or you can pass them as parameters.

> The workflow fails fast if `msix/AppxManifest.xml` still contains `PARTNER_CENTER` placeholders, so
> commit your real Product Identity (section 3) before the first automated run. The msstore CLI it uses
> is installed by Microsoft's [`microsoft/microsoft-store-apppublisher`](https://github.com/microsoft/microsoft-store-apppublisher)
> action; locally, install it with `winget install Microsoft.MSStoreCLI`.

---

## 8. Architecture and device coverage

Nyora ships **x64** and **ARM64** packages with a bundled JRE and targets the
`Windows.Desktop` device family (`MinVersion="10.0.17763.0"`,
`MaxVersionTested="10.0.22621.0"`).

### Supported

| Target | Supported | Notes |
| --- | --- | --- |
| Windows 10 x64 (build 17763+) | Yes | Primary target. |
| Windows 11 x64 | Yes | Primary target. |
| Windows 10/11 on ARM64 | Yes | Native ARM64 package — runs natively on Snapdragon-based PCs. |
| Surface Pro / Surface Laptop (x64) | Yes | Standard x64 Windows. |
| Surface Pro X / ARM-based Surface | Yes | Native ARM64 package (x64 build would also run under emulation, but the ARM64 build is preferred). |

### Not supported

| Target | Supported | Reason |
| --- | --- | --- |
| 32-bit x86 Windows | No | No x86 build is produced. |
| Xbox | No | Not in the `Windows.Desktop` family / no console build; full-trust JVM app. |
| HoloLens | No | Holographic device family not targeted. |
| Windows phone / Windows 10 Mobile | No | Discontinued platform; not targeted. |
| Surface Duo / Surface Duo 2 | No | These are **Android** devices — use the Nyora Android app instead. |

---

## 9. Content-policy caveat

Nyora is a **manga-source aggregator**: it browses and reads content from third-party sources
rather than hosting first-party content. Be aware before submitting:

- Microsoft Store **content and intellectual-property policies** may scrutinize apps that
  aggregate or facilitate access to third-party media, especially copyrighted manga. The app
  could be **rejected during certification** or removed later if reviewers consider it to
  enable infringement, or if mature content is not appropriately gated/rated.
- Mitigate by: completing the IARC age rating accurately, describing the app as a reader/client
  for sources the **user** chooses, not bundling or pre-loading any copyrighted content, and
  keeping the listing free of claims that imply free access to paid/licensed works.
- **Fallback distribution:** if Store certification is not feasible, the existing
  **MSI installer** and **portable EXE** (produced by `jpackage` via
  `./gradlew :desktopApp:packageReleaseDistributable`, which builds both the MSI and the
  portable EXE) remain fully supported and can be **sideloaded** without the Store. The MSIX
  system here does not replace those — it is an additional channel.

---

## 10. Troubleshooting

**Identity mismatch on upload** — Partner Center rejects the package because
`Identity Name` / `Publisher` do not match the reserved app. Re-copy the three values from
**Product management → Product identity** (section 3) and confirm there are no trailing spaces.
The `Publisher` must be the full `CN=...` string exactly.

**"This app package's publisher certificate could not be verified" / untrusted on install** —
The self-signed cert is not trusted on this machine. Import it into
`Cert:\LocalMachine\TrustedPeople` (section 6.2) and ensure the signing CN equals the manifest
`Publisher`. This error never applies to Store-installed copies because the Store re-signs with
a trusted certificate.

**`makeappx.exe` / `signtool.exe` not found** — The Windows SDK is not installed or not on the
expected path. Install the Windows SDK, then verify discovery:

```powershell
Get-ChildItem 'C:\Program Files (x86)\Windows Kits\10\bin' -Recurse -Filter makeappx.exe |
    Sort-Object FullName -Descending | Select-Object -First 1
```

If installed in a non-default location, point the build script at it via its SDK-path parameter
or add the SDK `bin\<ver>\x64` directory to `PATH`.

**App-image not found / no `.exe` located** — The Gradle step did not produce an app-image, or
it landed somewhere unexpected. Run the build manually and inspect the output:

```powershell
.\gradlew :desktopApp:createReleaseDistributable
Get-ChildItem -Recurse 'desktopApp\build\compose\binaries\main-release\app' -Filter *.exe
```

The script searches for the `.exe` under that tree; if the build failed (e.g. JDK < 17, or a
compilation error) fix Gradle first. Never hardcode the exe/folder name — let the script find
it.

**Version not updating** — `Version` in the manifest must be `Major.Minor.Build.Revision`
(four parts, e.g. `1.0.0.0`). The build script normalizes `-Version 1.0.0` to `1.0.0.0`. Each
Store submission must use a **higher** version than the previous one.

**Full-trust / capability errors** — A packaged JVM desktop app must run full-trust. Confirm
the manifest keeps the `rescap` namespace, `<rescap:Capability Name="runFullTrust" />`, and
`EntryPoint="Windows.FullTrustApplication"` with `Executable="app\Nyora.exe"` (matching the
`app\` layout the build script creates). If the app launches but cannot reach localhost
services, note that **AppContainer loopback** restrictions apply to UWP-sandboxed apps;
full-trust packaged apps are not sandboxed, so loopback works normally — verify the manifest
actually declares `runFullTrust`.

**OCR / translation not working after install** — Nyora's translate pipeline uses the system
`Windows.Media.Ocr` engine, which needs the per-language OCR pack installed. Install the pack
for your source language in an **elevated** PowerShell, then set that language as the
translation **source** in Nyora's settings:

```powershell
# List available / installed OCR language packs
Get-WindowsCapability -Online | Where-Object Name -Like 'Language.OCR*'

# Install (examples: Japanese, Korean, Simplified Chinese)
Add-WindowsCapability -Online -Name 'Language.OCR~~~ja-JP~0.0.1.0'
Add-WindowsCapability -Online -Name 'Language.OCR~~~ko-KR~0.0.1.0'
Add-WindowsCapability -Online -Name 'Language.OCR~~~zh-Hans-CN~0.0.1.0'
```

GUI alternative: **Settings → Time & language → Language & region →** select/add the language
**→ Language options → Optional features →** add **Optical character recognition**.
