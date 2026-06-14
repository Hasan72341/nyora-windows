#requires -Version 5.1
<#
.SYNOPSIS
    Builds a single Microsoft Store-ready MSIX *bundle* (.msixbundle) for Nyora.

.DESCRIPTION
    The Microsoft Store accepts a multi-architecture .msixbundle as a single
    upload that serves the right package to each device. This wrapper produces
    that bundle from the per-architecture .msix files made by build-msix.ps1:

        1. (optional) Build the CURRENT machine's architecture .msix via
           build-msix.ps1 -BuildHostArch.
        2. Collect the per-arch .msix files (x64 and/or arm64) into a clean
           folder.
        3. makeappx.exe bundle /d <folder> /p Nyora-<version>.msixbundle
        4. (optional) Sign the bundle with signtool when -CertPath is supplied.
           Store submission does NOT need signing - the Store re-signs.

    IMPORTANT - cross-architecture reality:
        jpackage / Compose Desktop builds the app-image (and its bundled JRE)
        for the HOST architecture only. It cannot cross-build. So a true
        x64 + arm64 bundle requires the x64 .msix to be built on an x64 Windows
        machine and the arm64 .msix on an ARM64 Windows machine, then both
        dropped into the same -InputDir here. A single-architecture bundle is
        valid too - it simply only serves that one architecture.

.PARAMETER Version
    4-part MSIX version (1-3 parts are normalised, e.g. 1.0.0 -> 1.0.0.0).
    Must match the version used to build the per-arch .msix files. Default 1.0.0.

.PARAMETER InputDir
    Folder containing the per-arch .msix files to bundle. Default: nyora-windows/dist.
    Only files named like 'Nyora-<arch>-<version>.msix' are bundled; anything
    else in the folder is ignored (the bundle is assembled in a clean temp dir).

.PARAMETER BuildHostArch
    If set, first build the current machine's architecture .msix (via
    build-msix.ps1) into -InputDir, then bundle. Use this on an x64 box to make
    the x64 .msix, and on an ARM64 box to make the arm64 .msix.

.PARAMETER CertPath
    Optional .pfx to sign the resulting .msixbundle (local sideload only - omit
    for Store submission).

.PARAMETER CertPassword
    Optional password for -CertPath (plain string or SecureString).

.PARAMETER Output
    Output path (file or directory) for the .msixbundle.
    Default: nyora-windows/dist/Nyora-<version>.msixbundle.

.EXAMPLE
    # On an x64 machine: build the x64 .msix and bundle it
    .\build-msixbundle.ps1 -BuildHostArch -Version 1.0.0

.EXAMPLE
    # Bundle pre-built x64 + arm64 .msix files collected from both machines
    .\build-msixbundle.ps1 -InputDir ..\dist -Version 1.0.0

.NOTES
    Run on Windows 10/11 with the Windows SDK (makeappx + signtool) installed.
#>
[CmdletBinding()]
param(
    [string] $Version = '1.0.0',
    [string] $InputDir,
    [switch] $BuildHostArch,
    [string] $CertPath,
    [object] $CertPassword,
    [string] $Output
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Write-Step { param([string]$m) Write-Host "`n==> $m" -ForegroundColor Cyan }
function Write-Info { param([string]$m) Write-Host "    $m" -ForegroundColor Gray }
function Write-Ok   { param([string]$m) Write-Host "    $m" -ForegroundColor Green }
function Fail       { param([string]$m) throw $m }

function Test-OnWindows {
    $v = Get-Variable -Name 'IsWindows' -ErrorAction SilentlyContinue
    if ($v) { return [bool]$v.Value }
    return ($env:OS -eq 'Windows_NT')
}
$OnWindows = Test-OnWindows

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

# Reuse the SDK-tool discovery logic from build-msix.ps1's pattern.
function Find-SdkTool {
    param([Parameter(Mandatory)][string] $Name)
    $onPath = Get-Command $Name -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $roots = @(
        "${env:ProgramFiles(x86)}\Windows Kits\10\bin",
        "${env:ProgramFiles}\Windows Kits\10\bin"
    ) | Where-Object { $_ -and (Test-Path $_) }
    $hostArch = if ([Environment]::Is64BitOperatingSystem) { 'x64' } else { 'x86' }
    $archPref = @($hostArch, 'x64', 'x86', 'arm64') | Select-Object -Unique
    foreach ($root in $roots) {
        $verDirs = Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '^[0-9]+\.[0-9]+\.' } |
            Sort-Object { [version]($_.Name) } -Descending
        foreach ($vd in $verDirs) {
            foreach ($a in $archPref) {
                $candidate = Join-Path $vd.FullName (Join-Path $a $Name)
                if (Test-Path $candidate) { return $candidate }
            }
        }
    }
    return $null
}

