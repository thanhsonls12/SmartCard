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
$apduCasesSource = Join-Path $PSScriptRoot 'ApduErrorCases.java'
$transportCasesSource = Join-Path $PSScriptRoot 'ApduTransportCases.java'
$transportSource = Join-Path $PSScriptRoot '../../desktop/src/main/java/vn/labaccess/desktop/CardTransport.java'
$gatewayCasesSource = Join-Path $PSScriptRoot 'GatewayTransportCases.java'
$gatewayNames = @('CardGateway', 'CardLifecycle', 'CardOperationResult', 'CardPhotoCommands', 'CardPinCommands',
    'CardProfileCommands', 'CardProtocol', 'CardStatus', 'CardStatusCodec', 'PendingJournal', 'PersonalizeRequest',
    'PhotoImage', 'Profile', 'ProfileCodec', 'ProfileCryptoMeta', 'ProfileMetaCodec', 'Receipt', 'ReceiptCodec',
    'ReceiptExpectation', 'ReceiptVerifier', 'Reconciliation', 'SimulatorConnection', 'Sw')
$gatewaySources = $gatewayNames | ForEach-Object { Join-Path $PSScriptRoot "../../desktop/src/main/java/vn/labaccess/desktop/$_.java" }

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

function New-TestPin {
    param([int]$Length)
    -join (1..$Length | ForEach-Object { [char](Get-Random -Minimum 48 -Maximum 58) })
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
foreach ($path in @($amserviceJar, $socketJar, $propsFile, $source, $apduCasesSource)) {
    if (-not (Test-Path $path)) {
        Stop-Test "Required file missing: $path"
    }
}

New-Item -ItemType Directory -Force -Path $toolsOut, $buildLogDir, $logDir | Out-Null
$javac = Join-Path $JavaHome 'bin/javac.exe'
$java = Join-Path $JavaHome 'bin/java.exe'
$classpath = "$amserviceJar;$socketJar"

Write-Host '=== LabAccess PERSONALIZE and PIN runtime selftest ==='
Write-Host '[1/3] Compile harness'
$result = Invoke-Native { & $javac -g -d $toolsOut -cp $classpath $source $apduCasesSource $transportCasesSource $transportSource $gatewayCasesSource @gatewaySources }
$result.Output | ForEach-Object { Write-Host "      $_" }
if ($result.ExitCode -ne 0) {
    Stop-Test "javac failed with exit code $($result.ExitCode)"
}

$testCapFiles = @(Get-ChildItem -Path (Join-Path $buildDir 'test-deliverables') -Recurse -Filter *.cap -File)
if ($testCapFiles.Count -ne 1) {
    Stop-Test 'Expected exactly one isolated test CAP; run build.ps1 first'
}
$testCapPath = $testCapFiles[0].FullName
$variantRoot = Join-Path $buildDir 'variants'
$variantNames = @('ciphertext', 'tag', 'nonce', 'counter-near-max',
    'quota-fault-before-commit', 'quota-fault-after-commit',
    'visit-counter-near-max', 'visit-fault-before-commit', 'visit-fault-after-commit',
    'visit-fault-after-close-before-commit',
    'receipt-fault-before-commit', 'receipt-signature-too-long',
    'photo-finish-after-length-fault')
foreach ($variantName in $variantNames) {
    $candidate = Join-Path $variantRoot "$variantName-deliverables/vn/labaccess/card/javacard/card.cap"
    if (-not (Test-Path $candidate)) {
        Stop-Test "Missing variant CAP for $variantName; run build.ps1 first"
    }
}
$testPins = @()
while ($testPins.Count -lt 4) {
    $length = if ($testPins.Count -eq 0) { 8 } else { 6 }
    $candidate = New-TestPin $length
    if ($candidate -notin $testPins) {
        $testPins += $candidate
    }
}
$adminPin = $testPins[0]
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$logFile = Join-Path $logDir "gate1-pin-runtime-$stamp.log"
Write-Host '[2/3] Run test AID EE01 scenarios'
$artifactsPath = Join-Path $buildDir 'receipt-artifacts.jsonl'
$result = Invoke-Native {
    ($testPins -join "`n") | & $java --module-path "$amserviceJar;$socketJar" --add-modules ALL-MODULE-PATH `
        -cp $toolsOut PersonalizeSelfTest $propsFile $testCapPath $variantRoot $artifactsPath
}
$result.Output | ForEach-Object { Write-Host $_ }
$harnessLines = @($result.Output)
[System.IO.File]::WriteAllLines($logFile, [string[]]$harnessLines,
    (New-Object System.Text.UTF8Encoding($false)))

Write-Host '[2b/3] Verify receipt signatures with Node.js'
$nodeScript = Join-Path $PSScriptRoot 'verify-receipts.js'
$nodeResult = Invoke-Native { & node $nodeScript $artifactsPath }
$nodeResult.Output | ForEach-Object { Write-Host $_ }
$nodeLines = @('', '--- Node.js verify-receipts.js ---') + @($nodeResult.Output)
[System.IO.File]::AppendAllLines($logFile, [string[]]$nodeLines,
    (New-Object System.Text.UTF8Encoding($false)))

$needles = @()
foreach ($pin in $testPins) {
    $bytes = [System.Text.Encoding]::ASCII.GetBytes($pin)
    $hex = @($bytes | ForEach-Object { $_.ToString('x2') })
    $needles += $pin, ($hex -join ''), ($hex -join ' '), ($hex -join ':')
}
$profileStudentId = 'SV2024001'
$profileDisplayName = 'Nguyen Van A'
$profilePlaintext = @(1, $profileStudentId.Length) +
    [System.Text.Encoding]::ASCII.GetBytes($profileStudentId) +
    @($profileDisplayName.Length) +
    [System.Text.Encoding]::UTF8.GetBytes($profileDisplayName)
$profileHex = @($profilePlaintext | ForEach-Object { ([byte]$_).ToString('x2') })
$needles += $profileStudentId, $profileDisplayName, ($profileHex -join '')
$testPins = $null
$adminPin = $null
$bytes = $null
$hex = $null

Write-Host '[3/3] Scan logs for test PIN values'
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
if ($nodeResult.ExitCode -ne 0) {
    Write-Host "      Node receipt verification FAILED (exit $($nodeResult.ExitCode))"
    exit 1
}
exit $result.ExitCode
