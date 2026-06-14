#requires -Version 5.1
<#
.SYNOPSIS
    Builds a Microsoft Store-ready MSIX package for the Nyora Windows desktop app.

.DESCRIPTION
    Nyora's Windows build is a Compose Multiplatform for Desktop (JVM) app. Its
    native Windows packaging (jpackage via Compose Desktop) produces an MSI
    installer + a portable EXE, but the Microsoft Store requires the MSIX format
    which jpackage does NOT emit. This script adds an MSIX layer on top of the
    jpackage *app-image*:

        1. Builds the release app-image:
               ./gradlew :desktopApp:createReleaseDistributable
           Output lands under
               desktopApp/build/compose/binaries/main-release/app/<AppFolder>\
           containing the app's .exe plus a bundled runtime\ JRE.
        2. Locates the app-image folder + .exe DYNAMICALLY (searches for the .exe;
           never hardcodes a folder/exe name that could drift).
        3. Stages an MSIX package layout under a working dir:
               <stage>\app\        <- the whole app-image (exe + runtime\)
               <stage>\assets\      <- msix/assets PNG logos
               <stage>\AppxManifest.xml
           and patches Version + ProcessorArchitecture into the manifest.
        4. Packs it with makeappx.exe pack  ->  Nyora-<arch>-<version>.msix
        5. Optionally signs it with signtool.exe when -CertPath is supplied.
           (Store submission does NOT need signing - the Store re-signs the
           package. Signing is only for self-distribution / local sideload.)

    makeappx.exe and signtool.exe are auto-discovered from the installed Windows
    SDK if they are not already on PATH.

    Per-architecture .msix files are produced. To ship a single multi-arch
    bundle, build both x64 and arm64 .msix files into one folder, then run:
        makeappx.exe bundle /d <folderWithMsix> /p Nyora-1.0.0.0.msixbundle
    (see the note printed at the end of a successful run).

.PARAMETER Architecture
    Target architecture: x64 or arm64. Default: x64.
    (Nyora targets x64 and ARM64 only - there is no 32-bit x86 build.)

.PARAMETER Version
    4-part MSIX package version (Major.Minor.Build.Revision). A 1-3 part value
    such as 1.0.0 is normalised to 4 parts (1.0.0.0). Default: 1.0.0.

.PARAMETER Configuration
    Build configuration. Default: release. (Only 'release' produces a
    Store-quality bundled-JRE app-image via createReleaseDistributable.)

.PARAMETER CertPath
    Optional path to a .pfx code-signing certificate. When supplied the resulting
    .msix is signed with signtool. Omit this for Store submission.

.PARAMETER CertPassword
    Optional password for the .pfx in -CertPath. Accepts a plain string or a
    SecureString. If a .pfx is given without a password the prompt is suppressed
    only when the cert truly has none.

.PARAMETER Output
    Optional output path for the .msix. May be a directory or a full file path.
    Defaults to nyora-windows/dist/Nyora-<arch>-<version>.msix.

.EXAMPLE
    # Build an unsigned x64 MSIX ready for Partner Center upload
    .\build-msix.ps1 -Architecture x64 -Version 1.0.0

.EXAMPLE
    # Build + sign an arm64 MSIX for local sideload testing
    .\build-msix.ps1 -Architecture arm64 -CertPath .\NyoraTest.pfx -CertPassword 'p@ss'

.NOTES
    Run on Windows 10/11 with the Windows SDK installed (makeappx + signtool).
    Requires JDK 17+ with jpackage on PATH for the Gradle app-image build.
