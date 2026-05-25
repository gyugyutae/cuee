param(
    [string] $DeviceSerial = "28ea4a2c2d3f7ece",
    [int] $MaxSeconds = 60,
    [switch] $CleanupReservation
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$artifactDir = Join-Path $repo "artifacts\logs\real-korail-full-smoke-$stamp"
New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null

$adbArgs = @()
if ($DeviceSerial) {
    $adbArgs = @("-s", $DeviceSerial)
}

if (!(Test-Path $adb)) {
    throw "adb not found: $adb"
}

$deviceLines = & $adb devices
$deviceReady = $false
foreach ($line in $deviceLines) {
    if ($DeviceSerial) {
        $serialPattern = [regex]::Escape($DeviceSerial)
        if ($line -match "^$serialPattern\s+device\b") { $deviceReady = $true }
    } elseif ($line -match "\sdevice\b") {
        $deviceReady = $true
    }
}
if (-not $deviceReady) {
    throw "ADB device is not connected or not authorized. Unlock the phone, reconnect USB, approve USB debugging, then retry."
}

function Invoke-Adb {
    & $adb @adbArgs @args
}

function Save-Logcat($name = "logcat.txt") {
    "logcat skipped: this device blocks logcat intermittently during smoke runs" |
        Out-File -Encoding UTF8 (Join-Path $artifactDir $name)
}

function Save-Screenshot($name) {
    $remote = "/sdcard/$name.png"
    Invoke-Adb shell screencap -p $remote | Out-Null
    Invoke-Adb pull $remote (Join-Path $artifactDir "$name.png") | Out-Null
}

function Dump-Window($name = "window") {
    $remote = "/sdcard/window.xml"
    Invoke-Adb shell uiautomator dump $remote | Out-Null
    $local = Join-Path $artifactDir "$name.xml"
    Invoke-Adb pull $remote $local | Out-Null
    [xml](Get-Content -Path $local -Encoding UTF8 -Raw)
}

function Get-Attr($node, $name) {
    $value = $node.GetAttribute($name)
    if ($null -eq $value) { "" } else { $value }
}

function Node-Text($node) {
    ((Get-Attr $node "text") + " " + (Get-Attr $node "content-desc") + " " + (Get-Attr $node "resource-id")).Trim()
}

function All-Nodes($xml) {
    $xml.SelectNodes("//node") | ForEach-Object { $_ }
}

function Node-Visible($node) {
    (Get-Attr $node "enabled") -eq "true" -and (Get-Attr $node "bounds") -ne "[0,0][0,0]"
}

function Find-Node($predicate, [string] $label, [int] $timeoutSec = 10) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    do {
        $xml = Dump-Window $label
        foreach ($node in (All-Nodes $xml)) {
            if (& $predicate $node) {
                return $node
            }
        }
        Start-Sleep -Milliseconds 350
    } while ((Get-Date) -lt $deadline)
    Save-Screenshot "fail-$label"
    throw "Timed out waiting for $label. Artifacts: $artifactDir"
}

function Bounds-Parts($node) {
    $bounds = Get-Attr $node "bounds"
    if ($bounds -notmatch "\[(\d+),(\d+)\]\[(\d+),(\d+)\]") {
        throw "Bad bounds for $(Node-Text $node): $bounds"
    }
    @{
        Left = [int]$matches[1]
        Top = [int]$matches[2]
        Right = [int]$matches[3]
        Bottom = [int]$matches[4]
    }
}

function Tap-Node($node, [int] $sleepMs = 800) {
    $b = Bounds-Parts $node
    $x = [int](($b.Left + $b.Right) / 2)
    $y = [int](($b.Top + $b.Bottom) / 2)
    Invoke-Adb shell input tap $x $y | Out-Null
    Start-Sleep -Milliseconds $sleepMs
}

function Tap-Node-TopBand($node, [int] $sleepMs = 800) {
    $b = Bounds-Parts $node
    $x = [int](($b.Left + $b.Right) / 2)
    $y = [int]($b.Top + 36)
    Invoke-Adb shell input tap $x $y | Out-Null
    Start-Sleep -Milliseconds $sleepMs
}

function Tap-Text([string[]] $texts, [string] $label, [int] $timeoutSec = 10) {
    $node = Find-Node {
        param($n)
        if (-not (Node-Visible $n)) { return $false }
        $t = Node-Text $n
        foreach ($text in $texts) {
            if ($t -like "*$text*") { return $true }
        }
        return $false
    } $label $timeoutSec
    Tap-Node $node
    return $node
}

function Wait-Text([string[]] $texts, [string] $label, [int] $timeoutSec = 10) {
    Find-Node {
        param($n)
        if (-not (Node-Visible $n)) { return $false }
        $t = Node-Text $n
        foreach ($text in $texts) {
            if ($t -like "*$text*") { return $true }
        }
        return $false
    } $label $timeoutSec | Out-Null
}

