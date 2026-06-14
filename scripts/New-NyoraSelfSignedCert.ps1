#requires -Version 5.1
<#
.SYNOPSIS
    Creates a self-signed code-signing certificate for LOCAL Nyora MSIX testing.

.DESCRIPTION
    The Microsoft Store re-signs submitted packages, so a self-signed (or EV)
    certificate is NOT required for Store submission. It IS required to sideload
    and run an MSIX locally (Add-AppxPackage refuses unsigned packages, and
    Windows only trusts a signed package whose signing cert is trusted).

    This script:
        1. Creates a self-signed code-signing cert in CurrentUser\My whose
           Subject CN MUST EXACTLY MATCH the Publisher in the MSIX manifest
           (nyora-windows/msix/AppxManifest.xml -> <Identity Publisher="...">).
           If they differ, signing succeeds but Windows rejects the install with
           a publisher-mismatch error.
        2. Exports it to a password-protected .pfx (for signtool / build-msix.ps1).
        3. Optionally exports the public .cer.
        4. Prints the exact commands to TRUST the cert and Add-AppxPackage the
           signed .msix for local testing.

    IMPORTANT - keep the subjects aligned:
        Manifest Publisher (placeholder) : CN=PARTNER_CENTER_PUBLISHER_ID
        This cert Subject (default)      : CN=PARTNER_CENTER_PUBLISHER_ID
    For real local testing pick a stable value (e.g. CN=NyoraDev) and set the
    SAME string as the manifest Publisher BEFORE you build + sign the MSIX.

.PARAMETER Subject
    The certificate Subject (a Distinguished Name). MUST match the manifest
    <Identity Publisher="..."> exactly. Default: 'CN=PARTNER_CENTER_PUBLISHER_ID'.

.PARAMETER PfxPath
    Output path for the exported .pfx.
    Default: nyora-windows/dist/NyoraSelfSigned.pfx (created under this repo).

.PARAMETER Password
    Password protecting the .pfx. Accepts a plain string or a SecureString.
    If omitted you are prompted (input hidden).

.PARAMETER ValidityYears
    Certificate validity in years. Default: 3.

.PARAMETER ExportCer
    Also export the public certificate (.cer) next to the .pfx, for importing
    into the Trusted People / Trusted Root store.

.EXAMPLE
    .\New-NyoraSelfSignedCert.ps1 -Subject 'CN=NyoraDev' -Password 'p@ss' -ExportCer
    # then set the manifest Publisher to CN=NyoraDev, build + sign the MSIX.

.NOTES
    Run on Windows 10/11 (PowerShell New-SelfSignedCertificate). For trusting
    the cert into LocalMachine root and Add-AppxPackage, use an ELEVATED shell.