#>
[CmdletBinding()]
param(
    [ValidateSet('x64', 'arm64')]
    [string] $Architecture = 'x64',

    [string] $Version = '1.0.0',

    [ValidateSet('release')]
    [string] $Configuration = 'release',

    [string] $CertPath,

    [object] $CertPassword,

    [string] $Output
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
function Write-Step  { param([string]$m) Write-Host "`n==> $m" -ForegroundColor Cyan }
function Write-Info  { param([string]$m) Write-Host "    $m" -ForegroundColor Gray }
function Write-Ok    { param([string]$m) Write-Host "    $m" -ForegroundColor Green }
function Fail        { param([string]$m) throw $m }

# StrictMode-safe Windows check. $IsWindows is an automatic variable only on
# PowerShell 7+ (PowerShell Core). On Windows PowerShell 5.1 it is undefined,
# and referencing an undefined variable under Set-StrictMode throws. Probe for
# it safely and fall back to $env:OS, which is 'Windows_NT' on Windows.
function Test-OnWindows {
    $v = Get-Variable -Name 'IsWindows' -ErrorAction SilentlyContinue
    if ($v) { return [bool]$v.Value }
    return ($env:OS -eq 'Windows_NT')
}
$OnWindows = Test-OnWindows

# Normalise a 1-4 part version to a strict 4-part 'a.b.c.d'.
function Resolve-FourPartVersion {
    param([string] $Raw)
    $parts = @($Raw -split '\.')
    if ($parts.Count -lt 1 -or $parts.Count -gt 4) {
        Fail "Invalid -Version '$Raw'. Use 1 to 4 numeric parts, e.g. 1.0.0 or 1.0.0.0."
    }
    foreach ($p in $parts) {
        if ($p -notmatch '^[0-9]+$') { Fail "Invalid version segment '$p' in '$Raw' (digits only)." }
    }
    while ($parts.Count -lt 4) { $parts += '0' }
    return ($parts -join '.')
}

# Map our -Architecture to the MSIX ProcessorArchitecture token.
function Resolve-MsixArch {
    param([string] $Arch)
    switch ($Arch) {
        'x64'   { return 'x64' }
        'arm64' { return 'arm64' }
        default { Fail "Unsupported architecture '$Arch'." }
    }
}

# Find an SDK tool (makeappx / signtool): try PATH first, then scan the
# Windows Kits bin folders, newest SDK version first, preferring x64 host tools.
function Find-SdkTool {
    param([Parameter(Mandatory)][string] $Name)

    $onPath = Get-Command $Name -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    $roots = @(
        "${env:ProgramFiles(x86)}\Windows Kits\10\bin",
        "${env:ProgramFiles}\Windows Kits\10\bin",
        "${env:ProgramFiles(x86)}\Windows Kits\8.1\bin",
        "${env:ProgramFiles}\Windows Kits\8.1\bin"
    ) | Where-Object { $_ -and (Test-Path $_) }

    # Prefer a host-arch matching the build machine, but accept x64 universally.
    $hostArch = if ([Environment]::Is64BitOperatingSystem) { 'x64' } else { 'x86' }
    $archPref = @($hostArch, 'x64', 'x86', 'arm64') | Select-Object -Unique

    foreach ($root in $roots) {
        # Versioned subfolders like 10.0.22621.0, newest first.
        $verDirs = Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '^[0-9]+\.[0-9]+\.' } |
            Sort-Object { [version]($_.Name) } -Descending
        foreach ($vd in $verDirs) {
            foreach ($a in $archPref) {
                $candidate = Join-Path $vd.FullName (Join-Path $a $Name)
                if (Test-Path $candidate) { return $candidate }
            }
        }
        # Some older SDKs put tools directly under bin\<arch>\.
        foreach ($a in $archPref) {
            $candidate = Join-Path $root (Join-Path $a $Name)
            if (Test-Path $candidate) { return $candidate }
        }
    }
    return $null
}

# ---------------------------------------------------------------------------
# Resolve repo / project layout (script lives in nyora-windows/scripts/)
# ---------------------------------------------------------------------------
$ScriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$WinRoot     = Split-Path -Parent $ScriptDir                 # nyora-windows/
$MsixDir     = Join-Path $WinRoot 'msix'
$ManifestSrc = Join-Path $MsixDir 'AppxManifest.xml'
$AssetsSrc   = Join-Path $MsixDir 'assets'
$DistRoot    = Join-Path $WinRoot 'dist'
$Gradlew     = if ($OnWindows) {
                   Join-Path $WinRoot 'gradlew.bat'
               } else {
                   Join-Path $WinRoot 'gradlew'
               }

Write-Step "Nyora MSIX build"
Write-Info "Architecture  : $Architecture"
Write-Info "Configuration : $Configuration"
Write-Info "Project root  : $WinRoot"

$PkgVersion  = Resolve-FourPartVersion -Raw $Version
$MsixArch    = Resolve-MsixArch -Arch $Architecture
Write-Info "Package vers. : $PkgVersion"
Write-Info "MSIX arch     : $MsixArch"

# Sanity-check required inputs.
if (-not (Test-Path $Gradlew))     { Fail "gradlew not found at '$Gradlew'. Run from a checked-out nyora-windows tree." }
if (-not (Test-Path $ManifestSrc)) { Fail "Missing manifest: '$ManifestSrc'. Create nyora-windows/msix/AppxManifest.xml first." }
if (-not (Test-Path $AssetsSrc))   { Fail "Missing assets folder: '$AssetsSrc'. Generate the MSIX logos into nyora-windows/msix/assets/ first." }

