[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$JcHomeTools = $env:JC_HOME_TOOLS,
    [string]$JcHomeSimulator = $env:JC_HOME_SIMULATOR,
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'

$appletRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$buildDir = Join-Path $appletRoot 'build'
$classesDir = Join-Path $buildDir 'classes'
$deliverablesDir = Join-Path $buildDir 'deliverables'
$confDir = Join-Path $appletRoot 'configurations'
$srcDir = Join-Path $appletRoot 'src'
$logDir = Join-Path $buildDir 'logs'

function Stop-Build {
    param([string]$Step, [string]$Message)
    Write-Host ""
    Write-Host "BUILD THAT BAI o buoc: $Step" -ForegroundColor Red
    Write-Host "  $Message" -ForegroundColor Red
    exit 1
}

function Resolve-Required {
    param([string]$Value, [string]$Name, [string]$Marker)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        Stop-Build 'thiet lap' "Thieu $Name. Truyen tham so hoac dat bien moi truong $Name."
    }
    if (-not (Test-Path $Value)) {
        Stop-Build 'thiet lap' "$Name khong ton tai: $Value"
    }
    if ($Marker -and -not (Test-Path (Join-Path $Value $Marker))) {
        Stop-Build 'thiet lap' "$Name khong dung: khong thay $Marker trong $Value"
    }
    return (Resolve-Path $Value).Path
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

Write-Host "=== LabAccess applet build ==="

$javaHomePath = Resolve-Required -Value $JavaHome -Name 'JAVA_HOME' -Marker 'bin/java.exe'
$toolsPath = Resolve-Required -Value $JcHomeTools -Name 'JC_HOME_TOOLS' -Marker 'bin/converter.bat'
if (-not [string]::IsNullOrWhiteSpace($JcHomeSimulator)) {
    $null = Resolve-Required -Value $JcHomeSimulator -Name 'JC_HOME_SIMULATOR' -Marker 'runtime/bin/jcsw.exe'
}

$javac = Join-Path $javaHomePath 'bin/javac.exe'
$converter = Join-Path $toolsPath 'bin/converter.bat'
$verifier = Join-Path $toolsPath 'bin/verifycap.bat'
$apiJar = Join-Path $toolsPath 'lib/api_classic-3.2.0.jar'

foreach ($tool in @($javac, $converter, $verifier, $apiJar)) {
    if (-not (Test-Path $tool)) {
        Stop-Build 'thiet lap' "Khong thay cong cu bat buoc: $tool"
    }
}

Write-Host "JDK          : $javaHomePath"
Write-Host "JC_HOME_TOOLS: $toolsPath"
Write-Host "Applet root  : $appletRoot"
Write-Host ""

if ($Clean -and (Test-Path $buildDir)) {
    Write-Host "Xoa $buildDir"
    Remove-Item -Recurse -Force $buildDir
}
foreach ($dir in @($buildDir, $classesDir, $deliverablesDir, $logDir)) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}

Write-Host "[1/3] compile"
$sources = @(Get-ChildItem -Path $srcDir -Recurse -Filter *.java -File | ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) {
    Stop-Build 'compile' "Khong co file .java nao trong $srcDir"
}

Get-ChildItem -Path $classesDir -Recurse -Filter *.class -File -ErrorAction SilentlyContinue |
    Remove-Item -Force

$javacLog = Join-Path $logDir 'javac.log'
$r = Invoke-Native { & $javac -g -d $classesDir -source 10 -target 10 -Xlint:-options -cp $apiJar @sources }
$r.Output | ForEach-Object { Write-Host "      $_" }
$r.Output | Set-Content -Path $javacLog -Encoding UTF8
if ($r.ExitCode -ne 0) {
    Stop-Build 'compile' "javac tra exit code $($r.ExitCode) (xem $javacLog)"
}
$classCount = @(Get-ChildItem -Path $classesDir -Recurse -Filter *.class -File).Count
if ($classCount -eq 0) {
    Stop-Build 'compile' "javac khong sinh ra file .class nao"
}
Write-Host "      OK: $($sources.Count) file nguon, $classCount class"
Write-Host ""

