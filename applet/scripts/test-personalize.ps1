[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$JcHomeSimulator = $env:JC_HOME_SIMULATOR
)

$ErrorActionPreference = 'Stop'
$appletRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repoRoot = (Resolve-Path (Join-Path $appletRoot '..')).Path
$buildDir = Join-Path $appletRoot 'build'
$toolsOut = Join-Path $buildDir 'tools'
$buildLogDir = Join-Path $buildDir 'logs'
$logDir = Join-Path $repoRoot 'logs'
$source = Join-Path $PSScriptRoot 'PersonalizeSelfTest.java'

function Stop-Test {
    param([string]$Message)
    Write-Host "SELFTEST FAILED: $Message" -ForegroundColor Red
    exit 1
}

function Invoke-Native {
    param([scriptblock]$Action)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $Action 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    return [pscustomobject]@{ Output = @($output); ExitCode = $code }
}

if ([string]::IsNullOrWhiteSpace($JavaHome) -or -not (Test-Path (Join-Path $JavaHome 'bin/javac.exe'))) {
    Stop-Test "JAVA_HOME invalid: '$JavaHome'"
}
if ([string]::IsNullOrWhiteSpace($JcHomeSimulator) -or -not (Test-Path $JcHomeSimulator)) {
    Stop-Test "JC_HOME_SIMULATOR invalid: '$JcHomeSimulator'"
}
if (-not (Get-Command node.exe -ErrorAction SilentlyContinue)) {
    Stop-Test 'node.exe required for public key validation'
}

$clientDir = Join-Path $JcHomeSimulator 'client'
$amserviceJar = Join-Path $clientDir 'AMService/amservice.jar'
$socketJar = Join-Path $clientDir 'COMService/socketprovider.jar'
$propsFile = Join-Path $JcHomeSimulator 'samples/client.config.properties'
foreach ($path in @($amserviceJar, $socketJar, $propsFile, $source)) {
    if (-not (Test-Path $path)) {
        Stop-Test "Required file missing: $path"
    }
}

New-Item -ItemType Directory -Force -Path $toolsOut, $buildLogDir, $logDir | Out-Null
$javac = Join-Path $JavaHome 'bin/javac.exe'
$java = Join-Path $JavaHome 'bin/java.exe'
$classpath = "$amserviceJar;$socketJar"

Write-Host '=== LabAccess PERSONALIZE selftest ==='
Write-Host '[1/3] Compile harness'
$result = Invoke-Native { & $javac -g -d $toolsOut -cp $classpath $source }
$result.Output | ForEach-Object { Write-Host "      $_" }
if ($result.ExitCode -ne 0) {
    Stop-Test "javac failed with exit code $($result.ExitCode)"
}

$pinBytes = 1..8 | ForEach-Object { Get-Random -Minimum 48 -Maximum 58 }
$adminPin = -join ($pinBytes | ForEach-Object { [char]$_ })
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$logFile = Join-Path $logDir "gate1-personalize-$stamp.log"
Write-Host '[2/3] Run test AID EE01 scenarios'
$result = Invoke-Native {
    $adminPin | & $java --module-path "$amserviceJar;$socketJar" --add-modules ALL-MODULE-PATH `
        -cp $toolsOut PersonalizeSelfTest $propsFile
}
$result.Output | ForEach-Object { Write-Host $_ }
[System.IO.File]::WriteAllLines($logFile, [string[]]$result.Output,
    (New-Object System.Text.UTF8Encoding($false)))

$pinHex = @($pinBytes | ForEach-Object { $_.ToString('x2') })
$pinHexCompact = $pinHex -join ''
$pinHexSpaced = $pinHex -join ' '
$pinHexColon = $pinHex -join ':'
$needles = @($adminPin, $pinHexCompact, $pinHexSpaced, $pinHexColon)
$adminPin = $null
$pinBytes = $null
$pinHex = $null

Write-Host '[3/3] Scan logs for the test admin PIN'
$scanCount = 0
$leaks = @()
$readErrors = @()
foreach ($root in @((Join-Path $repoRoot 'logs'), $buildLogDir)) {
    if (-not (Test-Path -LiteralPath $root -PathType Container)) {
        Stop-Test "Required log directory missing: $root"
    }
    try {
        $files = @(Get-ChildItem -LiteralPath $root -File -Recurse -ErrorAction Stop)
    } catch {
        Stop-Test "Cannot enumerate log directory: $root"
    }
    foreach ($file in $files) {
        if ($file.Extension -notin @('.log', '.out', '.err', '.txt')) {
            continue
        }
        $scanCount++
        try {
            $content = Get-Content -LiteralPath $file.FullName -Raw -ErrorAction Stop
        } catch {
            $readErrors += $file.FullName
            continue
        }
        if (-not $content) {
            continue
        }
        foreach ($needle in $needles) {
            if ($needle -and $content.IndexOf($needle, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
                $leaks += $file.FullName
                break
            }
        }
    }
}
$needles = $null
if ($readErrors.Count -gt 0) {
    Stop-Test "Cannot read log file(s): $($readErrors -join '; ')"
}
if ($leaks.Count -gt 0) {
    Stop-Test "PIN material found in log file(s): $($leaks -join '; ')"
}
Write-Host "      Scanned $scanCount log files; no PIN leak found"
Write-Host "Log: $logFile"
exit $result.ExitCode
