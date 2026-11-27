[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$JcHomeSimulator = $env:JC_HOME_SIMULATOR,
    [switch]$FullSuite
)

$ErrorActionPreference = 'Stop'
$appletRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runRoot = Join-Path $appletRoot ('build/profile-v2-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $runRoot | Out-Null
$jcsw = Join-Path $JcHomeSimulator 'runtime/bin/jcsw.exe'
if (-not (Test-Path $jcsw)) { throw 'JC_HOME_SIMULATOR is invalid' }
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = $listener.LocalEndpoint.Port
$listener.Stop()
$log = Join-Path $runRoot 'simulator.log'
$process = Start-Process -FilePath $jcsw -ArgumentList "-p=$port" -WorkingDirectory $runRoot `
    -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru -WindowStyle Hidden
$suite = if ($FullSuite) { 'personalize-full' } else { 'profile-v2' }
$previousEvidence = $env:LABACCESS_PROFILE_V2_EVIDENCE
$env:LABACCESS_PROFILE_V2_EVIDENCE = Join-Path $runRoot 'profile-signatures.jsonl'
$manifest = [ordered]@{
    suite = $suite
    startedAt = (Get-Date).ToString('o')
    simulator = [ordered]@{ pid = $process.Id; port = $port; workingDirectory = $runRoot; log = $log }
    signatureEvidence = $env:LABACCESS_PROFILE_V2_EVIDENCE
    runnerExit = $null
    pass = $false
}
try {
    Write-Host "Isolated Simulator suite=$suite pid=$($process.Id) port=$port workingDirectory=$runRoot"
    $deadline = (Get-Date).AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 200
        if ($process.HasExited) { throw 'Isolated Simulator exited before ready' }
        $text = if (Test-Path $log) { Get-Content $log -Raw } else { '' }
        if ($text -match "server ready on port #$port") { break }
        if ((Get-Date) -ge $deadline) { throw 'Isolated Simulator readiness timed out' }
    } while ($true)
    $runner = Join-Path $PSScriptRoot 'test-personalize.ps1'
    $runnerArgs = @('-NoProfile', '-File', $runner, '-JavaHome', $JavaHome,
        '-JcHomeSimulator', $JcHomeSimulator, '-SimulatorPort', [string]$port)
    if (-not $FullSuite) { $runnerArgs += '-ProfileV2Only' }
    $shellCommand = Get-Command pwsh.exe -ErrorAction SilentlyContinue
    $shellExecutable = if ($shellCommand) { $shellCommand.Source } else { (Get-Process -Id $PID).Path }
    & $shellExecutable @runnerArgs
    $manifest.runnerExit = $LASTEXITCODE
    if ($LASTEXITCODE -ne 0) { throw "$suite runtime suite failed" }
    if (-not $FullSuite) {
        $nodeVerifier = Join-Path $PSScriptRoot 'verify-profile-v2-signatures.js'
        & node $nodeVerifier $env:LABACCESS_PROFILE_V2_EVIDENCE
        $manifest.nodeSignatureExit = $LASTEXITCODE
        if ($LASTEXITCODE -ne 0) { throw 'Independent Node profile signature verification failed' }
    }
    $manifest.pass = $true
} finally {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force }
    $env:LABACCESS_PROFILE_V2_EVIDENCE = $previousEvidence
    $manifest.finishedAt = (Get-Date).ToString('o')
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -Path (Join-Path $runRoot 'manifest.json') -Encoding utf8
    Write-Host "Isolation evidence: $runRoot"
}