Write-Host "[2/3] converter"
$convertLog = Join-Path $logDir 'converter.log'
$confFile = Join-Path $confDir 'labaccess.conf'
if (-not (Test-Path $confFile)) {
    Stop-Build 'converter' "Khong thay file cau hinh: $confFile"
}

Push-Location $confDir
try {
    $r = Invoke-Native { & $converter -config 'labaccess.conf' }
} finally {
    Pop-Location
}
$r.Output | Set-Content -Path $convertLog -Encoding UTF8
$r.Output | ForEach-Object { Write-Host "      $_" }

if ($r.ExitCode -ne 0) {
    Stop-Build 'converter' "converter tra exit code $($r.ExitCode) (xem $convertLog)"
}
if (-not ($r.Output -match 'conversion completed with 0 errors and 0 warnings')) {
    Stop-Build 'converter' "converter khong bao '0 errors and 0 warnings' (xem $convertLog)"
}

$testConfFile = Join-Path $confDir 'labaccess-test.conf'
if (-not (Test-Path $testConfFile)) {
    Stop-Build 'converter' "Khong thay file cau hinh test: $testConfFile"
}
$testCapDir = Join-Path $buildDir 'test-deliverables'
New-Item -ItemType Directory -Force -Path $testCapDir | Out-Null
Push-Location $confDir
try {
    $testConvert = Invoke-Native { & $converter -config 'labaccess-test.conf' }
} finally {
    Pop-Location
}
$testConvertLog = Join-Path $logDir 'converter-test.log'
$testConvert.Output | Set-Content -Path $testConvertLog -Encoding UTF8
$testConvert.Output | ForEach-Object { Write-Host "      $_" }
if (($testConvert.ExitCode -ne 0) -or (-not ($testConvert.Output -match 'conversion completed with 0 errors and 0 warnings'))) {
    Stop-Build 'converter test' "converter test that bai (xem $testConvertLog)"
}
$variantsDir = Join-Path $buildDir 'variants'
if (Test-Path $variantsDir) {
    Remove-Item -Recurse -Force $variantsDir
}
New-Item -ItemType Directory -Force -Path $variantsDir | Out-Null
$recordHeaderAnchor = '        target[(short) 0] = PROFILE_RECORD_VERSION;'
$variants = @(
    [pscustomobject]@{
        Name = 'ciphertext'
        Target = 'vn/labaccess/card/crypto/ProfileCrypto.java'
        Anchor = $recordHeaderAnchor
        Lines = @('        target[RECORD_HEADER_LENGTH] = (byte) (target[RECORD_HEADER_LENGTH] ^ 0x01);')
    },
    [pscustomobject]@{
        Name = 'tag'
        Target = 'vn/labaccess/card/crypto/ProfileCrypto.java'
        Anchor = $recordHeaderAnchor
        Lines = @('        target[(short) (RECORD_HEADER_LENGTH + ciphertextLength)] = (byte) (target[(short) (RECORD_HEADER_LENGTH + ciphertextLength)] ^ 0x01);')
    },
    [pscustomobject]@{
        Name = 'nonce'
        Target = 'vn/labaccess/card/crypto/ProfileCrypto.java'
        Anchor = $recordHeaderAnchor
        Lines = @('        target[RECORD_HEADER_OFFSET_NONCE] = (byte) (target[RECORD_HEADER_OFFSET_NONCE] ^ 0x01);')
    },
    [pscustomobject]@{
        Name = 'counter-near-max'
        Target = 'vn/labaccess/card/transaction/QuotaLedger.java'
        Anchor = '        counter3 = (byte) 0;'
        Occurrence = 1
        After = $true
        Lines = @(
            '        counter0 = COUNTER_MAX_BYTE;',
            '        counter1 = COUNTER_MAX_BYTE;',
            '        counter2 = COUNTER_MAX_BYTE;',
            '        counter3 = (byte) 0xFE;'
        )
    },
    [pscustomobject]@{
        Name = 'quota-fault-before-commit'
        Target = 'vn/labaccess/card/LabAccessApplet.java'
        Anchor = '            ledger.setBalance(balanceAfter);'
        Lines = @(
            '            if (delta == (short) 7777) {',
            '                ISOException.throwIt(Sw.INTERNAL_ERROR);',
            '            }'
        )
    },
    [pscustomobject]@{
        Name = 'quota-fault-after-commit'
        Target = 'vn/labaccess/card/LabAccessApplet.java'
        Anchor = '            started = false;'
        After = $true
        Lines = @(
            '            if (delta == (short) 8888) {',
            '                ISOException.throwIt(Sw.INTERNAL_ERROR);',
            '            }'
        )
    },
    [pscustomobject]@{
        Name = 'visit-counter-near-max'
        Target = 'vn/labaccess/card/transaction/QuotaLedger.java'
        Anchor = '        counter3 = (byte) 0;'
        Occurrence = 1
        After = $true
        Lines = @(
            '        counter0 = COUNTER_MAX_BYTE;',
            '        counter1 = COUNTER_MAX_BYTE;',
            '        counter2 = COUNTER_MAX_BYTE;',
            '        counter3 = (byte) 0xFD;'
        )
    },
    [pscustomobject]@{
        Name = 'visit-fault-before-commit'
        Target = 'vn/labaccess/card/LabAccessApplet.java'
        Anchor = '                visits.open(visitId, visitIdOffset);'
        After = $true
        Lines = @(
            '            if (visitId[(short) 0] == (byte) 0xAA) {',
            '                ISOException.throwIt(Sw.INTERNAL_ERROR);',
            '            }'
        )
    },
    [pscustomobject]@{
        Name = 'visit-fault-after-close-before-commit'
        Target = 'vn/labaccess/card/LabAccessApplet.java'
        Anchor = '                visits.close();'
        After = $true
        Lines = @(
            '                ISOException.throwIt(Sw.INTERNAL_ERROR);'
        )
    },
    [pscustomobject]@{
        Name = 'visit-fault-after-commit'
        Target = 'vn/labaccess/card/LabAccessApplet.java'
        Anchor = '            writeStateResponse(ledger, buffer);'
        Occurrence = 2
        Lines = @(
            '            if (visitId[(short) 0] == (byte) 0xAA) {',
            '                ISOException.throwIt(Sw.INTERNAL_ERROR);',
            '            }'
        )
    },
    [pscustomobject]@{
        Name = 'receipt-fault-before-commit'
        Target = 'vn/labaccess/card/LabAccessApplet.java'
        Anchor = '            receipt.store();'
        After = $true
        Lines = @(
            '            if (delta == (short) 7777) {',
            '                ISOException.throwIt(Sw.INTERNAL_ERROR);',
            '            }'
        )
    },
    [pscustomobject]@{
        Name = 'photo-finish-after-length-fault'
        Target = 'vn/labaccess/card/photo/PhotoStore.java'
        Anchor = '        photoLength = total;'
        After = $true
        Lines = @(
            '        if (buffer[(short) 0] == (byte) 0xA5) {',
            '            javacard.framework.ISOException.throwIt(Sw.INTERNAL_ERROR);',
            '        }'
        )
    },
    [pscustomobject]@{
        Name = 'receipt-signature-too-long'
        Target = 'vn/labaccess/card/transaction/Receipt.java'
        Anchor = '        if (stagedSignatureLength <= (short) 0'
        Lines = @(
            '        stagedSignatureLength = (short) 73;'
        )
    }
)
foreach ($variant in $variants) {
    $name = $variant.Name
    $targetRelative = $variant.Target -replace '/', '\'
    $nameClasses = Join-Path $variantsDir "$name-classes"
    $generatedSource = Join-Path $variantsDir "$name-src/$targetRelative"
    New-Item -ItemType Directory -Force -Path (Split-Path $generatedSource -Parent), $nameClasses | Out-Null

    $targetSources = @($sources | Where-Object { $_ -like "*$targetRelative" })
    if ($targetSources.Count -ne 1) {
        Stop-Build 'variant' "Khong xac dinh duy nhat duoc $targetRelative trong $srcDir"
    }
    $original = [System.IO.File]::ReadAllLines($targetSources[0])
    $anchorIndexes = @(for ($index = 0; $index -lt $original.Length; $index++) {
            if ($original[$index] -ceq $variant.Anchor) { $index }
        })
    $wanted = if ($variant.PSObject.Properties['Occurrence']) { $variant.Occurrence } else { 1 }
    if ($anchorIndexes.Count -lt $wanted) {
        Stop-Build 'variant' "Diem chen cua $name xuat hien $($anchorIndexes.Count) lan trong $targetRelative, can lan thu $wanted"
    }
    $anchorIndex = $anchorIndexes[$wanted - 1]
    $patched = New-Object System.Collections.Generic.List[string]
    for ($index = 0; $index -lt $original.Length; $index++) {
        if ($index -eq $anchorIndex) {
            if (-not $variant.After) {
                foreach ($line in $variant.Lines) {
                    $patched.Add($line)
                }
            }
            $patched.Add($original[$index])
            if ($variant.After) {
                foreach ($line in $variant.Lines) {
                    $patched.Add($line)
                }
            }
            continue
        }
        $patched.Add($original[$index])
    }
    [System.IO.File]::WriteAllLines($generatedSource, [string[]]$patched,
        (New-Object System.Text.UTF8Encoding($false)))

    $variantSources = @($sources | Where-Object { $_ -notlike "*$targetRelative" })
    $variantSources += $generatedSource
    $variantLog = Join-Path $logDir "javac-variant-$name.log"
    $r = Invoke-Native { & $javac -g -d $nameClasses -source 10 -target 10 -Xlint:-options -cp $apiJar @variantSources }
    $r.Output | Set-Content -Path $variantLog -Encoding UTF8
    if ($r.ExitCode -ne 0) {
        Stop-Build 'variant compile' "javac variant $name tra exit code $($r.ExitCode) (xem $variantLog)"
    }

    $confLines = @(
        '-i',
        "-classdir $name-classes",
        '-applet 0xF0:0x4C:0x41:0x42:0x02:0x01:0x01 vn.labaccess.card.LabAccessApplet',
        '-out CAP JCA EXP',
        "-d $name-deliverables",
        '-debug',
        '-target 3.2.0',
        'vn.labaccess.card',
        '0xF0:0x4C:0x41:0x42:0x02 1.0'
    )
    $nameConf = Join-Path $variantsDir "$name.conf"
    [System.IO.File]::WriteAllLines($nameConf, [string[]]$confLines,
        (New-Object System.Text.UTF8Encoding($false)))
    Push-Location $variantsDir
    try {
        $convert = Invoke-Native { & $converter -config "$name.conf" }
    } finally {
        Pop-Location
    }
    $variantConvertLog = Join-Path $logDir "converter-variant-$name.log"
    $convert.Output | Set-Content -Path $variantConvertLog -Encoding UTF8
    if (($convert.ExitCode -ne 0) -or (-not ($convert.Output -match 'conversion completed with 0 errors and 0 warnings'))) {
        Stop-Build 'variant converter' "converter variant $name that bai (xem $variantConvertLog)"
    }
}
$variantCaps = @(Get-ChildItem -Path $variantsDir -Recurse -Filter *.cap -File)
if ($variantCaps.Count -ne $variants.Count) {
    Stop-Build 'variant converter' "Can $($variants.Count) CAP variant, thuc te $($variantCaps.Count)"
}

