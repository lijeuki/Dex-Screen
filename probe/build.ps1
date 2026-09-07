param(
    [string]$Sdk = 'C:\Users\rizkk\AppData\Local\Android\Sdk',
    [string]$Jdk = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot'
)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = $Jdk
if (!(Test-Path -LiteralPath "$Jdk\bin\javac.exe")) { throw 'JDK not found' }
foreach ($required in @('platforms\android-35\android.jar', 'build-tools\35.0.0\aapt2.exe', 'build-tools\35.0.0\apksigner.bat')) {
    if (!(Test-Path -LiteralPath (Join-Path $Sdk $required))) { throw "Missing SDK component: $required" }
}
$escapedSdk = $Sdk.Replace('\','/')
"sdk.dir=$escapedSdk" | Set-Content -LiteralPath "$PSScriptRoot\local.properties" -Encoding ASCII
$gradleCache = Join-Path (Split-Path $PSScriptRoot -Parent) '.gradle-local'
& "$PSScriptRoot\gradlew.bat" -p $PSScriptRoot --gradle-user-home $gradleCache :app:assembleDebug --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }
$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
& "$Sdk\build-tools\35.0.0\apksigner.bat" verify $apk
if ($LASTEXITCODE -ne 0) { throw 'APK verification failed' }
Write-Output $apk