#>
[CmdletBinding()]
param(
    [string] $Subject = 'CN=PARTNER_CENTER_PUBLISHER_ID',

    [string] $PfxPath,

    [object] $Password,

    [ValidateRange(1, 30)]
    [int] $ValidityYears = 3,

    [switch] $ExportCer
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Write-Step { param([string]$m) Write-Host "`n==> $m" -ForegroundColor Cyan }
function Write-Info { param([string]$m) Write-Host "    $m" -ForegroundColor Gray }
function Write-Ok   { param([string]$m) Write-Host "    $m" -ForegroundColor Green }
function Fail       { param([string]$m) throw $m }

# This cmdlet only exists on Windows.
if (-not (Get-Command New-SelfSignedCertificate -ErrorAction SilentlyContinue)) {
    Fail "New-SelfSignedCertificate is unavailable. Run this on Windows 10/11."
}

# Validate the Subject is a DN (must start with CN= to match a manifest Publisher).
if ($Subject -notmatch '^(CN|cn)=') {
    Fail "Subject '$Subject' must be a Distinguished Name beginning with 'CN=', matching the manifest <Identity Publisher>."
}

# ---------------------------------------------------------------------------
# Resolve output path (script lives in nyora-windows/scripts/)
# ---------------------------------------------------------------------------
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$WinRoot   = Split-Path -Parent $ScriptDir
$DistRoot  = Join-Path $WinRoot 'dist'
if ([string]::IsNullOrWhiteSpace($PfxPath)) {
    if (-not (Test-Path $DistRoot)) { New-Item -ItemType Directory -Path $DistRoot -Force | Out-Null }
    $PfxPath = Join-Path $DistRoot 'NyoraSelfSigned.pfx'
} else {
    $pfxDir = Split-Path -Parent $PfxPath
    if ($pfxDir -and -not (Test-Path $pfxDir)) { New-Item -ItemType Directory -Path $pfxDir -Force | Out-Null }
}

Write-Step "Nyora self-signed code-signing certificate"
Write-Info "Subject (CN)  : $Subject"
Write-Info "PFX output    : $PfxPath"
Write-Info "Validity      : $ValidityYears year(s)"

# Cross-check against the manifest Publisher if the manifest exists.
$manifestPath = Join-Path $WinRoot 'msix\AppxManifest.xml'
if (Test-Path $manifestPath) {
    try {
        [xml]$m = Get-Content -LiteralPath $manifestPath -Raw
        $publisher = $m.Package.Identity.Publisher
        if ($publisher) {
            if ($publisher -eq $Subject) {
                Write-Ok "Manifest Publisher matches Subject ('$publisher')."
            } else {
                Write-Info "WARNING: manifest Publisher ('$publisher') != cert Subject ('$Subject')."
                Write-Info "         Align them or local install will fail with a publisher mismatch:"
                Write-Info "         edit <Identity Publisher=`"$Subject`"> in msix\AppxManifest.xml,"
                Write-Info "         or re-run this script with -Subject '$publisher'."
            }
        }
    } catch {
        Write-Info "Could not parse '$manifestPath' to verify Publisher (continuing)."
    }
}

# ---------------------------------------------------------------------------
# Resolve / prompt for the PFX password (as SecureString)
# ---------------------------------------------------------------------------
$securePw = $null
if ($null -ne $Password) {
    if ($Password -is [System.Security.SecureString]) {
        $securePw = $Password
    } else {
        $pwStr = [string]$Password
        if ([string]::IsNullOrEmpty($pwStr)) { Fail "Empty -Password is not allowed for a .pfx export." }
        $securePw = ConvertTo-SecureString -String $pwStr -AsPlainText -Force
    }
} else {
    $securePw = Read-Host -Prompt 'Enter a password to protect the .pfx' -AsSecureString
    if (-not $securePw -or $securePw.Length -eq 0) { Fail "A non-empty password is required to export the .pfx." }
}

# ---------------------------------------------------------------------------
# Create the certificate
# ---------------------------------------------------------------------------
Write-Step "Creating self-signed certificate"
$notAfter = (Get-Date).AddYears($ValidityYears)
$cert = New-SelfSignedCertificate `
    -Type Custom `
    -Subject $Subject `
    -KeyUsage DigitalSignature `
    -FriendlyName 'Nyora MSIX self-signed (local test)' `
    -CertStoreLocation 'Cert:\CurrentUser\My' `
    -NotAfter $notAfter `
    -TextExtension @('2.5.29.37={text}1.3.6.1.5.5.7.3.3', '2.5.29.19={text}')

Write-Ok "Created cert."
Write-Info "Thumbprint    : $($cert.Thumbprint)"
Write-Info "Store         : Cert:\CurrentUser\My\$($cert.Thumbprint)"

# ---------------------------------------------------------------------------
# Export PFX (+ optional CER)
# ---------------------------------------------------------------------------
Write-Step "Exporting .pfx"
if (Test-Path $PfxPath) { Remove-Item -Force $PfxPath }
Export-PfxCertificate -Cert "Cert:\CurrentUser\My\$($cert.Thumbprint)" -FilePath $PfxPath -Password $securePw | Out-Null
Write-Ok "Exported: $PfxPath"

$cerPath = $null
if ($ExportCer) {
    $cerPath = [System.IO.Path]::ChangeExtension($PfxPath, '.cer')
    if (Test-Path $cerPath) { Remove-Item -Force $cerPath }
    Export-Certificate -Cert "Cert:\CurrentUser\My\$($cert.Thumbprint)" -FilePath $cerPath | Out-Null
    Write-Ok "Exported public cert: $cerPath"
}

# ---------------------------------------------------------------------------
# Print the local sideload workflow
# ---------------------------------------------------------------------------
Write-Step "Local sideload / testing steps"
Write-Host ""
Write-Host "  1) Ensure the manifest Publisher matches this cert Subject:" -ForegroundColor White
Write-Host "       msix\AppxManifest.xml  ->  <Identity Publisher=`"$Subject`" ...>" -ForegroundColor Gray
Write-Host ""
Write-Host "  2) Build + sign the MSIX with this cert:" -ForegroundColor White
Write-Host "       .\scripts\build-msix.ps1 -Architecture x64 -CertPath '$PfxPath' -CertPassword '<your-pfx-password>'" -ForegroundColor Gray
Write-Host ""
Write-Host "  3) Trust the certificate so Windows accepts the package" -ForegroundColor White
Write-Host "     (run this block in an ELEVATED PowerShell):" -ForegroundColor White
if ($ExportCer -and $cerPath) {
Write-Host "       Import-Certificate -FilePath '$cerPath' -CertStoreLocation Cert:\LocalMachine\TrustedPeople" -ForegroundColor Gray
Write-Host "       # (or Cert:\LocalMachine\Root to fully trust the publisher)" -ForegroundColor Gray
} else {
Write-Host "       `$pw = Read-Host 'PFX password' -AsSecureString" -ForegroundColor Gray
Write-Host "       Import-PfxCertificate -FilePath '$PfxPath' -CertStoreLocation Cert:\LocalMachine\TrustedPeople -Password `$pw" -ForegroundColor Gray
Write-Host "       # re-run this script with -ExportCer to get a .cer for Import-Certificate instead" -ForegroundColor Gray
}
Write-Host ""
Write-Host "  4) Install the signed package (elevated, signed .msix from step 2):" -ForegroundColor White
Write-Host "       Add-AppxPackage -Path '.\dist\Nyora-x64-1.0.0.0.msix'" -ForegroundColor Gray
Write-Host ""
Write-Host "  5) To uninstall later:" -ForegroundColor White
Write-Host "       Get-AppxPackage *Nyora* | Remove-AppxPackage" -ForegroundColor Gray
Write-Host ""
Write-Info "Reminder: this self-signed cert is for LOCAL testing only. The Microsoft"
Write-Info "Store re-signs your submitted package - do NOT sign Store uploads with it."

return [pscustomobject]@{
    Thumbprint = $cert.Thumbprint
    Subject    = $Subject
    PfxPath    = $PfxPath
    CerPath    = $cerPath
}