# Detect the host MSIX architecture (x64 / arm64).
function Get-HostMsixArch {
    $a = $env:PROCESSOR_ARCHITECTURE
    if (-not $a) { $a = (if ([Environment]::Is64BitOperatingSystem) { 'AMD64' } else { 'x86' }) }
    switch -Wildcard ($a.ToUpper()) {
        'ARM64' { return 'arm64' }
        'AMD64' { return 'x64' }
        'X64'   { return 'x64' }
        default { Fail "Unsupported host architecture '$a' (Nyora targets x64 and arm64 only)." }
    }
}

# ---------------------------------------------------------------------------
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$WinRoot   = Split-Path -Parent $ScriptDir            # nyora-windows/
$DistRoot  = Join-Path $WinRoot 'dist'
$BuildMsix = Join-Path $ScriptDir 'build-msix.ps1'

$PkgVersion = Resolve-FourPartVersion -Raw $Version
if ([string]::IsNullOrWhiteSpace($InputDir)) { $InputDir = $DistRoot }

Write-Step "Nyora MSIX bundle"
Write-Info "Version    : $PkgVersion"
Write-Info "Input dir  : $InputDir"
Write-Info "Host arch  : $(Get-HostMsixArch)"

# ---------------------------------------------------------------------------
# (1) Optionally build the current host architecture .msix first.
# ---------------------------------------------------------------------------
if ($BuildHostArch) {
    if (-not (Test-Path $BuildMsix)) { Fail "build-msix.ps1 not found at '$BuildMsix'." }
    $hostArch = Get-HostMsixArch
    Write-Step "(1) Building host-arch .msix ($hostArch) via build-msix.ps1"
    if (-not (Test-Path $InputDir)) { New-Item -ItemType Directory -Path $InputDir -Force | Out-Null }
    & $BuildMsix -Architecture $hostArch -Version $Version -Output $InputDir | Out-Null
    Write-Ok "Host-arch .msix built into $InputDir"
}

# ---------------------------------------------------------------------------
# (2) Collect per-arch .msix files for this version into a clean folder.
# ---------------------------------------------------------------------------
Write-Step "(2) Collecting per-arch .msix"
if (-not (Test-Path $InputDir)) { Fail "Input dir '$InputDir' not found. Build per-arch .msix first (build-msix.ps1) or pass -BuildHostArch." }

$msixFiles = @(
    Get-ChildItem -Path $InputDir -Filter '*.msix' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match "Nyora-(x64|arm64)-$([regex]::Escape($PkgVersion))\.msix$" }
)
if ($msixFiles.Count -eq 0) {
    Fail @"
No matching per-arch .msix files found in '$InputDir' for version $PkgVersion.
Expected names like: Nyora-x64-$PkgVersion.msix, Nyora-arm64-$PkgVersion.msix
Build them with:  .\build-msix.ps1 -Architecture x64  -Version $Version
                  .\build-msix.ps1 -Architecture arm64 -Version $Version  (on an ARM64 machine)
or re-run this script with -BuildHostArch to build the current machine's arch.
"@
}
$arches = ($msixFiles | ForEach-Object { if ($_.Name -match '-(x64|arm64)-') { $Matches[1] } } | Select-Object -Unique)
Write-Info ("Found {0} .msix: {1}" -f $msixFiles.Count, ($msixFiles.Name -join ', '))
if ($arches -notcontains 'x64' -or $arches -notcontains 'arm64') {
    Write-Info "NOTE: bundling a SINGLE architecture ($($arches -join ', '))."
    Write-Info "      A full Store bundle usually contains both x64 and arm64; build the"
    Write-Info "      missing arch on its native machine and drop the .msix into '$InputDir'."
}