$capFiles = @(Get-ChildItem -Path $deliverablesDir -Recurse -Filter *.cap -File)
$testCapDir = Join-Path $buildDir 'test-deliverables'
$testCapFiles = @(Get-ChildItem -Path $testCapDir -Recurse -Filter *.cap -File)
if ($capFiles.Count -eq 0 -or $testCapFiles.Count -eq 0) {
    Stop-Build 'converter' 'Khong sinh du ca CAP production va CAP test'
}
$capFile = $capFiles[0].FullName
$testCapFile = $testCapFiles[0].FullName
Write-Host "      OK: $capFile ($($capFiles[0].Length) byte)"
Write-Host "      OK test: $testCapFile ($($testCapFiles[0].Length) byte)"
Write-Host ""

Write-Host "[3/3] verifycap"
$verifyLog = Join-Path $logDir 'verifycap.log'
$r = Invoke-Native { & $verifier $capFile }
$r.Output | Set-Content -Path $verifyLog -Encoding UTF8
$r.Output | ForEach-Object { Write-Host "      $_" }

if ($r.ExitCode -ne 0) {
    Stop-Build 'verifycap' "verifycap tra exit code $($r.ExitCode) (xem $verifyLog)"
}
if (-not ($r.Output -match 'Verification completed with 0 warnings and 0 errors')) {
    Stop-Build 'verifycap' "verifycap khong bao '0 warnings and 0 errors' (xem $verifyLog)"
}
$testVerifyLog = Join-Path $logDir 'verifycap-test.log'
$testVerify = Invoke-Native { & $verifier $testCapFile }
$testVerify.Output | Set-Content -Path $testVerifyLog -Encoding UTF8
$testVerify.Output | ForEach-Object { Write-Host "      $_" }
if (($testVerify.ExitCode -ne 0) -or (-not ($testVerify.Output -match 'Verification completed with 0 warnings and 0 errors'))) {
    Stop-Build 'verifycap test' "verifycap test that bai (xem $testVerifyLog)"
}
foreach ($variantCap in $variantCaps) {
    $variantName = $variantCap.FullName.Substring($variantsDir.Length).TrimStart('\').Split('\')[0] -replace '-deliverables$', ''
    $variantVerifyLog = Join-Path $logDir "verifycap-variant-$variantName.log"
    $variantVerify = Invoke-Native { & $verifier $variantCap.FullName }
    $variantVerify.Output | Set-Content -Path $variantVerifyLog -Encoding UTF8
    if (($variantVerify.ExitCode -ne 0) -or (-not ($variantVerify.Output -match 'Verification completed with 0 warnings and 0 errors'))) {
        Stop-Build 'verifycap variant' "verifycap variant that bai cho $($variantCap.FullName) (xem $variantVerifyLog)"
    }
}
Write-Host "      OK production + test + variants"
Write-Host ""

$sha = (Get-FileHash $capFile -Algorithm SHA256).Hash
$testSha = (Get-FileHash $testCapFile -Algorithm SHA256).Hash
Write-Host "=== BUILD THANH CONG ===" -ForegroundColor Green
Write-Host "CAP   : $capFile"
Write-Host "Size  : $($capFiles[0].Length) byte"
Write-Host "SHA256: $sha"
Write-Host "TEST CAP   : $testCapFile"
Write-Host "TEST SIZE  : $($testCapFiles[0].Length) byte"
Write-Host "TEST SHA256: $testSha"
foreach ($variantCap in $variantCaps) {
    $variantName = $variantCap.FullName.Substring($variantsDir.Length).TrimStart('\').Split('\')[0] -replace '-deliverables$', ''
    Write-Host "VARIANT $($variantName.ToUpper()) CAP : $($variantCap.FullName) ($($variantCap.Length) byte)"
}
Write-Host "Log   : $logDir"
exit 0
