[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$JcHomeSimulator = $env:JC_HOME_SIMULATOR
)

$ErrorActionPreference = 'Stop'
$appletRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runRoot = Join-Path $appletRoot ('build/profile-rollback-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $runRoot | Out-Null
$jcsw = Join-Path $JcHomeSimulator 'runtime/bin/jcsw.exe'
if (-not (Test-Path $jcsw)) { throw 'JC_HOME_SIMULATOR is invalid' }
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = $listener.LocalEndpoint.Port
$listener.Stop()
$log = Join-Path $runRoot 'simulator.log'
$manifest = [ordered]@{
    suite = 'profile-rollback-after-ciphertext-write'
    startedAt = (Get-Date).ToString('o')
    modes = @()
    caps = @()
    sourceManifest = @()
    pass = $false
}
foreach ($variant in @('profile-update-fault', 'profile-identity-update-fault')) {
    $cap = Join-Path $appletRoot "build/variants/$variant-deliverables/vn/labaccess/card/javacard/card.cap"
    if (-not (Test-Path $cap)) { throw "Missing CAP: $variant" }
    $manifest.caps += [ordered]@{ variant = $variant; path = $cap; sha256 = (Get-FileHash $cap -Algorithm SHA256).Hash }
}
$sourceFiles = @(Get-ChildItem (Join-Path $appletRoot 'src') -Recurse -File -Filter *.java)
$sourceFiles += @(Get-ChildItem $PSScriptRoot -File | Where-Object { $_.Extension -in @('.java', '.ps1', '.js', '.mjs') })
foreach ($file in ($sourceFiles | Sort-Object FullName)) {
    $manifest.sourceManifest += [ordered]@{ path = $file.FullName.Substring($appletRoot.Length + 1); sha256 = (Get-FileHash $file.FullName -Algorithm SHA256).Hash }
}
$process = Start-Process -FilePath $jcsw -ArgumentList "-p=$port" -WorkingDirectory $runRoot `
    -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru -WindowStyle Hidden
$manifest.simulator = [ordered]@{ pid = $process.Id; port = $port; workingDirectory = $runRoot; log = $log; stopped = $false }
try {
    Write-Host "Isolated rollback Simulator pid=$($process.Id) port=$port workingDirectory=$runRoot"
    $deadline = (Get-Date).AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 200
        if ($process.HasExited) { throw 'Rollback Simulator exited before ready' }
        $text = if (Test-Path $log) { Get-Content $log -Raw } else { '' }
        if ($text -match "server ready on port #$port") { break }
        if ((Get-Date) -ge $deadline) { throw 'Rollback Simulator readiness timed out' }
    } while ($true)
    $shellCommand = Get-Command pwsh.exe -ErrorAction SilentlyContinue
    $shellExecutable = if ($shellCommand) { $shellCommand.Source } else { (Get-Process -Id $PID).Path }
    foreach ($mode in @('display-name', 'identity')) {
        $runnerLog = Join-Path $runRoot "$mode.log"
        & $shellExecutable -NoProfile -File (Join-Path $PSScriptRoot 'test-personalize.ps1') `
            -JavaHome $JavaHome -JcHomeSimulator $JcHomeSimulator -SimulatorPort $port -ProfileRollback $mode *> $runnerLog
        $code = $LASTEXITCODE
        Get-Content $runnerLog | ForEach-Object { Write-Host $_ }
        $manifest.modes += [ordered]@{ mode = $mode; exitCode = $code; log = $runnerLog }
        if ($code -ne 0) { throw "Profile rollback $mode runtime suite failed" }
    }
    $manifest.pass = $true
} finally {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force }
    $process.WaitForExit()
    $manifest.simulator.stopped = -not [bool](Get-Process -Id $process.Id -ErrorAction SilentlyContinue)
    $manifest.finishedAt = (Get-Date).ToString('o')
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -Path (Join-Path $runRoot 'manifest.json') -Encoding utf8
    Write-Host "Rollback evidence: $runRoot"
}