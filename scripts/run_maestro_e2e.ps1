param(
    [switch] $SkipInstall,
    [switch] $ActualKorail,
    [string] $DeviceSerial = ""
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$androidStudioJbr = "C:\Program Files\Android\Android Studio\jbr"
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$maestro = Join-Path $repo ".tools\maestro\bin\maestro.bat"

$env:JAVA_HOME = $androidStudioJbr
$env:Path = "$androidStudioJbr\bin;$sdk\platform-tools;$env:Path"
$env:MAESTRO_CLI_NO_ANALYTICS = "true"
$env:MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED = "true"
if ($DeviceSerial) {
    $env:ANDROID_SERIAL = $DeviceSerial
}

function FromUtf8Base64($value) {
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($value))
}

function Invoke-MaestroTest($flow) {
    & $maestro test $flow
    if ($LASTEXITCODE -ne 0) {
        throw "Maestro failed: $flow"
    }
}

function Start-KorailApp {
    if ($ActualKorail) {
        & $adb @adbArgs shell monkey -p com.korail.talk -c android.intent.category.LAUNCHER 1 | Out-Null
    } else {
        & $adb @adbArgs shell am start -n com.korail.talk/.MockKorailActivity | Out-Null
    }
}

if (!(Test-Path $adb)) {
    throw "adb not found at $adb"
}
if (!(Test-Path $maestro)) {
    throw "Maestro CLI not found at $maestro. Download maestro.zip into .tools first."
}

$adbArgs = @()
if ($DeviceSerial) {
    $adbArgs = @("-s", $DeviceSerial)
}

Push-Location $repo
try {
    $devices = & $adb devices | Select-String "`tdevice$"
    if (!$devices) {
        throw "No adb device is connected. Start an emulator or connect a phone with USB debugging."
    }
    if (!$DeviceSerial -and $devices.Count -gt 1) {
        throw "More than one adb device is connected. Pass -DeviceSerial <serial>."
    }

    if (!$SkipInstall) {
        if ($ActualKorail) {
            & .\gradlew.bat app:installDebug
        } else {
            & .\gradlew.bat app:installDebug mock-korail:installDebug
        }
    }

    & $adb @adbArgs shell settings put secure enabled_accessibility_services com.cuee/com.cuee.service.CueAccessibilityService
    & $adb @adbArgs shell settings put secure accessibility_enabled 1
    Start-Sleep -Seconds 2

    New-Item -ItemType Directory -Force -Path (Join-Path $repo "artifacts\screenshots") | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $repo "artifacts\logs") | Out-Null

    if ($ActualKorail) {
        Invoke-MaestroTest ".\maestro\actual-korail-entrypoints.yaml"
    } else {
        Invoke-MaestroTest ".\maestro\mock-korail-reservation.yaml"
        Invoke-MaestroTest ".\maestro\mock-korail-ticket.yaml"
    }

    & $adb @adbArgs logcat -c
    Start-KorailApp
    Start-Sleep -Seconds $(if ($ActualKorail) { 5 } else { 1 })
    $reservationCommand = FromUtf8Base64 "7Iq57LCo6raMIOyYiOunpCDssL7quLA="
    & $adb @adbArgs shell "am broadcast -a com.cuee.DEBUG_COMMAND -p com.cuee --es utterance '$reservationCommand'" | Out-Null
    Start-Sleep -Seconds 2
    & $adb @adbArgs shell screencap -p /sdcard/cuee-reservation-command.png
    & $adb @adbArgs pull /sdcard/cuee-reservation-command.png .\artifacts\screenshots\cuee-reservation-command.png | Out-Null
    & $adb @adbArgs shell am broadcast -a com.cuee.DEBUG_STOP -p com.cuee | Out-Null
    Start-Sleep -Seconds 1

    Start-KorailApp
    Start-Sleep -Seconds $(if ($ActualKorail) { 5 } else { 1 })
    $ticketCommand = FromUtf8Base64 "7JiI66ek7ZWcIO2RnCDrs7Tsl6zspJg="
    & $adb @adbArgs shell "am broadcast -a com.cuee.DEBUG_COMMAND -p com.cuee --es utterance '$ticketCommand'" | Out-Null
    Start-Sleep -Seconds 2
    & $adb @adbArgs shell screencap -p /sdcard/cuee-ticket-command.png
    & $adb @adbArgs pull /sdcard/cuee-ticket-command.png .\artifacts\screenshots\cuee-ticket-command.png | Out-Null

    & $adb @adbArgs logcat -d -v time > .\artifacts\logs\maestro-e2e-logcat.txt
} finally {
    Pop-Location
}
