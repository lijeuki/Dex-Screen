param([string]$Adb = 'C:\Users\rizkk\AppData\Local\Android\Sdk\platform-tools\adb.exe')
$ErrorActionPreference = 'Stop'
$serial = '153373fc'
function Device([string[]]$Command) {
    $result = & $Adb -s $serial @Command
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed: $($Command -join ' ')" }
    return $result
}
function ReadReceiverReport {
    $ErrorActionPreference = 'Continue'
    $lines = & $Adb -s $serial shell run-as local.dexprobe cat files/receiver.txt 2>$null
    if ($LASTEXITCODE -eq 0) { return ($lines -join "`n") }
    return ''
}
Device @('get-state')
$reportDirectory = Join-Path (Split-Path $PSScriptRoot -Parent) 'reports'
New-Item -ItemType Directory -Force $reportDirectory | Out-Null
$previous = (Device @('shell','settings','get','global','wifi_display_on')).Trim()
if ($previous -notin @('0','1','null')) { throw 'Unexpected Wireless display setting; no change made' }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$stateFile = Join-Path $reportDirectory "wireless-display-$stamp-before.txt"
$previous | Set-Content -LiteralPath $stateFile
Write-Output "Temporary wifi_display_on=0; prior value saved to $stateFile. Restored when this script exits."
try {
    $initial = ReadReceiverReport
    Device @('shell','settings','put','global','wifi_display_on','0')
    # Do not force-stop the app: an existing probe must be allowed to restore first.
    Device @('shell','am','start','-n','local.dexprobe/.ReceiverActivity')
    Write-Output 'Choose the tablet in Samsung wireless DeX, accept any Android invitation, then use Stop receiver when finished.'
    $deadline = (Get-Date).AddMinutes(12)
    $observedNewReport = $false
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $text = ReadReceiverReport
        if (!$text) { continue }
        $text | Set-Content -LiteralPath (Join-Path $reportDirectory "receiver-$stamp.txt") -Encoding UTF8
        if ($text -ne $initial) { $observedNewReport = $true }
        if ($observedNewReport -and $text.Contains('P2P session ended;')) { break }
    }
} finally {
    if ($previous -eq 'null') { Device @('shell','settings','delete','global','wifi_display_on') }
    else { Device @('shell','settings','put','global','wifi_display_on',$previous) }
    Write-Output "Restored wifi_display_on=$previous"
}
