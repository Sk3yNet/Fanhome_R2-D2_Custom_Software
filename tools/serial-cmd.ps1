# Send shell commands over the serial console and log the replies.
# Usage: powershell -File tools\serial-cmd.ps1 -Port COM4 -Out serial.log -CommandFile tools\serial-commands\usb-device-mode.txt
# (one shell command per line; blank lines and lines starting with # are skipped)
param(
    [string]$Port = "COM4",
    [int]$Baud = 115200,
    [string]$CommandFile,
    [int]$WaitMs = 3000,
    [string]$Out = "serial-cmd.log"
)

$Commands = Get-Content $CommandFile | Where-Object { $_.Trim() -and -not $_.StartsWith("#") }

$sp = New-Object System.IO.Ports.SerialPort $Port, $Baud, ([System.IO.Ports.Parity]::None), 8, ([System.IO.Ports.StopBits]::One)
$sp.Handshake = [System.IO.Ports.Handshake]::None
$sp.NewLine = "`n"
$sp.Open()

$writer = New-Object System.IO.StreamWriter($Out, $true, [System.Text.Encoding]::UTF8)
$writer.AutoFlush = $true

function Drain([int]$ms) {
    $end = (Get-Date).AddMilliseconds($ms)
    $buf = ""
    while ((Get-Date) -lt $end) {
        $d = $sp.ReadExisting()
        if ($d.Length -gt 0) { $buf += $d; $end = (Get-Date).AddMilliseconds([Math]::Min($ms, 1500)) }
        else { Start-Sleep -Milliseconds 50 }
    }
    return $buf
}

try {
    # Quiet kernel messages on the console so command output stays readable.
    $sp.Write("`n"); Drain 500 | Out-Null
    $sp.Write("echo 1 > /proc/sys/kernel/printk`n"); Drain 500 | Out-Null
    foreach ($c in $Commands) {
        $writer.WriteLine("===== $c =====")
        $sp.Write("$c`n")
        $writer.Write((Drain $WaitMs))
        $writer.WriteLine()
    }
} finally {
    $writer.Close()
    $sp.Close()
}
"Done: $Out"
