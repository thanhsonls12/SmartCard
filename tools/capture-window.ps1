# Chup anh mot cua so Windows theo PID (dung cho minh chung Gate 0).
# Cach dung: .\capture-window.ps1 -ProcessId 21824 -OutFile 'D:\SmartCard\artifacts\desktop-ui\shot.png'
param(
    [Parameter(Mandatory = $true)][int]$ProcessId,
    [Parameter(Mandatory = $true)][string]$OutFile
)

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms

if (-not ('Win32Rect' -as [type])) {
    Add-Type @"
using System;
using System.Runtime.InteropServices;

public class Win32Rect {
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }

    [DllImport("user32.dll")]
    public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
}
"@
}

$proc = Get-Process -Id $ProcessId -ErrorAction Stop
$handle = $proc.MainWindowHandle
if ($handle -eq [IntPtr]::Zero) {
    throw "Process $ProcessId khong co cua so (MainWindowHandle = 0)"
}

# Dua cua so len truoc de khong bi che
[void][Win32Rect]::ShowWindow($handle, 9)   # SW_RESTORE
[void][Win32Rect]::SetForegroundWindow($handle)
Start-Sleep -Milliseconds 700

$rect = New-Object Win32Rect+RECT
if (-not [Win32Rect]::GetWindowRect($handle, [ref]$rect)) {
    throw "GetWindowRect that bai cho PID $ProcessId"
}

$width  = $rect.Right - $rect.Left
$height = $rect.Bottom - $rect.Top
if ($width -le 0 -or $height -le 0) {
    throw "Kich thuoc cua so khong hop le: ${width}x${height}"
}

$bitmap = New-Object System.Drawing.Bitmap($width, $height)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
try {
    $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, $bitmap.Size)
    $dir = Split-Path -Parent $OutFile
    if ($dir -and -not (Test-Path $dir)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }
    $bitmap.Save($OutFile, [System.Drawing.Imaging.ImageFormat]::Png)
}
finally {
    $graphics.Dispose()
    $bitmap.Dispose()
}

Write-Output "PID       : $ProcessId"
Write-Output "Title     : $($proc.MainWindowTitle)"
Write-Output "Rect      : $($rect.Left),$($rect.Top) ${width}x${height}"
Write-Output "Saved to  : $OutFile"
Write-Output "Size      : $((Get-Item $OutFile).Length) bytes"
