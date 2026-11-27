function Get-ManagedProcess {
    param([string]$Role)
    $recordPath = Join-Path $stateDir "$Role.json"
    if (-not (Test-Path -LiteralPath $recordPath)) { return }
    $record = Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
    if (-not $record.Pid -or -not $record.StartTicks -or -not $record.Executable) {
        throw "Invalid launcher record: $recordPath"
    }
    $process = Get-Process -Id $record.Pid -ErrorAction SilentlyContinue
    if (-not $process) { return }
    try { $null = $process.Handle } catch {
        if ($process.HasExited) { return }
        throw
    }
    if ($process.StartTime.ToUniversalTime().Ticks.ToString() -ne $record.StartTicks) { return }
    if (-not $process.Path -or $process.Path -ne $record.Executable) {
        throw "Cannot verify process identity for $Role."
    }
    return $process
}

function Register-ManagedProcess {
    param([string]$Role, [System.Diagnostics.Process]$Process)
    $recordPath = Join-Path $stateDir "$Role.json"
    $temporary = "$recordPath.tmp"
    try {
        $null = $Process.Handle
        $ticks = $Process.StartTime.ToUniversalTime().Ticks.ToString()
        $executable = $null
        for ($i = 0; $i -lt 20; $i++) {
            if ($Process.HasExited) { throw "$Role exited during startup." }
            $fresh = Get-Process -Id $Process.Id -ErrorAction Stop
            if ($fresh.StartTime.ToUniversalTime().Ticks.ToString() -ne $ticks) {
                throw "$Role process identity changed during startup."
            }
            $executable = $fresh.Path
            if ($executable) { break }
            Start-Sleep -Milliseconds 100
        }
        if (-not $executable) { throw "Cannot record identity for $Role." }
        $record = @{ Pid = $Process.Id; StartTicks = $ticks; Executable = $executable }
        $record | ConvertTo-Json | Set-Content -LiteralPath $temporary -Encoding UTF8
        Move-Item -LiteralPath $temporary -Destination $recordPath -Force
    } catch {
        if (-not $Process.HasExited) {
            $Process.Kill()
            $null = $Process.WaitForExit(10000)
        }
        throw
    }
}

function Stop-ManagedProcess {
    param([string]$Role, [switch]$Graceful)
    $process = Get-ManagedProcess -Role $Role
    if (-not $process) { return }
    Write-Host "[..] Stop $Role PID $($process.Id)"
    if ($Graceful) {
        if (-not $process.CloseMainWindow() -or -not $process.WaitForExit(20000)) {
            throw "Close $Role manually and retry stop. Dependencies remain running."
        }
    } else {
        $process.Kill()
        if (-not $process.WaitForExit(10000)) { throw "Timeout stopping $Role." }
    }
}
