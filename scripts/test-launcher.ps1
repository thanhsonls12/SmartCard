[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$stateDir = Join-Path $PSScriptRoot ('..\.cache\launcher-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stateDir -Force | Out-Null
. (Join-Path $PSScriptRoot 'managed-process.ps1')
$children = @()
$passed = 0

function Assert-True {
    param([bool]$Condition, [string]$Name)
    if (-not $Condition) { throw "FAIL: $Name" }
    $script:passed++
    Write-Host "PASS: $Name"
}

try {
    $exe = (Get-Command powershell.exe).Source
    $owned = Start-Process -FilePath $exe -ArgumentList '-NoProfile -Command "Start-Sleep -Seconds 90"' -PassThru -WindowStyle Hidden
    $children += $owned
    $unrelated = Start-Process -FilePath $exe -ArgumentList '-NoProfile -Command "Start-Sleep -Seconds 90"' -PassThru -WindowStyle Hidden
    $children += $unrelated
    Assert-True (-not (Get-ManagedProcess -Role 'backend')) 'No record means no managed process'
    Register-ManagedProcess -Role 'backend' -Process $owned
    Assert-True ((Get-ManagedProcess -Role 'backend').Id -eq $owned.Id) 'Recorded identity is recognized'
    $recordPath = Join-Path $stateDir 'backend.json'
    $record = Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
    $record.Pid = $unrelated.Id
    $record.StartTicks = '1'
    $record | ConvertTo-Json | Set-Content -LiteralPath $recordPath
    Assert-True (-not (Get-ManagedProcess -Role 'backend')) 'Stale PID record is ignored'
    Stop-ManagedProcess -Role 'backend'
    $unrelated.Refresh()
    Assert-True (-not $unrelated.HasExited) 'Stale record does not stop unrelated process'
    Register-ManagedProcess -Role 'backend' -Process $owned
    $record = Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
    $record.Executable = 'C:\not-the-executable.exe'
    $record | ConvertTo-Json | Set-Content -LiteralPath $recordPath
    $rejected = $false
    try { Stop-ManagedProcess -Role 'backend' } catch { $rejected = $true }
    Assert-True $rejected 'Mismatched executable is rejected'
    $owned.Refresh()
    Assert-True (-not $owned.HasExited) 'Identity mismatch does not stop process'
    Register-ManagedProcess -Role 'backend' -Process $owned
    Stop-ManagedProcess -Role 'backend'
    $owned.Refresh()
    $unrelated.Refresh()
    Assert-True $owned.HasExited 'Verified owned process is stopped'
    Assert-True (-not $unrelated.HasExited) 'Unrelated same-executable process survives'
    Assert-True (-not (Get-ManagedProcess -Role 'backend')) 'Exited record is not considered running'
    Write-Host "PASS: $passed checks"
} finally {
    foreach ($child in $children) {
        $child.Refresh()
        if (-not $child.HasExited) {
            $child.Kill()
            $null = $child.WaitForExit(10000)
        }
    }
}
