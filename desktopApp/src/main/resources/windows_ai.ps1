# Nyora — on-device text refinement via Windows AI (the Phi Silica small language
# model exposed by the Windows App SDK on Copilot+ PCs).
#
# Modes:
#   -Mode probe                     -> {"available":true|false,"reason":"..."}
#   -Mode generate -InputPath FILE  -> {"available":true,"results":["...",...]}
#
# The input file is UTF-8 and holds the prompts separated by the literal token
# "<<<NYORA_SEP>>>" on its own line; results are returned in the same order.
#
# IMPORTANT / HONEST CAVEAT: calling Phi Silica from an unpackaged PowerShell host
# is best-effort. It needs a Copilot+ PC, the Windows App SDK runtime, and (often)
# package identity + bootstrap initialization. When any of that is missing this
# script reports {"available":false} and Nyora falls back to the BYOK refiner. The
# WinRT namespace also moved across SDK versions, so we try both known names.

param(
    [ValidateSet('probe', 'generate')][string]$Mode = 'probe',
    [string]$InputPath = ''
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Fail([string]$reason) {
    $r = ($reason -replace '\\', '\\' -replace '"', '\"' -replace "[\r\n]+", ' ')
    Write-Output ('{"available":false,"reason":"' + $r + '"}')
    exit 0
}

try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null

    # Bridge WinRT IAsyncOperation<T> and IAsyncOperationWithProgress<T,P> to Tasks.
    $asTaskOp = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
            $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
            $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
        })[0]
    $asTaskProg = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
            $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
            $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperationWithProgress`2'
        })[0]

    function Await($op) {
        $t = $op.GetType()
        $iface = $t.GetInterfaces() | Where-Object { $_.Name -like 'IAsyncOperation*' } | Select-Object -First 1
        if ($null -eq $iface) { return $op }
        if ($iface.Name -eq 'IAsyncOperationWithProgress`2') {
            $args = $iface.GetGenericArguments()
            $m = $asTaskProg.MakeGenericMethod($args[0], $args[1])
        }
        else {
            $args = $iface.GetGenericArguments()
            $m = $asTaskOp.MakeGenericMethod($args[0])
        }
        $task = $m.Invoke($null, @($op))
        [void]$task.Wait(-1)
        $task.Result
    }

    # Resolve the LanguageModel type across known SDK namespaces.
    $lm = $null
    foreach ($typeName in @(
            'Microsoft.Windows.AI.Text.LanguageModel, Microsoft.Windows.AI, ContentType=WindowsRuntime',
            'Microsoft.Windows.AI.Generative.LanguageModel, Microsoft.Windows.AI.Generative, ContentType=WindowsRuntime'
        )) {
        try { $lm = [type]$typeName; if ($lm) { break } } catch { }
    }
    if ($null -eq $lm) { Fail 'language-model-type-not-found' }

    # Readiness: GetReadyState() should report Ready. Names vary; be defensive.
    $ready = $true
    try {
        $rs = $lm::GetReadyState()
        if ("$rs" -notmatch 'Ready') { $ready = $false }
    }
    catch { }
    if (-not $ready) { Fail 'model-not-ready' }

    if ($Mode -eq 'probe') {
        Write-Output '{"available":true}'
        exit 0
    }

    if (-not (Test-Path $InputPath)) { Fail 'missing-input' }
    $raw = [System.IO.File]::ReadAllText($InputPath, [System.Text.Encoding]::UTF8)
    $prompts = $raw -split '<<<NYORA_SEP>>>'

    $model = Await ($lm::CreateAsync())

    $results = New-Object System.Collections.Generic.List[string]
    foreach ($p in $prompts) {
        $prompt = $p.Trim()
        if ($prompt.Length -eq 0) { $results.Add(''); continue }
        try {
            $resp = Await ($model.GenerateResponseAsync($prompt))
            $text = $null
            foreach ($prop in @('Response', 'Text', 'Content')) {
                try { $v = $resp.$prop; if ($v) { $text = "$v"; break } } catch { }
            }
            if ($null -eq $text) { $text = "$resp" }
            $results.Add($text)
        }
        catch {
            $results.Add('')
        }
    }

    [pscustomobject]@{ available = $true; results = $results } | ConvertTo-Json -Depth 5 -Compress
}
catch {
    Fail $_.Exception.Message
}
