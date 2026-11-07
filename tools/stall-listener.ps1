# Listener gia cho bai kiem tra dong cua so khi dang ban:
# accept ket noi nhung KHONG bao gio tra loi, nen connect() cua smartcardio treo.
# Chay tach roi (Start-Process) de khong chet theo phien pwsh.

param([int]$Port = 9031)

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
$listener.Start()
Write-Output "stall-listener: dang nghe $Port"

$clients = [System.Collections.Generic.List[System.Net.Sockets.TcpClient]]::new()
try {
    while ($true) {
        if ($listener.Pending()) {
            $clients.Add($listener.AcceptTcpClient())
            Write-Output "stall-listener: da nhan 1 ket noi, giu mo (khong tra loi)"
        }
        Start-Sleep -Milliseconds 100
    }
} finally {
    foreach ($c in $clients) { $c.Close() }
    $listener.Stop()
}
