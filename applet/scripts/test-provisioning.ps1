[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$JcHomeSimulator = $env:JC_HOME_SIMULATOR,
    [ValidateSet('full', 'negative')]
    [string]$CaseSet = 'full'
)

$ErrorActionPreference = 'Stop'

$appletRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$buildDir = Join-Path $appletRoot 'build'
$toolsOut = Join-Path $buildDir 'tools'
$buildLogDir = Join-Path $buildDir 'logs'
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

New-Item -ItemType Directory -Force -Path $toolsOut, $buildLogDir, $logDir | Out-Null
$javac = Join-Path $JavaHome 'bin/javac.exe'
$java = Join-Path $JavaHome 'bin/java.exe'

Write-Host "=== LabAccess provisioning selftest ==="
Write-Host "[1/3] compile harness"
$r = Invoke-Native { & $javac -g -d $toolsOut -cp "$amserviceJar;$socketJar" $selfTestSrc }
$r.Output | ForEach-Object { Write-Host "      $_" }
if ($r.ExitCode -ne 0) {
    Stop-Test "javac harness tra exit code $($r.ExitCode)"
}
Write-Host "      OK: ProvisioningSelfTest.java"

$adminPinBytes = 1..8 | ForEach-Object { Get-Random -Minimum 48 -Maximum 58 }
$adminPin = -join ($adminPinBytes | ForEach-Object { [char]$_ })

Write-Host "[2/3] chay harness"
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$logFile = Join-Path $logDir "gate1-provisioning-$CaseSet-$stamp.log"
$r = Invoke-Native {
    $adminPin | & $java --module-path "$amserviceJar;$socketJar" --add-modules ALL-MODULE-PATH `
        -cp $toolsOut ProvisioningSelfTest $propsFile $CaseSet
}
$r.Output | ForEach-Object { Write-Host $_ }
[System.IO.File]::WriteAllLines($logFile, [string[]]$r.Output, (New-Object System.Text.UTF8Encoding($false)))

$hexPinBytes = @($adminPinBytes | ForEach-Object { $_.ToString('x2') })
$hexPinCompactLower = $hexPinBytes -join ''
$hexPinCompactUpper = $hexPinCompactLower.ToUpperInvariant()
$hexPinSpacedLower = $hexPinBytes -join ' '
$hexPinSpacedUpper = $hexPinSpacedLower.ToUpperInvariant()
$hexPinColonLower = $hexPinBytes -join ':'
$hexPinColonUpper = $hexPinColonLower.ToUpperInvariant()
$pinNeedles = @($adminPin, $hexPinCompactLower, $hexPinCompactUpper,
    $hexPinSpacedLower, $hexPinSpacedUpper, $hexPinColonLower, $hexPinColonUpper)
$adminPinBytes = $null

Write-Host ""
Write-Host "[3/3] kiem log khong lo PIN"
$leaks = @()
$readErrors = @()
$scanCount = 0
$logRoots = @((Join-Path $repoRoot 'logs'), $buildLogDir)
foreach ($root in $logRoots) {
    if (-not (Test-Path -LiteralPath $root -PathType Container)) {
        Stop-Test "Thieu thu muc log bat buoc: $root"
    }
    try {
        $files = @(Get-ChildItem -LiteralPath $root -File -Recurse -ErrorAction Stop)
    } catch {
        Stop-Test "Khong liet ke duoc thu muc log bat buoc: $root"
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
        if (@($pinNeedles | Where-Object { $_ -and $content.Contains($_) }).Count -gt 0) {
            $leaks += $file.FullName
        }
    }
}
$adminPin = $null
$pinNeedles = $null
$hexPinBytes = $null

Write-Host "      Da doc $scanCount file log trong $($logRoots -join '; ')"
if ($readErrors.Count -gt 0) {
    foreach ($err in $readErrors) {
        Write-Host "      KHONG DOC DUOC: $err" -ForegroundColor Red
    }
    Write-Host "SELFTEST THAT BAI: co file log khong doc duoc, khong the xac minh khong lo PIN" -ForegroundColor Red
    exit 1
}
if ($leaks.Count -gt 0) {
    foreach ($leak in $leaks) {
        Write-Host "      LO PIN trong: $leak" -ForegroundColor Red
    }
    Write-Host "SELFTEST THAT BAI: admin PIN xuat hien trong log" -ForegroundColor Red
    exit 1
}
Write-Host "      OK: khong file log nao chua PIN dang chuoi hoac hex"

Write-Host ""
Write-Host "Log: $logFile"
exit $r.ExitCode