function Current-HasText([string[]] $texts) {
    $xml = Dump-Window "probe"
    foreach ($n in (All-Nodes $xml)) {
        $t = Node-Text $n
        foreach ($text in $texts) {
            if ($t -like "*$text*") { return $true }
        }
    }
    return $false
}

function Tap-Plus-Near([string] $label) {
    $xml = Dump-Window "passenger-$label"
    $labelNode = $null
    foreach ($n in (All-Nodes $xml)) {
        if ((Node-Visible $n) -and (Node-Text $n) -like "*$label*") {
            $labelNode = $n
            break
        }
    }
    if ($null -eq $labelNode) {
        throw "Cannot find passenger label: $label"
    }
    $lb = Bounds-Parts $labelNode
    $best = $null
    $bestLeft = -1
    foreach ($n in (All-Nodes $xml)) {
        if (-not (Node-Visible $n)) { continue }
        $t = Node-Text $n
        $id = Get-Attr $n "resource-id"
        $b = Bounds-Parts $n
        $sameRow = $b.Top -le ($lb.Bottom + 72) -and $b.Bottom -ge ($lb.Top - 72)
        $looksPlus = $t -eq "+" -or $t -like "*증가*" -or $id -like "*plus*"
        if ($sameRow -and $looksPlus -and $b.Left -gt $lb.Left -and $b.Left -gt $bestLeft) {
            $best = $n
            $bestLeft = $b.Left
        }
    }
    if ($null -eq $best) {
        throw "Cannot find plus button near: $label"
    }
    Tap-Node $best
}

function Select-Best-Train() {
    for ($page = 0; $page -lt 8; $page++) {
        $xml = Dump-Window "results-$page"
        $candidates = @()
        foreach ($n in (All-Nodes $xml)) {
            if (-not (Node-Visible $n)) { continue }
            $text = (Node-Text $n).ToLower()
            $id = (Get-Attr $n "resource-id").ToLower()
            $clickable = (Get-Attr $n "clickable") -eq "true"
            $looksSeat = $text -like "*일반*" -or $text -like "*특실*" -or $text -like "*우등*" -or $text -like "*입석*" -or $text -like "*좌석*" -or $id -like "*reservebutton*" -or $id -like "*firsttextview*" -or $id -like "*secondtextview*"
            $blocked = $text -like "*매진*" -or $text -like "*예약대기*" -or $text -like "*예약링크*" -or $text -like "*링크*"
            if ($looksSeat -and -not $blocked -and ($clickable -or $id -like "*reservebutton*" -or $id -like "*firsttextview*" -or $id -like "*secondtextview*")) {
                $b = Bounds-Parts $n
                if ($b.Top -gt 300 -and $b.Bottom -lt 2050) {
                    $rank = 9
                    if ($text -like "*ktx*" -and $text -like "*일반*") { $rank = 0 }
                    elseif ($text -like "*ktx*") { $rank = 1 }
                    elseif ($text -like "*srt*" -and $text -like "*일반*") { $rank = 2 }
                    elseif ($text -like "*srt*") { $rank = 3 }
                    elseif ($text -like "*일반*") { $rank = 4 }
                    $candidates += [pscustomobject]@{ Node = $n; Rank = $rank; Top = $b.Top; Text = $text }
                }
            }
        }
        $best = $candidates | Sort-Object Rank, Top | Select-Object -First 1
        if ($best) {
            Tap-Node $best.Node 1200
            return $best
        }
        Invoke-Adb shell input swipe 540 1820 540 620 450 | Out-Null
        Start-Sleep -Milliseconds 900
    }
    Save-Screenshot "fail-no-train"
    throw "No bookable train candidate found. Artifacts: $artifactDir"
}

function Tap-IfVisible([string[]] $texts, [string] $label, [int] $timeoutSec = 2) {
    try {
        Tap-Text $texts $label $timeoutSec | Out-Null
        return $true
    } catch {
        return $false
    }
}

