#requires -Version 5.1
<#
.SYNOPSIS
    Submit a Nyora .msixbundle (or .msix) to the Microsoft Store from your machine
    using the Microsoft Store Developer CLI (msstore).

.DESCRIPTION
    This is the local equivalent of .github/workflows/store-release.yml. It
    authenticates the msstore CLI with your Entra (Azure AD) credentials and
    publishes a package to your live Store product.

    CAVEATS (from Microsoft's docs):
      * Store update automation is for FREE products only (Nyora is free).
      * The app must already be PUBLISHED AND LIVE in the Store once (do the
        first submission manually in Partner Center). This publishes UPDATES.
      * The Entra app registration must have the "Manager" role in
        Partner Center → Account settings → User management.

    Prerequisite: the msstore CLI must be installed, e.g.
        winget install Microsoft.MSStoreCLI
    (cross-platform; see https://learn.microsoft.com/windows/apps/publish/msstore-dev-cli/overview)

.PARAMETER BundlePath
    Path to the .msixbundle (or .msix) to publish. Defaults to the newest
    .msixbundle in nyora-windows/dist.

.PARAMETER ProductId
    Public Store product ID (from your Partner Center URL .../products/<ID>/...).
    Default: 9MZTV9GXQ4V0 (Nyora).

.PARAMETER TenantId
    Entra tenant ID. Defaults to $env:AZURE_AD_TENANT_ID.

.PARAMETER SellerId
    Partner Center seller/publisher ID. Defaults to $env:SELLER_ID.

.PARAMETER ClientId
    Entra app registration "Application (client) ID".
    Defaults to $env:AZURE_AD_APPLICATION_CLIENT_ID.

.PARAMETER ClientSecret
    Entra app client secret. Defaults to $env:AZURE_AD_APPLICATION_SECRET.
    Prefer setting it via the environment variable rather than on the command line.

.EXAMPLE
    # With credentials in environment variables:
    $env:AZURE_AD_TENANT_ID = '...'; $env:SELLER_ID = '...'
    $env:AZURE_AD_APPLICATION_CLIENT_ID = '...'; $env:AZURE_AD_APPLICATION_SECRET = '...'
    .\submit-store.ps1

.EXAMPLE
    .\submit-store.ps1 -BundlePath ..\dist\Nyora-1.2.0.0.msixbundle -ProductId 9MZTV9GXQ4V0
#>
[CmdletBinding()]
param(
    [string] $BundlePath,
    [string] $ProductId    = '9MZTV9GXQ4V0',
    [string] $TenantId     = $env:AZURE_AD_TENANT_ID,
    [string] $SellerId     = $env:SELLER_ID,
    [string] $ClientId     = $env:AZURE_AD_APPLICATION_CLIENT_ID,
    [string] $ClientSecret = $env:AZURE_AD_APPLICATION_SECRET
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Write-Step { param([string]$m) Write-Host "`n==> $m" -ForegroundColor Cyan }
function Write-Info { param([string]$m) Write-Host "    $m" -ForegroundColor Gray }
function Fail       { param([string]$m) throw $m }

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$WinRoot   = Split-Path -Parent $ScriptDir
$DistRoot  = Join-Path $WinRoot 'dist'

Write-Step "Nyora — submit to Microsoft Store"

# 1. Locate the package to publish.
if ([string]::IsNullOrWhiteSpace($BundlePath)) {
    $pkg = Get-ChildItem $DistRoot -Filter *.msixbundle -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $pkg) {
        $pkg = Get-ChildItem $DistRoot -Filter *.msix -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
    }
    if (-not $pkg) { Fail "No .msixbundle/.msix found in '$DistRoot'. Build one first: .\scripts\build-msixbundle.ps1 -BuildHostArch -Version 1.0.0" }
    $BundlePath = $pkg.FullName
}
if (-not (Test-Path $BundlePath)) { Fail "Package not found: '$BundlePath'." }
Write-Info "Package    : $BundlePath"
Write-Info "Product ID : $ProductId"

# 2. Validate credentials are present.
foreach ($pair in @(
    @{ name = 'TenantId';     val = $TenantId;     env = 'AZURE_AD_TENANT_ID' },
    @{ name = 'SellerId';     val = $SellerId;     env = 'SELLER_ID' },
    @{ name = 'ClientId';     val = $ClientId;     env = 'AZURE_AD_APPLICATION_CLIENT_ID' },
    @{ name = 'ClientSecret'; val = $ClientSecret; env = 'AZURE_AD_APPLICATION_SECRET' }
)) {
    if ([string]::IsNullOrWhiteSpace($pair.val)) {
        Fail "Missing $($pair.name). Pass -$($pair.name) or set `$env:$($pair.env)."
    }
}

# 3. Ensure the msstore CLI is available.
if (-not (Get-Command msstore -ErrorAction SilentlyContinue)) {
    Fail @"
The Microsoft Store Developer CLI (msstore) is not on PATH. Install it, e.g.:
    winget install Microsoft.MSStoreCLI
Docs: https://learn.microsoft.com/windows/apps/publish/msstore-dev-cli/overview
"@
}

# 4. Authenticate and publish.
Write-Step "Authenticating msstore"
msstore reconfigure --tenantId $TenantId --sellerId $SellerId --clientId $ClientId --clientSecret $ClientSecret
if ($LASTEXITCODE -ne 0) { Fail "msstore reconfigure failed (exit $LASTEXITCODE)." }

Write-Step "Publishing to the Store"
Write-Info "Note: Store update automation works only for FREE, already-published products."
msstore publish "$BundlePath" -id $ProductId
if ($LASTEXITCODE -ne 0) { Fail "msstore publish failed (exit $LASTEXITCODE). Check the product is live, free, and the Entra app has the Manager role." }

Write-Step "Submission created"
Write-Info "Track certification in Partner Center; the update goes live once it passes."
