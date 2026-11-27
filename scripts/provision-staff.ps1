$ErrorActionPreference = 'Stop'
$backend = Join-Path $PSScriptRoot '..\backend'
$staffCode = Read-Host 'Ma nhan vien (VD STF001)'
$username = Read-Host 'Ten dang nhap (VD staff01)'
$displayName = Read-Host 'Ten hien thi'
$secret = Read-Host 'Mat khau (toi thieu 12 ky tu)' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
$previousEncoding = $OutputEncoding
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    Push-Location $backend
    try {
        @{ staffCode = $staffCode; username = $username; displayName = $displayName; password = $password } |
            ConvertTo-Json -Compress | & node.exe scripts/provision-staff.mjs
        if ($LASTEXITCODE -ne 0) { throw 'Staff provisioning failed.' }
    } finally { Pop-Location }
} finally {
    $password = $null
    $OutputEncoding = $previousEncoding
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $secret.Dispose()
}
