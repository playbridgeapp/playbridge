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
Assert-Equal $x64.Emulated $false 'x64 should not be marked as emulated'

$arm64 = Get-PlayBridgeWindowsTarget -Architecture 'ARM64'
Assert-Equal $arm64.Arch 'x86_64' 'ARM64 should use the published x64 target'
Assert-Equal $arm64.Emulated $true 'ARM64 should report emulation'
Assert-Throws { Get-PlayBridgeWindowsTarget -Architecture 'x86' } '32-bit Windows must be rejected'

$manifest = [PSCustomObject]@{
    schemaVersion = 1
    product = 'cli'
    channel = 'stable'
    version = '1.2.3'
    asset = [PSCustomObject]@{
        name = 'playbridge-cli-windows-x86_64.tar.gz'
        url = 'https://example.test/playbridge.tar.gz'
        sha256 = 'a' * 64
        size = 1024
    }
}
Assert-PlayBridgeManifest -Manifest $manifest
$manifest.asset.url = 'http://example.test/playbridge.tar.gz'
Assert-Throws { Assert-PlayBridgeManifest -Manifest $manifest } 'HTTP assets must be rejected'
$manifest.asset.url = 'https://example.test/playbridge.tar.gz'
$manifest.asset.sha256 = 'invalid'
Assert-Throws { Assert-PlayBridgeManifest -Manifest $manifest } 'Malformed checksums must be rejected'
$manifest.asset.sha256 = 'a' * 64
$manifest.asset.size = 0
Assert-Throws { Assert-PlayBridgeManifest -Manifest $manifest } 'Empty archives must be rejected'

Assert-SafeArchiveEntries -Entries @('./playbridge.exe', './LICENSE')
Assert-Throws { Assert-SafeArchiveEntries -Entries @('../playbridge.exe') } 'Traversal must be rejected'
Assert-Throws { Assert-SafeArchiveEntries -Entries @('C:\playbridge.exe') } 'Drive paths must be rejected'

$path = Add-PathEntryValue -CurrentValue 'C:\Tools' -InstallDirectory 'C:\PlayBridge\bin'
Assert-Equal $path 'C:\Tools;C:\PlayBridge\bin' 'Install directory should be appended'
$deduplicated = Add-PathEntryValue -CurrentValue $path -InstallDirectory 'c:\playbridge\bin\'
Assert-Equal $deduplicated $path 'PATH matching should be case-insensitive and slash-tolerant'

Write-Host 'PowerShell installer tests passed.'