# ---------------------------------------------------------------------------
# (1) Build the release app-image via Compose Desktop / jpackage
# ---------------------------------------------------------------------------
Write-Step "(1/5) Building app-image  (gradle :desktopApp:createReleaseDistributable)"
Push-Location $WinRoot
try {
    if ($OnWindows) {
        & $Gradlew ':desktopApp:createReleaseDistributable' --console=plain
    } else {
        & sh $Gradlew ':desktopApp:createReleaseDistributable' --console=plain
    }
    if ($LASTEXITCODE -ne 0) { Fail "Gradle createReleaseDistributable failed (exit $LASTEXITCODE)." }
} finally {
    Pop-Location
}
Write-Ok "App-image build complete."

# ---------------------------------------------------------------------------
# (2) Locate the app-image folder + .exe DYNAMICALLY
# ---------------------------------------------------------------------------
Write-Step "(2/5) Locating app-image"
$AppImageParent = Join-Path $WinRoot 'desktopApp\build\compose\binaries\main-release\app'
if (-not (Test-Path $AppImageParent)) {
    Fail "Expected app-image output not found at '$AppImageParent'. Did createReleaseDistributable succeed?"
}

# The app-image is a single subfolder (named by Compose, e.g. 'nyora' or 'Nyora').
# Find the .exe inside it rather than guessing the folder/exe name. Exclude any
# stray exe inside the bundled runtime\ JRE (java.exe, etc.).
$AppExe = Get-ChildItem -Path $AppImageParent -Recurse -Filter '*.exe' -File -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch '\\runtime\\' } |
    Sort-Object FullName |
    Select-Object -First 1
if (-not $AppExe) {
    Fail "Could not find an application .exe under '$AppImageParent' (outside runtime\)."
}
$AppImageDir = $AppExe.Directory.FullName
$AppExeName  = $AppExe.Name
Write-Info "App-image dir : $AppImageDir"
Write-Info "App exe       : $AppExeName"

if (-not (Test-Path (Join-Path $AppImageDir 'runtime'))) {
    Write-Info "WARNING: no 'runtime\' folder beside the exe - bundled JRE may be missing."
}

# ---------------------------------------------------------------------------
# (3) Stage the MSIX package layout
# ---------------------------------------------------------------------------
Write-Step "(3/5) Staging MSIX layout"
$StageRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("nyora-msix-{0}-{1}" -f $MsixArch, ([guid]::NewGuid().ToString('N').Substring(0,8)))
$StageApp  = Join-Path $StageRoot 'app'
$StageAsset = Join-Path $StageRoot 'assets'
if (Test-Path $StageRoot) { Remove-Item -Recurse -Force $StageRoot }
New-Item -ItemType Directory -Path $StageApp  -Force | Out-Null
New-Item -ItemType Directory -Path $StageAsset -Force | Out-Null
Write-Info "Stage dir     : $StageRoot"

# Copy the whole app-image (exe + runtime\ + app\ data) into <stage>\app\.
Write-Info "Copying app-image -> app\ ..."
Copy-Item -Path (Join-Path $AppImageDir '*') -Destination $StageApp -Recurse -Force

# Copy MSIX logos into <stage>\assets\.
Write-Info "Copying logos -> assets\ ..."
Copy-Item -Path (Join-Path $AssetsSrc '*') -Destination $StageAsset -Recurse -Force

# Copy + patch the manifest into the stage root.
$StageManifest = Join-Path $StageRoot 'AppxManifest.xml'
Write-Info "Patching manifest (Version=$PkgVersion, ProcessorArchitecture=$MsixArch) ..."
[xml]$manifestXml = Get-Content -LiteralPath $ManifestSrc -Raw

$identity = $manifestXml.Package.Identity
if (-not $identity) { Fail "AppxManifest.xml has no <Identity> element." }
$identity.SetAttribute('Version', $PkgVersion)
$identity.SetAttribute('ProcessorArchitecture', $MsixArch)

# Keep the Application Executable pointing at the real exe under app\.
# The manifest ships a placeholder (app\Nyora.exe); align it to the discovered
# exe name so a renamed Compose output never breaks the package.
$appNode = $manifestXml.Package.Applications.Application
if ($appNode) {
    $appNode.SetAttribute('Executable', "app\$AppExeName")
    if (-not $appNode.GetAttribute('EntryPoint')) {
        $appNode.SetAttribute('EntryPoint', 'Windows.FullTrustApplication')
    }
}

# Warn loudly if the manifest still carries the Partner Center placeholders.
if ($identity.GetAttribute('Name')      -match 'PARTNER_CENTER' -or
    $identity.GetAttribute('Publisher') -match 'PARTNER_CENTER') {
    Write-Info "NOTE: Identity Name/Publisher still contain PARTNER_CENTER placeholders."
    Write-Info "      For a real Store upload, set them to the exact values from"
    Write-Info "      Partner Center -> Product management -> Product identity."
    Write-Info "      (Placeholders are fine for an unsigned local-layout test pack.)"
}

