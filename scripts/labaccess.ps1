[CmdletBinding()]
param(
    [ValidateSet('start', 'stop', 'status', 'install', 'build', 'apps', 'reader', 'reader-all', 'reader-staff', 'reader-kiosk', 'reader-insert', 'reader-eject')]
    [string]$Action = 'start',
    [string]$Root,
    [string]$Eeprom,
    [int]$SimPort = 9025,
    [int]$BackendPort = 3100,
    [switch]$ServicesOnly,
    [string]$ReaderDirectory,
    [ValidateSet('staff', 'kiosk')]
    [string]$ReaderSlot,
    [switch]$PinStdin
)

$ErrorActionPreference = 'Stop'

if (-not $Root) { $Root = Join-Path $PSScriptRoot '..' }
$Root = (Resolve-Path -LiteralPath $Root).Path
if (-not $Eeprom) { $Eeprom = Join-Path $Root 'logs\sim-labaccess\eeprom.bin' }
$Eeprom = [IO.Path]::GetFullPath($Eeprom)
$backend = Join-Path $Root 'backend'
$desktop = Join-Path $Root 'desktop'
$desktopBuild = Join-Path $desktop 'target\launcher'
$runDir = Join-Path $Root ('logs\run-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$stateDir = Join-Path $Root '.cache\launcher'
$roles = @('simulator', 'simulator-staff', 'simulator-kiosk', 'backend', 'StaffApp', 'KioskApp', 'VirtualReaderApp')
if (-not $ReaderDirectory) { $ReaderDirectory = Join-Path $Root 'secrets\virtual-reader' }
$ReaderDirectory = [IO.Path]::GetFullPath($ReaderDirectory)
$simulatorRole = 'simulator'
if ($Action -in @('reader-insert', 'reader-eject')) {
    if (-not $ReaderSlot) { throw 'Specify ReaderSlot for internal reader actions.' }
    $simulatorRole = "simulator-$ReaderSlot"
    if ($ReaderSlot -eq 'kiosk') { $SimPort++ }
}
. (Join-Path $PSScriptRoot 'managed-process.ps1')

function Resolve-LaunchTools {
    if (-not $env:JAVA_HOME -or -not $env:JC_HOME_SIMULATOR) {
        throw 'Set JAVA_HOME and JC_HOME_SIMULATOR first.'
    }
    $script:java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    $script:jcsw = Join-Path $env:JC_HOME_SIMULATOR 'runtime\bin\jcsw.exe'
    foreach ($path in @($java, $jcsw)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing executable: $path" }
    }
    $script:node = (Get-Command node.exe -ErrorAction Stop).Source
}

function Test-Listening {
    param([int]$Port)
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Wait-Port {
    param([int]$Port, [int]$Seconds, [System.Diagnostics.Process]$Process)
    for ($i = 0; $i -lt ($Seconds * 2); $i++) {
        $Process.Refresh()
        if ($Process.HasExited) { return $false }
        $owners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
        if ($owners.Count -gt 0) {
            if (@($owners | Where-Object OwningProcess -ne $Process.Id).Count -gt 0) {
                throw "Port $Port belongs to a different process."
            }
            return $true
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Assert-Stopped {
    foreach ($role in $roles) {
        if (Get-ManagedProcess -Role $role) { throw "LabAccess $role is running. Close/stop it first." }
    }
    $ports = @($SimPort, $BackendPort)
    if ($Action -in @('reader', 'reader-all')) { $ports += ($SimPort + 1) }
    foreach ($port in $ports) {
        if (Test-Listening $port) { throw "Port $port is already in use. No existing process was adopted or stopped." }
    }
}

function Build-Backend {
    Push-Location $backend
    try {
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    } finally { Pop-Location }
    if (-not (Test-Path (Join-Path $backend 'dist\main.js'))) { throw 'Missing Backend build output.' }
}

function Build-Desktop {
    Push-Location $desktop
    try {
        & mvn.cmd -o clean compile dependency:build-classpath "-Dlabaccess.buildDirectory=$desktopBuild" "-Dmdep.outputFile=$desktopBuild\runtime-classpath.txt" '-DincludeScope=runtime'
        if ($LASTEXITCODE -ne 0) { throw 'Desktop build failed. Check Maven dependencies/cache.' }
    } finally { Pop-Location }
    if (-not (Test-Path (Join-Path $desktopBuild 'runtime-classpath.txt'))) { throw 'Missing Desktop runtime classpath.' }
}

function Build-Host {
    Build-Backend
    Build-Desktop
}

function Build-All {
    Build-Host
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Root 'applet\scripts\build.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Applet build/verify failed.' }
}

function Start-Simulator {
    if (Test-Listening $SimPort) { throw "Simulator port $SimPort is occupied." }
    New-Item -ItemType Directory -Force -Path (Split-Path $Eeprom -Parent), $runDir | Out-Null
    $arguments = @("-p=$SimPort", ('"-o=' + $Eeprom + '"'))
    if (Test-Path -LiteralPath $Eeprom) {
        if ((Get-Item -LiteralPath $Eeprom).Length -le 0) { throw 'Existing EEPROM is empty; do not overwrite it.' }
        $arguments += ('"-i=' + $Eeprom + '"')
    }
    $process = Start-Process -FilePath $jcsw -ArgumentList $arguments `
        -WorkingDirectory (Split-Path $Eeprom -Parent) -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $runDir 'simulator.out.log') `
        -RedirectStandardError (Join-Path $runDir 'simulator.err.log')
    Register-ManagedProcess -Role $simulatorRole -Process $process
    if (Wait-Port $SimPort 20 $process) {
        Write-Host "[OK]   Simulator cong $SimPort (PID $($process.Id)), the: $Eeprom"
    } else {
        throw "Simulator failed to listen. See $runDir\simulator.err.log"
    }
}

function Start-Backend {
    if (Test-Listening $BackendPort) { throw "Backend port $BackendPort is occupied." }
    $previousPort = $env:PORT
    try {
        $env:PORT = [string]$BackendPort
        New-Item -ItemType Directory -Force -Path $runDir | Out-Null
        $process = Start-Process -FilePath $node -ArgumentList 'dist/main.js' -WorkingDirectory $backend -PassThru -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $runDir 'backend.out.log') `
            -RedirectStandardError (Join-Path $runDir 'backend.err.log')
        Register-ManagedProcess -Role 'backend' -Process $process
        if (Wait-Port $BackendPort 40 $process) {
            Write-Host "[OK]   Backend cong $BackendPort (PID $($process.Id))"
        } else {
            throw "Backend failed to listen. See $runDir\backend.err.log"
        }
    } finally { $env:PORT = $previousPort }
    try {
        $health = (Invoke-WebRequest -Uri "http://127.0.0.1:$BackendPort/health/database" -UseBasicParsing -TimeoutSec 15).Content
        Write-Host "[OK]   Database: $health"
    } catch {
        throw "Database health check failed: $($_.Exception.Message)"
    }
}

function Get-ModulePath {
    $deps = (Get-Content (Join-Path $desktopBuild 'runtime-classpath.txt') -Raw).Trim()
    return (Join-Path $desktopBuild 'classes') + ';' + $deps
}

function Start-Apps {
    param([switch]$VirtualReader, [string]$Only)
    $modulePath = Get-ModulePath
    $common = @('--enable-native-access=ALL-UNNAMED', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
        '-Dfile.encoding=UTF-8', "-Dlabaccess.host=127.0.0.1",
        "-Dlabaccess.backend=http://127.0.0.1:$BackendPort", '--module-path', ('"' + $modulePath + '"'))
    $apps = @('StaffApp', 'KioskApp')
    if ($VirtualReader) {
        $apps = @('VirtualReaderApp', 'StaffApp', 'KioskApp')
    }
    if ($Only) { $apps = @($Only) }
    New-Item -ItemType Directory -Force -Path $runDir | Out-Null
    foreach ($app in $apps) {
        $appPort = $SimPort
        $appArgs = @()
        if ($VirtualReader) {
            $appDirectory = $ReaderDirectory
            if ($app -eq 'StaffApp') { $appDirectory = Join-Path $ReaderDirectory 'staff' }
            if ($app -eq 'KioskApp') {
                $appDirectory = Join-Path $ReaderDirectory 'kiosk'
                $appPort++
            }
            $appArgs += ('"-Dlabaccess.virtualReader=' + $appDirectory + '"')
        }
        $appArgs += "-Dlabaccess.port=$appPort"
        $process = Start-Process -FilePath $java -WorkingDirectory $Root -PassThru `
            -ArgumentList ($common + $appArgs + @('-m', "vn.labaccess.desktop/vn.labaccess.desktop.$app")) `
            -RedirectStandardOutput (Join-Path $runDir "$app.out.log") `
            -RedirectStandardError (Join-Path $runDir "$app.err.log")
        Register-ManagedProcess -Role $app -Process $process
        Start-Sleep -Seconds 3
        if (Get-ManagedProcess -Role $app) {
            Write-Host "[OK]   $app (PID $($process.Id))"
        } else {
            throw "$app exited during startup. See $runDir\$app.err.log"
        }
    }
}

function Show-Status {
    Write-Host ""
    Write-Host "Simulator $SimPort : $(if (Test-Listening $SimPort) { 'dang chay' } else { 'tat' })"
    if ($SimPort -lt 65535) {
        Write-Host "Kiosk Simulator $($SimPort + 1) : $(if (Test-Listening ($SimPort + 1)) { 'dang chay' } else { 'tat' })"
    }
    Write-Host "Backend  $BackendPort : $(if (Test-Listening $BackendPort) { 'dang chay' } else { 'tat' })"
    foreach ($role in $roles) {
        $process = Get-ManagedProcess -Role $role
        if ($process) { Write-Host ("  {0} PID {1}" -f $role, $process.Id) }
        else { Write-Host "  ${role}: not managed/running" }
    }
}

function Stop-Everything {
    foreach ($role in @('KioskApp', 'StaffApp', 'VirtualReaderApp', 'backend', 'simulator', 'simulator-staff', 'simulator-kiosk')) {
        Stop-ManagedProcess -Role $role -Graceful:($role -in @('KioskApp', 'StaffApp', 'VirtualReaderApp'))
    }
    Start-Sleep -Seconds 2
    Show-Status
}

function Install-Applet {
    param([switch]$Reader)
    $sim = Get-ManagedProcess -Role $simulatorRole
    if (-not $sim -or -not (Wait-Port $SimPort 1 $sim)) {
        throw 'Start the managed Simulator before installing.'
    }
    if (-not $Reader) {
        foreach ($role in @('StaffApp', 'KioskApp', 'VirtualReaderApp')) {
            if (Get-ManagedProcess -Role $role) {
                throw 'Close Staff, Kiosk and Virtual Reader before manual installation.'
            }
        }
    }
    $script = Join-Path $Root 'applet\scripts\install.ps1'
    $cap = Join-Path $Root 'applet\build\deliverables\vn\labaccess\card\javacard\card.cap'
    if (-not (Test-Path -LiteralPath $cap -PathType Leaf)) { throw 'Build Applet first.' }
    $capTime = (Get-Item -LiteralPath $cap).LastWriteTimeUtc
    $inputs = Get-ChildItem -Path (Join-Path $Root 'applet\src'), (Join-Path $Root 'applet\configurations') -Recurse -File
    if (@($inputs | Where-Object LastWriteTimeUtc -gt $capTime).Count -gt 0) {
        throw 'CAP is older than Applet source/configuration. Build again before installing.'
    }
    $installArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $script, '-CapPath', $cap, '-HostAddress', '127.0.0.1', '-Port', $SimPort)
    if ($PinStdin) {
        $pin = [Console]::In.ReadLine()
        try {
            if ($pin -notmatch '^[0-9]{8}$') { throw 'Invalid provisioning PIN.' }
            $pin | & powershell.exe @installArgs -PinStdin
        } finally { $pin = $null }
    } else {
        & powershell.exe @installArgs
    }
    if ($LASTEXITCODE -ne 0) { throw 'Applet install failed.' }
    Write-Host ""
    if (-not $Reader) {
        Write-Host "Reopen Staff/Kiosk: powershell -File scripts\labaccess.ps1 -Action apps"
    }
}

function Assert-Reader {
    if (-not (Get-ManagedProcess -Role 'VirtualReaderApp')) { throw 'Virtual Reader is not running.' }
    $settings = Get-Content -LiteralPath (Join-Path $stateDir 'reader-settings.json') -Raw | ConvertFrom-Json
    $expectedPort = [int]$settings.SimPort
    if ($ReaderSlot -eq 'kiosk') { $expectedPort++ }
    if ($ReaderDirectory -ne $settings.Directory -or $SimPort -ne $expectedPort) {
        throw 'Reader directory or port does not match the running reader.'
    }
    $slotDirectory = Join-Path $ReaderDirectory $ReaderSlot
    $selection = Join-Path $slotDirectory 'selection.txt'
    if (-not (Test-Path -LiteralPath $selection) -or (Get-Content -LiteralPath $selection -Raw).Trim() -ne 'NONE') {
        throw 'Virtual Reader must withdraw the current selection first.'
    }
    $sessionPath = Join-Path $slotDirectory 'session.lock'
    if (-not (Test-Path -LiteralPath $sessionPath)) { throw 'Virtual Reader has not acquired the session lease.' }
    $session = [IO.File]::Open($sessionPath, 'Open', 'ReadWrite', 'ReadWrite')
    $held = $false
    try {
        try { $session.Lock(0, 1) } catch [System.IO.IOException] { $held = $true }
        if (-not $held) {
            $session.Unlock(0, 1)
            throw 'Virtual Reader session lease is not held.'
        }
    } finally { $session.Dispose() }
}

function Read-ReaderSettings {
    if (-not (Get-ManagedProcess -Role 'VirtualReaderApp')) { throw 'Start Virtual Reader first.' }
    $settings = Get-Content -LiteralPath (Join-Path $stateDir 'reader-settings.json') -Raw | ConvertFrom-Json
    $script:ReaderDirectory = [IO.Path]::GetFullPath($settings.Directory)
    $script:SimPort = [int]$settings.SimPort
    $script:BackendPort = [int]$settings.BackendPort
    if ($SimPort -lt 1 -or $SimPort -ge 65535 -or $BackendPort -lt 1 -or $BackendPort -gt 65535 -or $BackendPort -in @($SimPort, ($SimPort + 1))) {
        throw 'Invalid running Reader settings.'
    }
}

function Install-SelectedStaffCard {
    Read-ReaderSettings
    $script:simulatorRole = 'simulator-staff'
    $slot = Join-Path $ReaderDirectory 'staff'
    $lease = [IO.File]::Open((Join-Path $slot 'session.lock'), 'OpenOrCreate', 'ReadWrite', 'ReadWrite')
    $locked = $false
    try {
        try { $lease.Lock(0, 1); $locked = $true } catch [System.IO.IOException] {
            throw 'Disconnect Staff from the card before installing.'
        }
        $selection = (Get-Content -LiteralPath (Join-Path $slot 'selection.txt') -Raw).Trim()
        $card = [guid]::Empty
        if (-not [guid]::TryParseExact($selection, 'D', [ref]$card)) { throw 'Select a card in Staff Reader first.' }
        if (-not (Test-Path -LiteralPath (Join-Path $ReaderDirectory "$selection.properties") -PathType Leaf)) {
            throw 'Selected card is not in the catalog.'
        }
        foreach ($journal in @('staff-journal.txt', 'pending-journal.txt')) {
            if (Test-Path -LiteralPath (Join-Path (Join-Path $env:USERPROFILE '.labaccess') $journal)) {
                throw 'Resolve pending journal before installing.'
            }
        }
        Install-Applet -Reader
    } finally {
        if ($locked) { $lease.Unlock(0, 1) }
        $lease.Dispose()
    }
}

function Start-VirtualReader {
    $reader = Get-ManagedProcess -Role 'VirtualReaderApp'
    if ($reader) {
        Read-ReaderSettings
        Write-Host "[OK]   VirtualReaderApp already running (PID $($reader.Id))"
        return
    }
    Assert-Stopped
    Resolve-LaunchTools
    Build-Desktop
    foreach ($slot in @('staff', 'kiosk')) {
        $slotDirectory = Join-Path $ReaderDirectory $slot
        New-Item -ItemType Directory -Force -Path $slotDirectory | Out-Null
        [IO.File]::WriteAllText((Join-Path $slotDirectory 'selection.txt'), 'NONE')
    }
    @{ Directory = $ReaderDirectory; SimPort = $SimPort; BackendPort = $BackendPort } | ConvertTo-Json |
        Set-Content -LiteralPath (Join-Path $stateDir 'reader-settings.json') -Encoding UTF8
    Start-Apps -VirtualReader -Only 'VirtualReaderApp'
    Write-Host "Virtual Reader ready. Logs: $runDir"
}

function Start-ReaderClient {
    param([string]$App)
    Read-ReaderSettings
    if (Get-ManagedProcess -Role 'simulator') { throw 'Fixed EEPROM mode is running. Stop it first.' }
    Resolve-LaunchTools
    $server = Get-ManagedProcess -Role 'backend'
    if (-not $server) {
        if (Test-Listening $BackendPort) { throw "Backend port $BackendPort is occupied by an unmanaged process." }
        Build-Backend
        Start-Backend
    }
    elseif (-not (Wait-Port $BackendPort 1 $server)) { throw 'Backend is not ready.' }
    $client = Get-ManagedProcess -Role $App
    if ($client) {
        Write-Host "[OK]   $App already running (PID $($client.Id))"
        return
    }
    Start-Apps -VirtualReader -Only $App
}

function Open-LauncherLock {
    $path = Join-Path $stateDir 'launcher.lock'
    $seconds = if ($Action -in @('reader-all', 'reader', 'reader-staff', 'reader-kiosk')) { 120 } else { 0 }
    $deadline = [datetime]::UtcNow.AddSeconds($seconds)
    while ($true) {
        try { return [IO.File]::Open($path, 'OpenOrCreate', 'ReadWrite', 'None') }
        catch [IO.IOException] {
            if ([datetime]::UtcNow -ge $deadline) { throw 'Another launcher operation is active. Retry after it finishes.' }
            Start-Sleep -Milliseconds 250
        }
    }
}

if ($SimPort -lt 1 -or $SimPort -gt 65535 -or $BackendPort -lt 1 -or $BackendPort -gt 65535 -or $SimPort -eq $BackendPort) {
    throw 'Invalid or duplicate ports.'
}
if ($Action -in @('reader', 'reader-all') -and ($SimPort -ge 65535 -or ($SimPort + 1) -eq $BackendPort)) {
    throw 'Invalid or duplicate Kiosk Simulator port.'
}
New-Item -ItemType Directory -Force -Path $stateDir | Out-Null
$lock = Open-LauncherLock
try {
switch ($Action) {
    'start' {
        Write-Host "=== LabAccess: khoi dong ==="
        Assert-Stopped
        Resolve-LaunchTools
        Build-Host
        Start-Simulator
        Start-Backend
        if (-not $ServicesOnly) { Start-Apps }
        Write-Host ""
        Write-Host "Log phien nay: $runDir"
        Write-Host "CAP da cai khong tu thay doi. The trang: dong hai app, chay cmd\install-applet.cmd."
    }
    'build' { Assert-Stopped; Build-All }
    'reader' { Start-VirtualReader }
    'reader-all' {
        Write-Host '=== LabAccess: Virtual Reader + Staff + Kiosk ==='
        Start-VirtualReader
        Start-ReaderClient -App 'StaffApp'
        Start-ReaderClient -App 'KioskApp'
        Write-Host 'LabAccess ready. Select a card in Virtual Reader before connecting.'
    }
    'reader-insert' {
        Assert-Reader
        Resolve-LaunchTools
        if (Get-ManagedProcess -Role $simulatorRole) { throw 'Eject the current Simulator first.' }
        Start-Simulator
    }
    'reader-eject' {
        Assert-Reader
        Stop-ManagedProcess -Role $simulatorRole
        if (Test-Listening $SimPort) { throw 'Simulator port is still occupied.' }
    }
    'reader-staff' { Start-ReaderClient -App 'StaffApp' }
    'reader-kiosk' { Start-ReaderClient -App 'KioskApp' }
    'apps' {
        if (Get-ManagedProcess -Role 'VirtualReaderApp') { throw 'Use Virtual Reader to connect cards.' }
        Resolve-LaunchTools
        foreach ($role in @('StaffApp', 'KioskApp')) {
            if (Get-ManagedProcess -Role $role) { throw "Close $role first." }
        }
        $sim = Get-ManagedProcess -Role 'simulator'
        $server = Get-ManagedProcess -Role 'backend'
        if (-not $sim -or -not $server -or -not (Wait-Port $SimPort 1 $sim) -or -not (Wait-Port $BackendPort 1 $server)) {
            throw 'Start managed services first.'
        }
        Start-Apps
    }
    'stop' { Stop-Everything }
    'status' { Show-Status }
    'install' {
        if (Get-ManagedProcess -Role 'VirtualReaderApp') { Install-SelectedStaffCard }
        else { Install-Applet }
    }
}
} finally { $lock.Dispose() }
