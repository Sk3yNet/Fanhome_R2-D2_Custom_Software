# Read an APK's signing certificate and versionCode on the PC, without the Android SDK.
# Dot-source it: . tools\apk-info.ps1 ; Get-ApkCert apps\out\R2Main.apk ; Get-ApkVersionCode apps\out\R2Main.apk
# Works in Windows PowerShell 5.1 and PowerShell 7 (also on Linux, which CI uses to check it against apksigner).

Add-Type -AssemblyName System.IO.Compression.FileSystem
try { Add-Type -AssemblyName System.Security } catch { }   # SignedCms in Windows PowerShell 5.1; built into PowerShell 7

function Read-ApkEntry([string]$Apk, [scriptblock]$Match) {
    $zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Apk).Path)
    try {
        $e = $zip.Entries | Where-Object $Match | Select-Object -First 1
        if (-not $e) { return $null }
        $ms = New-Object IO.MemoryStream
        $s = $e.Open()
        try { $s.CopyTo($ms) } finally { $s.Close() }
        return , $ms.ToArray()
    } finally { $zip.Dispose() }
}

# SHA-256 of the signing certificate, lowercase hex: the same value as apksigner's "Signer #1 certificate
# SHA-256 digest" and the one in the release notes. Read from the v1 (JAR) signature, which our builds always
# have because they support Android 4.4.
function Get-ApkCert([string]$Apk) {
    $p7 = Read-ApkEntry $Apk { $_.FullName -match '^META-INF/[^/]+\.(RSA|DSA|EC)$' }
    if (-not $p7) { throw "$Apk has no v1 (JAR) signature" }
    $cms = New-Object System.Security.Cryptography.Pkcs.SignedCms
    $cms.Decode($p7)
    $cert = if ($cms.SignerInfos.Count -gt 0 -and $cms.SignerInfos[0].Certificate) { $cms.SignerInfos[0].Certificate } else { $cms.Certificates[0] }
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $hash = $sha.ComputeHash($cert.RawData) } finally { $sha.Dispose() }
    return (($hash | ForEach-Object { $_.ToString('x2') }) -join '')
}

# android:versionCode from the binary AndroidManifest.xml.
function Get-ApkVersionCode([string]$Apk) {
    $b = Read-ApkEntry $Apk { $_.FullName -eq 'AndroidManifest.xml' }
    if (-not $b) { throw "$Apk has no AndroidManifest.xml" }
    if ([BitConverter]::ToUInt16($b, 0) -ne 0x0003) { throw "${Apk}: AndroidManifest.xml is not binary XML" }
    $resIds = @()
    $off = [BitConverter]::ToUInt16($b, 2)            # first chunk after the file header
    while ($off + 8 -le $b.Length) {
        $type = [BitConverter]::ToUInt16($b, $off)
        $headerSize = [BitConverter]::ToUInt16($b, $off + 2)
        $size = [BitConverter]::ToUInt32($b, $off + 4)
        if ($size -lt 8) { break }
        if ($type -eq 0x0180) {                        # resource map: attribute name index -> resource id
            $n = ($size - $headerSize) / 4
            $resIds = for ($i = 0; $i -lt $n; $i++) { [BitConverter]::ToUInt32($b, $off + $headerSize + 4 * $i) }
        }
        elseif ($type -eq 0x0102) {                    # first start tag: <manifest>
            $ext = $off + $headerSize
            $attrStart = [BitConverter]::ToUInt16($b, $ext + 8)
            $attrSize = [BitConverter]::ToUInt16($b, $ext + 10)
            $attrCount = [BitConverter]::ToUInt16($b, $ext + 12)
            for ($i = 0; $i -lt $attrCount; $i++) {
                $a = $ext + $attrStart + $i * $attrSize
                $name = [BitConverter]::ToUInt32($b, $a + 4)
                if ($name -lt $resIds.Count -and $resIds[$name] -eq 0x0101021b) {   # android:versionCode
                    return [BitConverter]::ToInt32($b, $a + 16)
                }
            }
            throw "${Apk}: no versionCode in the manifest"
        }
        $off += $size
    }
    throw "${Apk}: manifest tag not found"
}