$manifestXml.Save($StageManifest)
Write-Ok "Layout staged."

# ---------------------------------------------------------------------------
# (4) Pack with makeappx
# ---------------------------------------------------------------------------
Write-Step "(4/5) Packing MSIX (makeappx pack)"
$makeappx = Find-SdkTool -Name 'makeappx.exe'
if (-not $makeappx) {
    Fail @"
makeappx.exe not found. Install the Windows 10/11 SDK, or add it to PATH.
Typical location: C:\Program Files (x86)\Windows Kits\10\bin\<ver>\x64\makeappx.exe
"@
}
Write-Info "makeappx      : $makeappx"

# Resolve the output .msix path.
$defaultName = "Nyora-$MsixArch-$PkgVersion.msix"
if ([string]::IsNullOrWhiteSpace($Output)) {
    if (-not (Test-Path $DistRoot)) { New-Item -ItemType Directory -Path $DistRoot -Force | Out-Null }
    $OutMsix = Join-Path $DistRoot $defaultName
} elseif (Test-Path $Output -PathType Container) {
    $OutMsix = Join-Path $Output $defaultName
} else {
    $outDir = Split-Path -Parent $Output
    if ($outDir -and -not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }
    $OutMsix = $Output
}
if (Test-Path $OutMsix) { Remove-Item -Force $OutMsix }
Write-Info "Output        : $OutMsix"

# /o overwrite, /h SHA256 hash, /d source dir, /p output package.
& $makeappx pack /o /h SHA256 /d $StageRoot /p $OutMsix
if ($LASTEXITCODE -ne 0) { Fail "makeappx pack failed (exit $LASTEXITCODE)." }
if (-not (Test-Path $OutMsix)) { Fail "makeappx reported success but '$OutMsix' is missing." }
Write-Ok "Packed: $OutMsix"

# ---------------------------------------------------------------------------
# (5) Optional signing
# ---------------------------------------------------------------------------
if ($PSBoundParameters.ContainsKey('CertPath') -and -not [string]::IsNullOrWhiteSpace($CertPath)) {
    Write-Step "(5/5) Signing MSIX (signtool sign)"
    if (-not (Test-Path $CertPath)) { Fail "Certificate not found: '$CertPath'." }

    $signtool = Find-SdkTool -Name 'signtool.exe'
    if (-not $signtool) {
        Fail @"
signtool.exe not found. Install the Windows 10/11 SDK, or add it to PATH.
Typical location: C:\Program Files (x86)\Windows Kits\10\bin\<ver>\x64\signtool.exe
"@
    }
    Write-Info "signtool      : $signtool"

    # Normalise the password (accept plain string or SecureString).
    $pwPlain = $null
    if ($null -ne $CertPassword) {
        if ($CertPassword -is [System.Security.SecureString]) {
            $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($CertPassword)
            try { $pwPlain = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
            finally { [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
        } else {
            $pwPlain = [string]$CertPassword
        }
    }

    $signArgs = @('sign', '/fd', 'SHA256', '/a', '/f', $CertPath)
    if (-not [string]::IsNullOrEmpty($pwPlain)) { $signArgs += @('/p', $pwPlain) }
    $signArgs += $OutMsix

    & $signtool @signArgs
    if ($LASTEXITCODE -ne 0) {
        Fail "signtool sign failed (exit $LASTEXITCODE). Check the cert subject matches the manifest Publisher."
    }
    Write-Ok "Signed: $OutMsix"
} else {
    Write-Step "(5/5) Signing skipped"
    Write-Info "No -CertPath supplied. The package is UNSIGNED."
    Write-Info "  - Store submission: correct - Partner Center re-signs the package."
    Write-Info "  - Local sideload  : sign it first (use New-NyoraSelfSignedCert.ps1"
    Write-Info "                      then re-run with -CertPath / -CertPassword)."
}

# ---------------------------------------------------------------------------
# Done - clean up + next-steps note
# ---------------------------------------------------------------------------
try { Remove-Item -Recurse -Force $StageRoot -ErrorAction SilentlyContinue } catch { }

Write-Step "MSIX build complete"
Write-Ok   "Package: $OutMsix"
Write-Host ""
Write-Info "Next steps:"
Write-Info "  Store submission : upload this unsigned .msix in Partner Center"
Write-Info "                     (see nyora-windows/docs/WINDOWS-STORE.md)."
Write-Info "  Multi-arch bundle: build both x64 + arm64 .msix into one folder, then"
Write-Info "                     makeappx.exe bundle /d <folder> /p Nyora-$PkgVersion.msixbundle"
Write-Info "  Local sideload   : sign with a self-signed cert, trust it, then"
Write-Info "                     Add-AppxPackage -Path '$OutMsix'"

return $OutMsix
