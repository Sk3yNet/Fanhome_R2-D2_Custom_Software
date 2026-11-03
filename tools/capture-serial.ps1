# Capture a serial console to a log file.
# Usage: powershell -File capture-serial.ps1 -Port COM4 -Baud 115200 -Seconds 180 -Out boot.log
param(
    [string]$Port = "COM4",
    [int]$Baud = 115200,
    [int]$Seconds = 180,
    [string]$Out = "boot.log"
)

$sp = New-Object System.IO.Ports.SerialPort $Port, $Baud, ([System.IO.Ports.Parity]::None), 8, ([System.IO.Ports.StopBits]::One)
$sp.Handshake = [System.IO.Ports.Handshake]::None
$sp.ReadTimeout = 500
$sp.Open()

$writer = New-Object System.IO.StreamWriter($Out, $false, [System.Text.Encoding]::UTF8)
$writer.AutoFlush = $true
$deadline = (Get-Date).AddSeconds($Seconds)
$total = 0
try {
    while ((Get-Date) -lt $deadline) {
        $data = $sp.ReadExisting()
        if ($data.Length -gt 0) {
            $writer.Write($data)
            $total += $data.Length
        } else {
            Start-Sleep -Milliseconds 50
        }
    }
} finally {
    $writer.Close()
    $sp.Close()
}
"Captured $total characters to $Out"
