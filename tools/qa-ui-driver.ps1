param(
    [Parameter(Mandatory = $true)][int]$ProcessId,
    [Parameter(Mandatory = $true)][ValidateSet('rect', 'click', 'clickabs', 'hover', 'text', 'key', 'clear', 'wheel')][string]$Action,
    [int]$Notches = 3,
    [int]$X = 0,
    [int]$Y = 0,
    [string]$Text = '',
    [ValidateSet('ENTER', 'TAB', 'ESC', 'SPACE', 'BACKSPACE')][string]$Key = 'ENTER',
    [int]$SettleMs = 350
)

$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Windows.Forms

if (-not ('QaUiNative' -as [type])) {
    Add-Type @"
using System;
using System.Runtime.InteropServices;

public class QaUiNative {
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }

    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, IntPtr dwExtraInfo);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern IntPtr WindowFromPoint(POINT point);
    [DllImport("user32.dll")] public static extern IntPtr GetAncestor(IntPtr hWnd, uint flags);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);

    [StructLayout(LayoutKind.Sequential)]
    public struct POINT { public int X; public int Y; }

    public static uint RootOwnerPid(IntPtr hWnd) {
        uint pid;
        IntPtr root = GetAncestor(hWnd, 2);
        GetWindowThreadProcessId(root, out pid);
        return pid;
    }
}
"@
}

$proc = Get-Process -Id $ProcessId -ErrorAction Stop
$handle = $proc.MainWindowHandle
if ($handle -eq [IntPtr]::Zero) {
    throw "Process $ProcessId has no main window yet"
}

$rect = New-Object QaUiNative+RECT
if (-not [QaUiNative]::GetWindowRect($handle, [ref]$rect)) {
    throw "GetWindowRect failed for PID $ProcessId"
}
$width = $rect.Right - $rect.Left
$height = $rect.Bottom - $rect.Top

function Focus-Window {
    [void][QaUiNative]::ShowWindow($handle, 9)
    [void][QaUiNative]::SetForegroundWindow($handle)
    Start-Sleep -Milliseconds 250
}

switch ($Action) {
    'rect' {
        Write-Output "pid=$ProcessId left=$($rect.Left) top=$($rect.Top) width=$width height=$height"
        break
    }
    'click' {
        if ($X -lt 0 -or $Y -lt 0 -or $X -ge $width -or $Y -ge $height) {
            throw "Click offset ($X,$Y) outside window ${width}x${height}"
        }
        $screenX = $rect.Left + $X
        $screenY = $rect.Top + $Y
        Focus-Window
        [void][QaUiNative]::SetCursorPos($screenX, $screenY)
        Start-Sleep -Milliseconds 120
        $foreground = [QaUiNative]::GetForegroundWindow()
        if ($foreground -ne $handle) {
            throw "Window is not foreground after focus; refusing to click"
        }
        [QaUiNative]::mouse_event(0x0002, 0, 0, 0, [IntPtr]::Zero)
        Start-Sleep -Milliseconds 60
        [QaUiNative]::mouse_event(0x0004, 0, 0, 0, [IntPtr]::Zero)
        Start-Sleep -Milliseconds $SettleMs
        Write-Output "clicked window offset ($X,$Y) => screen ($screenX,$screenY)"
        break
    }
    'clickabs' {
        $screenX = $X
        $screenY = $Y
        $point = New-Object QaUiNative+POINT
        $point.X = $screenX
        $point.Y = $screenY
        $hit = [QaUiNative]::WindowFromPoint($point)
        if ($hit -eq [IntPtr]::Zero) {
            throw "No window at screen ($screenX,$screenY)"
        }
        $hitPid = [QaUiNative]::RootOwnerPid($hit)
        if ($hitPid -ne $process.Id -and $hitPid -ne $ProcessId) {
            throw "Window at screen ($screenX,$screenY) belongs to PID $hitPid, not $ProcessId"
        }
        [void][QaUiNative]::SetCursorPos($screenX, $screenY)
        Start-Sleep -Milliseconds 120
        $foregroundPid = [QaUiNative]::RootOwnerPid([QaUiNative]::GetForegroundWindow())
        if ($foregroundPid -ne $ProcessId) {
            throw "Foreground window belongs to PID $foregroundPid, not $ProcessId"
        }
        [QaUiNative]::mouse_event(0x0002, 0, 0, 0, [IntPtr]::Zero)
        Start-Sleep -Milliseconds 60
        [QaUiNative]::mouse_event(0x0004, 0, 0, 0, [IntPtr]::Zero)
        Start-Sleep -Milliseconds $SettleMs
        Write-Output "clicked screen ($screenX,$screenY) on window of PID $hitPid"
        break
    }
    'hover' {
        if ($X -lt 0 -or $Y -lt 0 -or $X -ge $width -or $Y -ge $height) {
            throw "Hover offset ($X,$Y) outside window ${width}x${height}"
        }
        $screenX = $rect.Left + $X
        $screenY = $rect.Top + $Y
        Focus-Window
        [void][QaUiNative]::SetCursorPos($screenX, $screenY)
        Start-Sleep -Milliseconds 1400
        Write-Output "hovering window offset ($X,$Y)"
        break
    }
    'wheel' {
        $screenX = $rect.Left + $X
        $screenY = $rect.Top + $Y
        Focus-Window
        [void][QaUiNative]::SetCursorPos($screenX, $screenY)
        Start-Sleep -Milliseconds 150
        $delta = $Notches * 120
        $wheelData = [uint32]([int64]$delta -band 4294967295L)
        [QaUiNative]::mouse_event(0x0800, 0, 0, $wheelData, [IntPtr]::Zero)
        Start-Sleep -Milliseconds $SettleMs
        Write-Output "wheel $Notches notches over ($X,$Y)"
        break
    }
    'text' {
        Focus-Window
        [System.Windows.Forms.SendKeys]::SendWait($Text)
        Start-Sleep -Milliseconds $SettleMs
        Write-Output "typed $($Text.Length) characters"
        break
    }
    'clear' {
        Focus-Window
        $backspaces = ''
        for ($i = 0; $i -lt 32; $i++) { $backspaces += '{BACKSPACE}' }
        [System.Windows.Forms.SendKeys]::SendWait($backspaces)
        Start-Sleep -Milliseconds $SettleMs
        Write-Output "cleared focused field"
        break
    }
    'key' {
        Focus-Window
        $map = @{ ENTER = '{ENTER}'; TAB = '{TAB}'; ESC = '{ESC}'; SPACE = ' '; BACKSPACE = '{BACKSPACE}' }
        [System.Windows.Forms.SendKeys]::SendWait($map[$Key])
        Start-Sleep -Milliseconds $SettleMs
        Write-Output "sent key $Key"
        break
    }
}
