# Nyora — Windows on-device OCR via the Windows.Media.Ocr WinRT API ("Windows ML"
# text recognition). Reads an image file, runs the system OCR engine for the
# requested BCP-47 language (falling back to the user's profile languages), and
# emits one JSON object describing the recognized line boxes:
#
#   {"available":true,"width":W,"height":H,"lines":[{"text","x","y","w","h"},...]}
#
# On any failure it emits {"available":false,...} so the JVM caller can surface a
# graceful "OCR unavailable" hint instead of crashing the reader.
#
# Must run under Windows PowerShell 5.1 (powershell.exe) — that host has the WinRT
# projection used by the [Type,Assembly,ContentType=WindowsRuntime] syntax and the
# System.Runtime.WindowsRuntime AsTask bridge below. PowerShell 7 (pwsh) does not.

param(
    [Parameter(Mandatory = $true)][string]$ImagePath,
    [string]$Lang = ""
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null

    # Bridge a WinRT IAsyncOperation<T> to a .NET Task we can synchronously wait on.
    $asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
            $_.Name -eq 'AsTask' -and
            $_.GetParameters().Count -eq 1 -and
            $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
        })[0]

    function Await($op, $resultType) {
        $asTask = $asTaskGeneric.MakeGenericMethod($resultType)
        $task = $asTask.Invoke($null, @($op))
        [void]$task.Wait(-1)
        $task.Result
    }

    # Project the WinRT types we need.
    [void][Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
    [void][Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType = WindowsRuntime]
    [void][Windows.Storage.StorageFile, Windows.Foundation, ContentType = WindowsRuntime]
    [void][Windows.Globalization.Language, Windows.Foundation, ContentType = WindowsRuntime]

    $file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($ImagePath)) ([Windows.Storage.StorageFile])
    $stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
    $decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
    $bitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])

    $engine = $null
    if ($Lang -and $Lang.Trim().Length -gt 0) {
        $language = New-Object Windows.Globalization.Language($Lang)
        if ([Windows.Media.Ocr.OcrEngine]::IsLanguageSupported($language)) {
            $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($language)
        }
    }
    if ($null -eq $engine) {
        $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
    }
    if ($null -eq $engine) {
        Write-Output '{"available":false,"reason":"no-language-pack","width":0,"height":0,"lines":[]}'
        return
    }

    $result = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])

    # Windows OCR groups text into lines of words; we report each line's merged
    # bounding box (computed from its words) and let the JVM cluster lines into
    # speech bubbles.
    $lines = New-Object System.Collections.Generic.List[object]
    foreach ($line in $result.Lines) {
        $minX = [double]::PositiveInfinity; $minY = [double]::PositiveInfinity
        $maxX = [double]::NegativeInfinity; $maxY = [double]::NegativeInfinity
        foreach ($word in $line.Words) {
            $r = $word.BoundingRect
            if ($r.X -lt $minX) { $minX = $r.X }
            if ($r.Y -lt $minY) { $minY = $r.Y }
            if (($r.X + $r.Width) -gt $maxX) { $maxX = $r.X + $r.Width }
            if (($r.Y + $r.Height) -gt $maxY) { $maxY = $r.Y + $r.Height }
        }
        if ($maxX -le $minX -or $maxY -le $minY) { continue }
        $txt = $line.Text
        if ([string]::IsNullOrWhiteSpace($txt)) { continue }
        $lines.Add([pscustomobject]@{
                text = $txt
                x    = [math]::Round($minX, 1)
                y    = [math]::Round($minY, 1)
                w    = [math]::Round($maxX - $minX, 1)
                h    = [math]::Round($maxY - $minY, 1)
            })
    }

    $payload = [pscustomobject]@{
        available = $true
        width     = [int]$bitmap.PixelWidth
        height    = [int]$bitmap.PixelHeight
        lines     = $lines
    }
    $payload | ConvertTo-Json -Depth 6 -Compress
}
catch {
    $msg = ($_.Exception.Message -replace '\\', '\\' -replace '"', '\"' -replace "[\r\n]+", ' ')
    Write-Output ('{"available":false,"error":"' + $msg + '","width":0,"height":0,"lines":[]}')
}
