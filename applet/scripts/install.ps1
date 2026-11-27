[CmdletBinding()]
param(
    [ValidateSet('install', 'replace')]
    [string]$Mode = 'install',
    [string]$CapPath,
    [string]$HostAddress = '127.0.0.1',
    [int]$Port = 9025,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$JcHomeSimulator = $env:JC_HOME_SIMULATOR,
    [string]$PackageAid,
    [string]$AppletAid,
    [string]$InstanceAid,
    [switch]$SkipVerify,
    [switch]$PinStdin
)

$ErrorActionPreference = 'Stop'

$appletRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$buildDir = Join-Path $appletRoot 'build'
$toolsOut = Join-Path $buildDir 'tools'
$logDir = Join-Path $buildDir 'logs'
$helperSrc = Join-Path $PSScriptRoot 'InstallApplet.java'

function Stop-Install {
    param([string]$Message)
    Write-Host ""
    Write-Host "INSTALL THAT BAI: $Message" -ForegroundColor Red
    exit 1
}

function Read-AdminPin {
    if ($PinStdin) {
        return [Console]::In.ReadLine()
    }
    $secure = Read-Host -AsSecureString 'Nhap admin PIN, 8 chu so, khong hien tren man hinh'
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
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

if ([string]::IsNullOrWhiteSpace($JavaHome) -or -not (Test-Path (Join-Path $JavaHome 'bin/java.exe'))) {
    Stop-Install "JAVA_HOME khong hop le: '$JavaHome'. Truyen -JavaHome hoac dat bien moi truong."
}
if ([string]::IsNullOrWhiteSpace($JcHomeSimulator) -or -not (Test-Path $JcHomeSimulator)) {
    Stop-Install "JC_HOME_SIMULATOR khong hop le: '$JcHomeSimulator'."
}

$clientDir = Join-Path $JcHomeSimulator 'client'
$amserviceJar = Join-Path $clientDir 'AMService/amservice.jar'
$socketJar = Join-Path $clientDir 'COMService/socketprovider.jar'
$propsFile = Join-Path $JcHomeSimulator 'samples/client.config.properties'
foreach ($p in @($amserviceJar, $socketJar, $propsFile)) {
    if (-not (Test-Path $p)) {
        Stop-Install "Khong thay file bat buoc: $p"
    }
}

if (-not $CapPath) {
    $found = @(Get-ChildItem -Path (Join-Path $buildDir 'deliverables') -Recurse -Filter *.cap -File -ErrorAction SilentlyContinue)
    if ($found.Count -eq 0) {
        Stop-Install "Khong thay file .cap trong $buildDir\deliverables. Chay build.ps1 truoc."
    }
    $CapPath = $found[0].FullName
}
if (-not (Test-Path $CapPath)) {
    Stop-Install "Khong thay CAP: $CapPath"
}

New-Item -ItemType Directory -Force -Path $toolsOut, $logDir | Out-Null
$classpath = "$amserviceJar;$socketJar"

Write-Host "=== LabAccess applet install ==="
Write-Host "CAP     : $CapPath"
Write-Host "Mode    : $Mode"
Write-Host "Target  : $HostAddress`:$Port"
Write-Host ""

Write-Host "[1/2] compile helper"
$javac = Join-Path $JavaHome 'bin/javac.exe'
$java = Join-Path $JavaHome 'bin/java.exe'
$r = Invoke-Native { & $javac -g -d $toolsOut -cp $classpath $helperSrc }
$r.Output | ForEach-Object { Write-Host "      $_" }
if ($r.ExitCode -ne 0) {
    Stop-Install "javac helper tra exit code $($r.ExitCode)"
}
Write-Host "      OK: $toolsOut"
Write-Host ""

Write-Host "[2/2] install"
$adminPin = Read-AdminPin
if ($adminPin -notmatch '^[0-9]{8}$') {
    $adminPin = $null
    Stop-Install "Admin PIN phai la dung 8 chu so ASCII tu 0 den 9."
}

$log = Join-Path $logDir ('install-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.log')
$capHash = (Get-FileHash -LiteralPath $CapPath -Algorithm SHA256).Hash
"CAP SHA256: $capHash" | Set-Content -LiteralPath $log -Encoding UTF8
$aidArgs = @()
if ($PackageAid) { $aidArgs += "-pkg=$PackageAid" }
if ($AppletAid) { $aidArgs += "-applet=$AppletAid" }
if ($InstanceAid) { $aidArgs += "-inst=$InstanceAid" }
if ($SkipVerify) { $aidArgs += '-skip-verify' }
$r = Invoke-Native {
    $adminPin | & $java --module-path "$amserviceJar;$socketJar" --add-modules ALL-MODULE-PATH `
        -cp $toolsOut InstallApplet `
        "-mode=$Mode" "-cap=$CapPath" "-host=$HostAddress`:$Port" "-props=$propsFile" @aidArgs
}
$adminPin = $null
$r.Output | Out-File -LiteralPath $log -Append -Encoding UTF8
$r.Output | ForEach-Object { Write-Host $_ }

Write-Host ""
Write-Host "Log: $log"
exit $r.ExitCode
