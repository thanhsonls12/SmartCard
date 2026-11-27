[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$JcHomeTools = $env:JC_HOME_TOOLS,
    [string]$JcHomeSimulator = $env:JC_HOME_SIMULATOR,
    [string]$EvidenceRoot,
    [switch]$KeepScratch
)

$ErrorActionPreference = 'Stop'

function Stop-Run {
    param([string]$Step, [string]$Message)
    Write-Host ""
    Write-Host "ADGR03 THAT BAI o buoc: $Step" -ForegroundColor Red
    Write-Host "  $Message" -ForegroundColor Red
    throw "adgr03 failed at $Step"
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if (-not $EvidenceRoot) {
    $EvidenceRoot = Join-Path $repoRoot "logs\qa-adgr03-$stamp"
}
$evidence = (New-Item -ItemType Directory -Force -Path $EvidenceRoot).FullName
$scratch = (New-Item -ItemType Directory -Force -Path (Join-Path $env:TEMP "labaccess-adgr03-$stamp")).FullName
$harnessDir = Join-Path $scratch 'harness'
New-Item -ItemType Directory -Force -Path $harnessDir | Out-Null

foreach ($pair in @(@($JavaHome, 'bin/java.exe', 'JAVA_HOME'),
        @($JcHomeTools, 'bin/converter.bat', 'JC_HOME_TOOLS'),
        @($JcHomeSimulator, 'runtime/bin/jcsw.exe', 'JC_HOME_SIMULATOR'))) {
    if ([string]::IsNullOrWhiteSpace($pair[0]) -or -not (Test-Path (Join-Path $pair[0] $pair[1]))) {
        Stop-Run 'thiet lap' "Thieu hoac sai $($pair[2]): '$($pair[0])'"
    }
}

$java = Join-Path $JavaHome 'bin/java.exe'
$javac = Join-Path $JavaHome 'bin/javac.exe'
$jcsw = Join-Path $JcHomeSimulator 'runtime/bin/jcsw.exe'
$am = Join-Path $JcHomeSimulator 'client/AMService/amservice.jar'
$sock = Join-Path $JcHomeSimulator 'client/COMService/socketprovider.jar'
$props = Join-Path $JcHomeSimulator 'samples/client.config.properties'
$sdkModules = "$am;$sock"

function Get-CapInfo([string]$Path) {
    if (-not (Test-Path $Path)) { return $null }
    [pscustomobject]@{
        path = (Resolve-Path $Path).Path
        sha256 = (Get-FileHash -Algorithm SHA256 -Path $Path).Hash
        bytes = (Get-Item $Path).Length
    }
}

function Start-Simulator([int]$Port, [string]$LogPath, $Registry) {
    $process = Start-Process -FilePath $jcsw -ArgumentList "-p=$Port" -PassThru `
        -RedirectStandardOutput $LogPath -RedirectStandardError "$LogPath.err" -WindowStyle Hidden
    $null = $Registry.Add($process)
    $deadline = (Get-Date).AddSeconds(60)
    do {
        Start-Sleep -Milliseconds 200
        $text = if (Test-Path $LogPath) { Get-Content $LogPath -Raw -ErrorAction SilentlyContinue } else { '' }
        if ($text -match "server ready on port #$Port") { return $process }
        if ($process.HasExited) { Stop-Run 'simulator' "jcsw thoat truoc khi ready tren cong $Port" }
    } while ((Get-Date) -lt $deadline)
    Stop-Run 'simulator' "Simulator khong ready tren cong $Port trong 60s"
}

function Get-FreePort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()
    return $port
}

$newPins = {
    param([int[]]$Lengths)
    $Lengths | ForEach-Object {
        -join (1..$_ | ForEach-Object { Get-Random -Minimum 0 -Maximum 10 })
    }
}

$simProcesses = New-Object System.Collections.ArrayList
try {
    Write-Host "=== ADGR-03 matrix: admin read APDU ==="
    Write-Host "repo   : $repoRoot"
    Write-Host "evidence: $evidence"
    Write-Host ""

    Write-Host "[1/6] build CAP hien tai"
    & (Join-Path $repoRoot 'applet/scripts/build.ps1') -JavaHome $JavaHome -JcHomeTools $JcHomeTools `
        *> (Join-Path $evidence 'build-current.log')
    if ($LASTEXITCODE -ne 0) { Stop-Run 'build-current' 'build.ps1 exit khac 0' }
    $currentTestCap = Join-Path $repoRoot 'applet/build/test-deliverables/vn/labaccess/card/javacard/card.cap'
    $currentProductCap = Join-Path $repoRoot 'applet/build/deliverables/vn/labaccess/card/javacard/card.cap'
    $current = [pscustomobject]@{
        product = Get-CapInfo $currentProductCap
        test    = Get-CapInfo $currentTestCap
    }
    Write-Host "      test CAP $($current.test.sha256) ($($current.test.bytes) byte)"

    Write-Host "[2/6] build CAP baseline tu HEAD trong scratch"
    $baselineTar = Join-Path $scratch 'applet-head.tar'
    & git -C $repoRoot archive --format=tar -o $baselineTar HEAD -- applet
    if ($LASTEXITCODE -ne 0) { Stop-Run 'git-archive' 'khong trich duoc HEAD applet' }
    & tar -xf $baselineTar -C $scratch
    if ($LASTEXITCODE -ne 0) { Stop-Run 'git-archive' 'khong giai nen duoc archive HEAD' }
    $baselineRoot = Join-Path $scratch 'applet'
    & (Join-Path $baselineRoot 'scripts/build.ps1') -JavaHome $JavaHome -JcHomeTools $JcHomeTools `
        *> (Join-Path $evidence 'build-baseline.log')
    if ($LASTEXITCODE -ne 0) { Stop-Run 'build-baseline' 'build.ps1 cua HEAD exit khac 0' }
    $baseline = [pscustomobject]@{
        product = Get-CapInfo (Join-Path $baselineRoot 'build/deliverables/vn/labaccess/card/javacard/card.cap')
        test    = Get-CapInfo (Join-Path $baselineRoot 'build/test-deliverables/vn/labaccess/card/javacard/card.cap')
    }
    Write-Host "      test CAP $($baseline.test.sha256) ($($baseline.test.bytes) byte)"

    Write-Host "[3/6] compile harness"
    $desktopClasses = @('card/CardGateway', 'card/CardLifecycle', 'card/CardOperationResult', 'card/CardPhotoCommands',
        'card/CardPinCommands', 'card/CardProfileCommands', 'card/CardProtocol', 'card/CardStatus', 'card/CardStatusCodec',
        'transport/CardTransport', 'card/PendingJournal', 'card/PersonalizeRequest', 'validation/PinFormat', 'card/PhotoImage', 'card/Profile', 'card/ProfileCodec',
        'card/ProfileCryptoMeta', 'card/ProfileMetaCodec', 'card/Receipt', 'card/ReceiptCodec', 'card/ReceiptExpectation',
        'card/ReceiptVerifier', 'card/Reconciliation', 'transport/SimulatorConnection', 'card/Sw', 'card/ReceiptReadRecovery',
        'card/CardOverviewReader', 'card/CardAdminCommands', 'card/StudentLedgerGate', 'card/LedgerAlignment',
        'card/CardPersonalizationCommands', 'card/CardEnrollmentCommands', 'card/CardProfileUpdateCommands', 'validation/ClassCode',
        'card/ProfileEditRequest', 'card/StateTransactionFlow', 'card/ReconciliationFlow',
        'virtualreader/protocol/VirtualReaderProtocol',
        'virtualreader/protocol/VirtualReaderSession')
    $sources = @('PersonalizeSelfTest.java', 'ApduErrorCases.java', 'ApduTransportCases.java',
        'GatewayTransportCases.java') | ForEach-Object { Join-Path $repoRoot "applet/scripts/$_" }
    $sources += $desktopClasses | ForEach-Object { Join-Path $repoRoot "desktop/src/main/java/vn/labaccess/desktop/$_.java" }
    & $javac -d $harnessDir -cp $sdkModules @sources 2>&1 | Tee-Object -FilePath (Join-Path $evidence 'javac-harness.log')
    if ($LASTEXITCODE -ne 0) { Stop-Run 'javac-harness' 'harness khong bien dich duoc' }
    Write-Host "      OK"

    Write-Host "[4/6] chay baseline tren Simulator rieng"
    $baselinePort = Get-FreePort
    $baselineSim = Start-Simulator $baselinePort (Join-Path $evidence 'simulator-baseline.log') $simProcesses
    $baselineLog = Join-Path $evidence 'matrix-baseline.log'
    $baselinePins = @(& $newPins @(8, 6, 6, 6))
    $pinBlock = $baselinePins -join "`n"
    $baselineArgs = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
        '-Dlabaccess.apduErrorsOnly=true', '-Dlabaccess.apduGroups=adgr03-baseline',
        "-Dlabaccess.simulator.port=$baselinePort", '--module-path', $sdkModules,
        '--add-modules', 'ALL-MODULE-PATH',
        '--add-opens', 'com.oracle.smartcardio.socketprovider/com.oracle.smartcardio.socket=ALL-UNNAMED',
        '-cp', $harnessDir, 'PersonalizeSelfTest',
        $props, $baseline.test.path, $scratch)
    ($pinBlock + "`n") | & $java @baselineArgs 2>&1 | Tee-Object -FilePath $baselineLog
    $baselineExit = $LASTEXITCODE
    Stop-Process -Id $baselineSim.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 500
    Write-Host "      exit=$baselineExit log=$baselineLog"

    Write-Host "[5/6] chay ma tran admin tren Simulator rieng"
    $adminPort = Get-FreePort
    $adminSim = Start-Simulator $adminPort (Join-Path $evidence 'simulator-admin.log') $simProcesses
    $adminLog = Join-Path $evidence 'matrix-admin.log'
    $adminPins = @(& $newPins @(8, 6, 6, 6))
    $pinBlock = $adminPins -join "`n"
    $adminArgs = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
        '-Dlabaccess.apduErrorsOnly=true', '-Dlabaccess.apduGroups=adgr03',
        "-Dlabaccess.simulator.port=$adminPort", '--module-path', $sdkModules,
        '--add-modules', 'ALL-MODULE-PATH',
        '--add-opens', 'com.oracle.smartcardio.socketprovider/com.oracle.smartcardio.socket=ALL-UNNAMED',
        '-cp', $harnessDir, 'PersonalizeSelfTest',
        $props, $current.test.path, $scratch)
    ($pinBlock + "`n") | & $java @adminArgs 2>&1 | Tee-Object -FilePath $adminLog
    $adminExit = $LASTEXITCODE
    Stop-Process -Id $adminSim.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 500
    Write-Host "      exit=$adminExit log=$adminLog"

    Write-Host "[6/6] so byte status giua baseline va CAP moi"
    $pattern = '^ADGR03-STATUS step=(\S+) public=([0-9A-F]+) auth=([0-9A-F]+)$'
    $baselineStatus = @{}
    Select-String -Path $baselineLog -Pattern $pattern | ForEach-Object {
        $baselineStatus[$_.Matches[0].Groups[1].Value] =
            "$($_.Matches[0].Groups[2].Value)|$($_.Matches[0].Groups[3].Value)"
    }
    $adminStatus = @{}
    Select-String -Path $adminLog -Pattern $pattern | ForEach-Object {
        $adminStatus[$_.Matches[0].Groups[1].Value] =
            "$($_.Matches[0].Groups[2].Value)|$($_.Matches[0].Groups[3].Value)"
    }
    $compare = @()
    foreach ($step in @('personalized', 'granted', 'started', 'ended')) {
        $left = $baselineStatus[$step]
        $right = $adminStatus[$step]
        $equal = $left -and $right -and ($left -ceq $right)
        $compare += [pscustomobject]@{ step = $step; equal = [bool]$equal
            baseline = $left; current = $right }
        Write-Host ("      {0,-13} {1}" -f $step, $(if ($equal) { 'IDENTICAL' } else { 'DIFFERENT' }))
    }
    $statusVerdict = if (@($compare | Where-Object { -not $_.equal }).Count -eq 0) { 'IDENTICAL' } else { 'DIFFERENT' }

    $adminSummary = (Select-String -Path $adminLog -Pattern '^ADGR03 cases=(\d+) failed=(\d+)').Matches
    $passCount = @(Select-String -Path $adminLog -Pattern '\| PASS$').Count
    $failCount = @(Select-String -Path $adminLog -Pattern '\| FAIL').Count
    $skipCount = @(Select-String -Path $adminLog -Pattern '\| SKIP').Count

    $allPins = @($baselinePins) + @($adminPins)
    $leaks = @()
    foreach ($file in Get-ChildItem -Path $evidence -File) {
        $text = Get-Content -Path $file.FullName -Raw -ErrorAction SilentlyContinue
        if (-not $text) { continue }
        foreach ($pin in $allPins) {
            if ($text.Contains($pin)) { $leaks += "$($file.Name):plain" }
            $hexPin = -join ($pin.ToCharArray() | ForEach-Object { '{0:X2}' -f [int]$_ })
            if ($text.Contains($hexPin)) { $leaks += "$($file.Name):hex" }
        }
    }
    $caseIds = @(Select-String -Path $adminLog -Pattern '^(ADGR03-[A-Z0-9-]+) \| (PASS|FAIL)' |
        ForEach-Object { "$($_.Matches[0].Groups[1].Value):$($_.Matches[0].Groups[2].Value)" })

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $javaVersion = (& $java -version 2>&1 | Select-Object -First 1)
    $ErrorActionPreference = $previousPreference
    $manifest = [pscustomobject]@{
        time = (Get-Date).ToString('o')
        head = (& git -C $repoRoot rev-parse HEAD).Trim()
        toolchain = [pscustomobject]@{
            javaHome = $JavaHome
            jcHomeTools = $JcHomeTools
            jcHomeSimulator = $JcHomeSimulator
            javaVersion = "$javaVersion"
        }
        caps = [pscustomobject]@{
            baseline = $baseline
            current = $current
            installedBaselineTestCap = $baseline.test.sha256
            installedCurrentTestCap = $current.test.sha256
        }
        runs = [pscustomobject]@{
            baseline = [pscustomobject]@{ group = 'adgr03-baseline'; port = $baselinePort
                exit = $baselineExit; log = $baselineLog }
            admin = [pscustomobject]@{ group = 'adgr03'; port = $adminPort
                exit = $adminExit; log = $adminLog }
        }
        statusByteCompare = [pscustomobject]@{ verdict = $statusVerdict; steps = $compare }
        caseIds = $caseIds
        sensitivePayloadHits = $leaks
        results = [pscustomobject]@{ pass = $passCount; fail = $failCount; skip = $skipCount
            adminSummary = if ($adminSummary) { $adminSummary[0].Value } else { $null } }
        pass = ($baselineExit -eq 0) -and ($adminExit -eq 0) -and ($statusVerdict -eq 'IDENTICAL') `
            -and ($leaks.Count -eq 0)
    }
    $manifestPath = Join-Path $evidence 'manifest.json'
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -Path $manifestPath -Encoding utf8
    Write-Host ""
    Write-Host "PASS=$passCount FAIL=$failCount SKIP=$skipCount status=$statusVerdict"
    Write-Host "manifest: $manifestPath"
    if (-not $manifest.pass) { exit 1 }
} finally {
    foreach ($process in $simProcesses) {
        if ($process -and -not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
    }
    if ($KeepScratch) {
        Write-Host "scratch giu lai: $scratch"
    } else {
        Remove-Item -Recurse -Force -Path $scratch -ErrorAction SilentlyContinue
    }
}
