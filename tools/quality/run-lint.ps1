# LabAccess Java lint. Compatible with Windows PowerShell 5.1 and PowerShell 7.
[CmdletBinding()]
param(
    [ValidateSet('all', 'applet', 'desktop')]
    [string]$Target = 'all',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$CheckstyleVersion = '10.21.0'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$configDir = Join-Path $repoRoot 'config/checkstyle'
$cacheDir = Join-Path $repoRoot '.cache/checkstyle'
$jarPath = Join-Path $cacheDir "checkstyle-$CheckstyleVersion-all.jar"

if ($JavaHome) {
    $javaName = if ($env:OS -eq 'Windows_NT') { 'java.exe' } else { 'java' }
    $java = Join-Path $JavaHome "bin/$javaName"
    if (-not (Test-Path $java)) { throw "Invalid JavaHome: $JavaHome" }
} else {
    $javaCommand = Get-Command java -ErrorAction Stop
    $java = $javaCommand.Source
}

# Cache dependencies; never commit vendor binaries.
if (-not (Test-Path $jarPath)) {
    New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null
    $url = "https://github.com/checkstyle/checkstyle/releases/download/checkstyle-$CheckstyleVersion/checkstyle-$CheckstyleVersion-all.jar"
    Write-Host "Downloading Checkstyle $CheckstyleVersion ..."
    Invoke-WebRequest -Uri $url -OutFile $jarPath -UseBasicParsing
    if ((Get-Item $jarPath).Length -lt 10MB) {
        throw "Invalid Checkstyle download at $jarPath; inspect the cached file before retrying."
    }
}

function Invoke-Checkstyle {
    param([string]$Name, [string]$SourceDir, [string]$ConfigFile)

    if (-not (Test-Path $SourceDir)) {
        Write-Host "[$Name] SKIP: source directory does not exist."
        return $null
    }
    $files = @(Get-ChildItem -Path $SourceDir -Recurse -Filter *.java -File |
        Where-Object { $_.Name -ne 'module-info.java' } |
        ForEach-Object { $_.FullName })
    if ($files.Count -eq 0) {
        Write-Host "[$Name] SKIP: no Java source files."
        return $null
    }
    $javaArgs = @("-Dconfig_loc=$configDir", '-jar', $jarPath, '-c', $ConfigFile) + $files
    Write-Host "Checkstyle: $Name ($($files.Count) files)"
    # Keep audit stdout out of the function's numeric return value.
    & $java @javaArgs | Out-Host
    $lintExit = $LASTEXITCODE
    return $lintExit
}

$results = [ordered]@{}
if ($Target -in @('all', 'applet')) {
    $results['applet'] = Invoke-Checkstyle -Name 'Applet' `
        -SourceDir (Join-Path $repoRoot 'applet/src') `
        -ConfigFile (Join-Path $configDir 'checkstyle-applet.xml')
}
if ($Target -in @('all', 'desktop')) {
    $results['desktop'] = Invoke-Checkstyle -Name 'Desktop' `
        -SourceDir (Join-Path $repoRoot 'desktop/src/main/java') `
        -ConfigFile (Join-Path $configDir 'checkstyle-desktop.xml')
}

$failed = $false
foreach ($key in $results.Keys) {
    $code = $results[$key]
    if ($null -eq $code) {
        Write-Host "${key}: SKIP"
    } elseif ($code -eq 0) {
        Write-Host "${key}: PASS"
    } else {
        Write-Host "${key}: FAIL (exit $code)"
        $failed = $true
    }
}
if ($failed) { exit 1 }
exit 0