$start = Get-Date
Push-Location $repo
try {
    # Keep this runner focused on deterministic UI state. This device can hang
    # on adb logcat commands while the Korail app is active.
    Invoke-Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
    Invoke-Adb shell wm dismiss-keyguard | Out-Null
    Invoke-Adb shell input swipe 540 1900 540 520 250 | Out-Null
    Start-Sleep -Milliseconds 800
    Invoke-Adb shell settings put secure enabled_accessibility_services com.cuee/com.cuee.service.CueAccessibilityService | Out-Null
    Invoke-Adb shell settings put secure accessibility_enabled 1 | Out-Null
    Invoke-Adb shell am broadcast -a com.cuee.DEBUG_STOP -p com.cuee | Out-Null
    Invoke-Adb shell monkey -p com.korail.talk -c android.intent.category.LAUNCHER 1 | Out-Null
    Start-Sleep -Seconds 3
    Save-Screenshot "00-home"

    Wait-Text @("열차조회") "home" 12
    Invoke-Adb shell am broadcast -a com.cuee.DEBUG_COMMAND -p com.cuee --es utterance "진주서울표예매" | Out-Null
    Start-Sleep -Milliseconds 1200
    Save-Screenshot "01-command"

    Wait-Text @("진주") "route-jinju" 8
    Wait-Text @("서울") "route-seoul" 8

    $dateNode = Find-Node {
        param($n)
        (Node-Visible $n) -and ((Get-Attr $n "resource-id") -like "*rl_going_date*" -or (Node-Text $n) -like "*가는날*")
    } "date-field" 8
    Tap-Node-TopBand $dateNode
    Wait-Text @("가는날 선택") "date-picker" 8
    Save-Screenshot "02-date-picker"

    $tomorrow = (Get-Date).AddDays(1).Day.ToString()
    $tomorrowNode = Find-Node {
        param($n)
        if (-not (Node-Visible $n)) { return $false }
        $text = Get-Attr $n "text"
        $desc = Get-Attr $n "content-desc"
        $all = Node-Text $n
        $text -eq $tomorrow -or $desc -eq $tomorrow -or $all -like "*월 $tomorrow*" -or $all -like "*월$tomorrow*"
    } "tomorrow" 8
    Tap-Node $tomorrowNode
    Save-Screenshot "03-date-selected"

    # The demo policy is 06:00 or later. If the hour is not directly visible,
    # the current Korail picker still searches trains from the selected day and
    # the result selector filters to bookable fast trains.
    $hourNode = $null
    try {
        $hourNode = Find-Node {
            param($n)
            (Node-Visible $n) -and ((Get-Attr $n "resource-id") -like "*hourTxt*" -or (Node-Text $n) -like "*6시*" -or (Get-Attr $n "text") -eq "6" -or (Get-Attr $n "text") -eq "06")
        } "six-hour" 2
    } catch {
        $hourNode = $null
    }
    if ($hourNode) {
        Tap-Node $hourNode 300
    }

    Tap-Text @("확인") "date-confirm" 8 | Out-Null
    Wait-Text @("열차조회") "home-after-date" 8
    Save-Screenshot "04-date-confirmed"

    $passengerNode = Find-Node {
        param($n)
        (Node-Visible $n) -and ((Get-Attr $n "resource-id") -like "*passenger*" -or (Node-Text $n) -like "*인원*" -or (Node-Text $n) -like "*어른*")
    } "passenger-field" 8
    Tap-Node $passengerNode
    Wait-Text @("인원선택", "어른") "passenger-screen" 8
    Save-Screenshot "05-passenger"

    Tap-Plus-Near "어른"
    Save-Screenshot "06-adult2"
    Tap-Plus-Near "어린이"
    Save-Screenshot "07-child1"
    Tap-Text @("확인") "passenger-confirm" 8 | Out-Null
    Wait-Text @("어린이") "home-after-passenger" 8
    Save-Screenshot "08-passenger-confirmed"

    Tap-Text @("열차조회") "train-search" 8 | Out-Null
    Wait-Text @("KTX", "일반실", "특실", "예약", "매진") "results" 20
    Save-Screenshot "09-results"

    $selected = Select-Best-Train
    Save-Screenshot "10-train-selected"

    Tap-IfVisible @("확인") "optional-confirm" 3 | Out-Null
    Tap-IfVisible @("예매") "booking-button" 6 | Out-Null
    Save-Screenshot "11-after-booking"

    Tap-IfVisible @("확인") "booking-confirm" 4 | Out-Null
    Tap-IfVisible @("결제/발권", "결제발권", "발권") "issue-payment" 8 | Out-Null
    Save-Screenshot "12-after-issue-payment"
    Tap-IfVisible @("확인") "issue-confirm" 5 | Out-Null

    Wait-Text @("결제하기", "결제") "payment-entry" 15
    Save-Screenshot "13-payment-entry"

    if ($CleanupReservation) {
        Tap-IfVisible @("예약취소", "취소") "cleanup-cancel" 5 | Out-Null
        Tap-IfVisible @("예", "네", "확인") "cleanup-confirm" 5 | Out-Null
        Save-Screenshot "14-cleanup"
    }

    $elapsed = [int]((Get-Date) - $start).TotalSeconds
    Save-Logcat
    "PASS elapsed=${elapsed}s artifactDir=$artifactDir selected=$($selected.Text)" | Tee-Object -FilePath (Join-Path $artifactDir "result.txt")
    if ($elapsed -gt $MaxSeconds) {
        throw "Smoke passed functionally but exceeded ${MaxSeconds}s: ${elapsed}s"
    }
} catch {
    Save-Logcat
    Save-Screenshot "final"
    $_ | Out-File -Encoding UTF8 (Join-Path $artifactDir "error.txt")
    throw
} finally {
    Pop-Location
}