# Stage a clean folder containing ONLY the .msix to bundle (makeappx bundle is
# picky about stray files in the source dir).
$BundleStage = Join-Path ([System.IO.Path]::GetTempPath()) ("nyora-bundle-{0}" -f ([guid]::NewGuid().ToString('N').Substring(0,8)))
New-Item -ItemType Directory -Path $BundleStage -Force | Out-Null
foreach ($f in $msixFiles) { Copy-Item -Path $f.FullName -Destination $BundleStage -Force }

# ---------------------------------------------------------------------------
# (3) Bundle with makeappx.
# ---------------------------------------------------------------------------
Write-Step "(3) Bundling (makeappx bundle)"
$makeappx = Find-SdkTool -Name 'makeappx.exe'
if (-not $makeappx) {
    Fail @"
makeappx.exe not found. Install the Windows 10/11 SDK, or add it to PATH.
Typical location: C:\Program Files (x86)\Windows Kits\10\bin\<ver>\x64\makeappx.exe
"@
}
Write-Info "makeappx   : $makeappx"

$defaultName = "Nyora-$PkgVersion.msixbundle"
if ([string]::IsNullOrWhiteSpace($Output)) {
    if (-not (Test-Path $DistRoot)) { New-Item -ItemType Directory -Path $DistRoot -Force | Out-Null }
    $OutBundle = Join-Path $DistRoot $defaultName
} elseif (Test-Path $Output -PathType Container) {
    $OutBundle = Join-Path $Output $defaultName
} else {
    $outDir = Split-Path -Parent $Output
    if ($outDir -and -not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }
    $OutBundle = $Output
}
if (Test-Path $OutBundle) { Remove-Item -Force $OutBundle }
Write-Info "Output     : $OutBundle"

# /bv = bundle version, /d = source dir of .msix, /p = output bundle, /o = overwrite.
& $makeappx bundle /o /bv $PkgVersion /d $BundleStage /p $OutBundle
if ($LASTEXITCODE -ne 0) { Fail "makeappx bundle failed (exit $LASTEXITCODE)." }
if (-not (Test-Path $OutBundle)) { Fail "makeappx reported success but '$OutBundle' is missing." }
Write-Ok "Bundled: $OutBundle"

# ---------------------------------------------------------------------------
# (4) Optional signing of the bundle.
# ---------------------------------------------------------------------------
if ($PSBoundParameters.ContainsKey('CertPath') -and -not [string]::IsNullOrWhiteSpace($CertPath)) {
    Write-Step "(4) Signing bundle (signtool sign)"
    if (-not (Test-Path $CertPath)) { Fail "Certificate not found: '$CertPath'." }
    $signtool = Find-SdkTool -Name 'signtool.exe'
    if (-not $signtool) { Fail "signtool.exe not found. Install the Windows 10/11 SDK." }
    Write-Info "signtool   : $signtool"

    $pwPlain = $null
    if ($null -ne $CertPassword) {
        if ($CertPassword -is [System.Security.SecureString]) {
            $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($CertPassword)
            try { $pwPlain = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
            finally { [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
        } else { $pwPlain = [string]$CertPassword }
    }
    $signArgs = @('sign', '/fd', 'SHA256', '/a', '/f', $CertPath)
    if (-not [string]::IsNullOrEmpty($pwPlain)) { $signArgs += @('/p', $pwPlain) }
    $signArgs += $OutBundle
    & $signtool @signArgs
    if ($LASTEXITCODE -ne 0) { Fail "signtool sign failed (exit $LASTEXITCODE). Check the cert subject matches the manifest Publisher." }
    Write-Ok "Signed: $OutBundle"
} else {
    Write-Step "(4) Signing skipped"
    Write-Info "No -CertPath. The bundle is UNSIGNED (correct for Store - Partner Center re-signs)."
    Write-Info "For local sideload, sign with New-NyoraSelfSignedCert.ps1 then re-run with -CertPath."
}

try { Remove-Item -Recurse -Force $BundleStage -ErrorAction SilentlyContinue } catch { }

Write-Step "MSIX bundle complete"
Write-Ok   "Bundle: $OutBundle"
Write-Host ""
Write-Info "Next: upload this .msixbundle in Partner Center"
Write-Info "      (see nyora-windows/docs/WINDOWS-STORE.md)."

return $OutBundle
