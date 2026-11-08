[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$JcHomeSimulator = $env:JC_HOME_SIMULATOR,
    [string]$AdminPin,
    [string]$Aid = 'F04C414201EE01',
    [ValidateSet('full', 'negative')]
    [string]$CaseSet = 'full',
    [string]$CapPath
)

$ErrorActionPreference = 'Stop'

$appletRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$buildDir = Join-Path $appletRoot 'build'
$toolsOut = Join-Path $buildDir 'tools'
$repoRoot = (Resolve-Path (Join-Path $appletRoot '..')).Path
$logDir = Join-Path $repoRoot 'logs'
$selfTestSrc = Join-Path $PSScriptRoot 'ProvisioningSelfTest.java'

function Stop-Test {
    param([string]$Message)
    Write-Host ""
    Write-Host "SELFTEST THAT BAI: $Message" -ForegroundColor Red
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

$clientDir = Join-Path $JcHomeSimulator 'client'
$amserviceJar = Join-Path $clientDir 'AMService/amservice.jar'
$socketJar = Join-Path $clientDir 'COMService/socketprovider.jar'
$propsFile = Join-Path $JcHomeSimulator 'samples/client.config.properties'
foreach ($p in @($amserviceJar, $socketJar, $propsFile)) {
    if (-not (Test-Path $p)) {
        Stop-Test "Khong thay file bat buoc: $p"
    }
}

New-Item -ItemType Directory -Force -Path $toolsOut, $logDir | Out-Null
$javac = Join-Path $JavaHome 'bin/javac.exe'
$java = Join-Path $JavaHome 'bin/java.exe'

Write-Host "=== LabAccess provisioning selftest ==="
Write-Host "[1/3] compile harness"
$r = Invoke-Native { & $javac -g -d $toolsOut -cp "$amserviceJar;$socketJar" $selfTestSrc }
$r.Output | ForEach-Object { Write-Host "      $_" }
if ($r.ExitCode -ne 0) {
    Stop-Test "javac harness tra exit code $($r.ExitCode)"
}

if (-not $AdminPin) {
    $AdminPin = -join (1..8 | ForEach-Object { Get-Random -Minimum 0 -Maximum 10 })
}
if ($AdminPin -notmatch '^[0-9]{8}$') {
    Stop-Test "Admin PIN thu phai la 8 chu so ASCII."
}

Write-Host "[2/3] chay harness"
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$logFile = Join-Path $logDir "gate1-provisioning-$CaseSet-$stamp.log"
$capArg = if ($CapPath) { $CapPath } else { $null }
$r = Invoke-Native {
    $AdminPin | & $java --module-path "$amserviceJar;$socketJar" --add-modules ALL-MODULE-PATH `
        -cp $toolsOut ProvisioningSelfTest $propsFile $Aid $CaseSet $capArg
}
$r.Output | ForEach-Object { Write-Host $_ }
[System.IO.File]::WriteAllLines($logFile, [string[]]$r.Output, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ""
Write-Host "[3/3] kiem log khong lo PIN"
$leaks = @()
foreach ($file in @(Get-ChildItem -Path (Join-Path $repoRoot 'logs') -File -Include *.log, *.out, *.err -Recurse) +
                  @(Get-ChildItem -Path (Join-Path $buildDir 'logs') -File -Recurse -ErrorAction SilentlyContinue)) {
    $content = Get-Content -Path $file.FullName -Raw -ErrorAction SilentlyContinue
    if ($content -and $content.Contains($AdminPin)) {
        $leaks += $file.FullName
    }
}
$AdminPin = $null

if ($leaks.Count -gt 0) {
    foreach ($leak in $leaks) {
        Write-Host "      LO PIN trong: $leak" -ForegroundColor Red
    }
    Write-Host "SELFTEST THAT BAI: admin PIN xuat hien trong log" -ForegroundColor Red
    exit 1
}
Write-Host "      OK: khong file log nao chua gia tri PIN thu"

Write-Host ""
Write-Host "Log: $logFile"
exit $r.ExitCode
