$ErrorActionPreference = 'Stop'
$env:PLAYBRIDGE_INSTALLER_TEST_MODE = '1'
. (Join-Path $PSScriptRoot 'install.ps1')

function Assert-Equal {
    param($Actual, $Expected, [string]$Message)
    if ($Actual -ne $Expected) {
        throw "$Message (expected '$Expected', got '$Actual')"
    }
}

function Assert-Throws {
    param([scriptblock]$Action, [string]$Message)
    try {
        & $Action
    } catch {
        return
    }
    throw "$Message (expected an exception)"
}

$x64 = Get-PlayBridgeWindowsTarget -Architecture 'AMD64'
Assert-Equal $x64.Arch 'x86_64' 'x64 should map to the published target'
$arm64 = Get-PlayBridgeWindowsTarget -Architecture 'ARM64'
Assert-Equal $arm64.Emulated $true 'ARM64 should install the x64 build under emulation'
Assert-Throws { Get-PlayBridgeWindowsTarget -Architecture 'x86' } '32-bit Windows must be rejected'

$manifest = [PSCustomObject]@{
    schemaVersion = 1
    product = 'desktop'
    channel = 'stable'
    version = '0.16.0'
    asset = [PSCustomObject]@{
        name = 'playbridge-desktop-windows-0.16.0.zip'
        url = 'https://example.test/playbridge-desktop-windows-0.16.0.zip'
        sha256 = 'a' * 64
        size = 1024
    }
}
Assert-PlayBridgeManifest -Manifest $manifest
$manifest.product = 'cli'
Assert-Throws { Assert-PlayBridgeManifest -Manifest $manifest } 'Manifests for other products must be rejected'
$manifest.product = 'desktop'
$manifest.asset.name = 'playbridge-desktop-macos-0.16.0.zip'
Assert-Throws { Assert-PlayBridgeManifest -Manifest $manifest } 'Assets for other platforms must be rejected'
$manifest.asset.name = 'playbridge-desktop-windows-0.16.0.zip'
$manifest.asset.url = 'http://example.test/playbridge.zip'
Assert-Throws { Assert-PlayBridgeManifest -Manifest $manifest } 'HTTP assets must be rejected'
$manifest.asset.url = 'https://example.test/playbridge.zip'
$manifest.asset.sha256 = $null
Assert-Throws { Assert-PlayBridgeManifest -Manifest $manifest } 'Missing checksums must be rejected'
$manifest.asset.sha256 = 'a' * 64
$manifest.asset.size = 0
Assert-Throws { Assert-PlayBridgeManifest -Manifest $manifest } 'Empty archives must be rejected'
$manifest.asset.size = 1024
$manifest.version = '0.16'
Assert-Throws { Assert-PlayBridgeManifest -Manifest $manifest } 'Malformed versions must be rejected'

Assert-Equal (Get-AssetDigestSha256 -Digest "sha256:$('B' * 64)") ('b' * 64) 'GitHub digests should be parsed and lowercased'
Assert-Equal (Get-AssetDigestSha256 -Digest "md5:$('b' * 32)") $null 'Non-SHA-256 digests must be ignored'
Assert-Equal (Get-AssetDigestSha256 -Digest '') $null 'Missing digests must be ignored'

Assert-SafeArchiveEntries -Entries @('playbridge_desktop.exe', 'data/flutter_assets/AssetManifest.json')
Assert-Throws { Assert-SafeArchiveEntries -Entries @('../playbridge_desktop.exe') } 'Traversal must be rejected'
Assert-Throws { Assert-SafeArchiveEntries -Entries @('data\..\..\evil.dll') } 'Backslash traversal must be rejected'
Assert-Throws { Assert-SafeArchiveEntries -Entries @('C:\Windows\evil.dll') } 'Drive paths must be rejected'

# A real archive round trip: safe entries extract, traversal entries never do.
$scratch = Join-Path ([IO.Path]::GetTempPath()) "pb-desktop-installer-test-$([Guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $scratch | Out-Null
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $source = Join-Path $scratch 'source'
    New-Item -ItemType Directory -Path (Join-Path $source 'data') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $source 'playbridge_desktop.exe') -Value 'exe'
    Set-Content -LiteralPath (Join-Path $source 'data\app.so') -Value 'aot'
    $goodZip = Join-Path $scratch 'good.zip'
    [IO.Compression.ZipFile]::CreateFromDirectory($source, $goodZip)
    $out = Join-Path $scratch 'out'
    Expand-PlayBridgeArchive -ArchivePath $goodZip -Destination $out
    Assert-Equal (Test-Path -LiteralPath (Join-Path $out 'playbridge_desktop.exe')) $true 'The executable should extract'
    Assert-Equal (Test-Path -LiteralPath (Join-Path $out 'data\app.so')) $true 'Nested files should extract'

    $evilZip = Join-Path $scratch 'evil.zip'
    $zip = [IO.Compression.ZipFile]::Open($evilZip, [IO.Compression.ZipArchiveMode]::Create)
    try {
        $entry = $zip.CreateEntry('../escaped.txt')
        $writer = New-Object IO.StreamWriter($entry.Open())
        $writer.Write('x')
        $writer.Dispose()
    } finally {
        $zip.Dispose()
    }
    $evilOut = Join-Path $scratch 'evil-out'
    Assert-Throws { Expand-PlayBridgeArchive -ArchivePath $evilZip -Destination $evilOut } 'Traversal archives must be rejected'
    Assert-Equal (Test-Path -LiteralPath (Join-Path $scratch 'escaped.txt')) $false 'Nothing may be written outside the destination'
} finally {
    Remove-Item -LiteralPath $scratch -Recurse -Force -ErrorAction SilentlyContinue
}

$uninstall = Get-UninstallScript -InstallDirectory "C:\Users\O'Neil\AppData\Local\Programs\PlayBridge" -ShortcutPath 'C:\Start\PlayBridge.lnk'
[scriptblock]::Create($uninstall) | Out-Null
if ($uninstall -notmatch [Regex]::Escape("'C:\Users\O''Neil\AppData\Local\Programs\PlayBridge'")) {
    throw 'The uninstaller should embed paths as escaped single-quoted literals'
}
foreach ($expected in @('NativeMessagingHosts\com.playbridge.host', 'SystemFileAssociations', 'PlayBridgeDesktop')) {
    if ($uninstall -notmatch [Regex]::Escape($expected)) {
        throw "The uninstaller should remove $expected"
    }
}
Assert-Equal (Get-UninstallCommand -UninstallScript 'C:\Users\a b\uninstall.ps1') `
    'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "C:\Users\a b\uninstall.ps1"' 'The uninstall command should quote the script path'

Write-Host 'Desktop PowerShell installer tests passed.'
