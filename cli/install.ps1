$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$script:MaxArchiveBytes = 256MB
$script:ExpectedAsset = 'playbridge-cli-windows-x86_64.tar.gz'

function Get-PlayBridgeWindowsTarget {
    param([string]$Architecture)

    if ([string]::IsNullOrWhiteSpace($Architecture)) {
        $Architecture = if ($env:PROCESSOR_ARCHITEW6432) {
            $env:PROCESSOR_ARCHITEW6432
        } else {
            $env:PROCESSOR_ARCHITECTURE
        }
    }

    switch ($Architecture.ToUpperInvariant()) {
        'AMD64' {
            return [PSCustomObject]@{ Arch = 'x86_64'; Emulated = $false }
        }
        'ARM64' {
            return [PSCustomObject]@{ Arch = 'x86_64'; Emulated = $true }
        }
        default {
            throw "playbridge installer: unsupported Windows architecture: $Architecture"
        }
    }
}

function Assert-PlayBridgeManifest {
    param(
        [Parameter(Mandatory = $true)]$Manifest,
        [string]$ExpectedAsset = $script:ExpectedAsset
    )

    if ($Manifest.schemaVersion -ne 1 -or
        $Manifest.product -ne 'cli' -or
        $Manifest.channel -ne 'stable') {
        throw 'playbridge installer: the update service returned an unsupported manifest'
    }
    if ($Manifest.asset.name -ne $ExpectedAsset) {
        throw 'playbridge installer: the update service returned an asset for another platform'
    }

    [Uri]$assetUri = $null
    if (-not [Uri]::TryCreate([string]$Manifest.asset.url, [UriKind]::Absolute, [ref]$assetUri) -or
        $assetUri.Scheme -ne 'https') {
        throw 'playbridge installer: the update service returned an insecure download URL'
    }

    $checksum = [string]$Manifest.asset.sha256
    if ($checksum -notmatch '^[a-fA-F0-9]{64}$') {
        throw 'playbridge installer: the update service returned an invalid checksum'
    }

    if ($null -ne $Manifest.asset.size) {
        $size = [UInt64]$Manifest.asset.size
        if ($size -eq 0 -or $size -gt $script:MaxArchiveBytes) {
            throw 'playbridge installer: the update service returned an invalid archive size'
        }
    }
}

function Assert-SafeArchiveEntries {
    param([Parameter(Mandatory = $true)][string[]]$Entries)

    foreach ($entry in $Entries) {
        $normalized = $entry.Replace('\', '/').Trim()
        while ($normalized.StartsWith('./')) {
            $normalized = $normalized.Substring(2)
        }
        if ([string]::IsNullOrWhiteSpace($normalized) -or $normalized -eq '.') {
            continue
        }
        if ($normalized.StartsWith('/') -or
            $normalized -match '^[A-Za-z]:' -or
            $normalized.Split('/') -contains '..') {
            throw "playbridge installer: the release archive contains an unsafe path: $entry"
        }
    }
}

function Add-PathEntryValue {
    param(
        [AllowEmptyString()][string]$CurrentValue,
        [Parameter(Mandatory = $true)][string]$InstallDirectory
    )

    $candidate = $InstallDirectory.TrimEnd([char[]]'\/')
    $entries = @($CurrentValue -split ';' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    foreach ($entry in $entries) {
        if ([string]::Equals($entry.TrimEnd([char[]]'\/'), $candidate, [StringComparison]::OrdinalIgnoreCase)) {
            return $CurrentValue
        }
    }
    if ($entries.Count -eq 0) {
        return $candidate
    }
    return "$($entries -join ';');$candidate"
}

function Publish-EnvironmentChange {
    try {
        if (-not ('PlayBridge.EnvironmentBroadcast' -as [type])) {
            Add-Type @'
using System;
using System.Runtime.InteropServices;
namespace PlayBridge {
    public static class EnvironmentBroadcast {
        [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
        private static extern IntPtr SendMessageTimeout(
            IntPtr hWnd, uint message, UIntPtr wParam, string lParam,
            uint flags, uint timeout, out UIntPtr result);

        public static void Notify() {
            UIntPtr result;
            SendMessageTimeout((IntPtr)0xffff, 0x001A, UIntPtr.Zero, "Environment", 0x0002, 5000, out result);
        }
    }
}
'@
        }
        [PlayBridge.EnvironmentBroadcast]::Notify()
    } catch {
        Write-Warning 'PATH was saved, but running applications may need to be restarted before they see it.'
    }
}

function Add-PlayBridgeToUserPath {
    param([Parameter(Mandatory = $true)][string]$InstallDirectory)

    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    $newUserPath = Add-PathEntryValue -CurrentValue ([string]$userPath) -InstallDirectory $InstallDirectory
    if ($newUserPath -ne [string]$userPath) {
        [Environment]::SetEnvironmentVariable('Path', $newUserPath, 'User')
        Publish-EnvironmentChange
        Write-Host "Added $InstallDirectory to your user PATH."
    }
    $env:Path = Add-PathEntryValue -CurrentValue $env:Path -InstallDirectory $InstallDirectory
}

function Get-ReleaseChecksum {
    param(
        [Parameter(Mandatory = $true)][string]$ChecksumFile,
        [Parameter(Mandatory = $true)][string]$AssetName
    )

    $escapedAsset = [Regex]::Escape($AssetName)
    foreach ($line in Get-Content -LiteralPath $ChecksumFile) {
        if ($line -match "^([a-fA-F0-9]{64})\s+\*?$escapedAsset\s*$") {
            return $Matches[1].ToLowerInvariant()
        }
    }
    throw "playbridge installer: release checksum for $AssetName is missing"
}

function Install-PlayBridge {
    $target = Get-PlayBridgeWindowsTarget
    if ($target.Emulated) {
        Write-Host 'Windows ARM64 detected; installing the x64 PlayBridge build using Windows emulation.'
    }

    if (-not (Get-Command 'tar.exe' -ErrorAction SilentlyContinue)) {
        throw 'playbridge installer: tar.exe is required (included with supported Windows 10 and Windows 11 versions)'
    }

    $repositoryOverride = [string]$env:PLAYBRIDGE_REPOSITORY
    $repository = if ($repositoryOverride) { $repositoryOverride } else { 'playbridgeapp/playbridge' }
    $version = [string]$env:PLAYBRIDGE_VERSION
    $updateBaseUrl = if ($env:PLAYBRIDGE_UPDATE_BASE_URL) {
        $env:PLAYBRIDGE_UPDATE_BASE_URL.TrimEnd('/')
    } else {
        'https://playbridge.app'
    }
    $installDirectory = if ($env:PLAYBRIDGE_INSTALL_DIR) {
        $env:PLAYBRIDGE_INSTALL_DIR
    } else {
        Join-Path $env:LOCALAPPDATA 'PlayBridge\bin'
    }

    $assetUrl = $null
    $expectedChecksum = $null
    $expectedSize = $null
    $manifest = $null

    if (-not $version -and -not $repositoryOverride) {
        $manifestUrl = "$updateBaseUrl/api/v1/updates/cli?os=windows&arch=$($target.Arch)"
        $manifest = Invoke-RestMethod -Uri $manifestUrl -Headers @{ 'User-Agent' = 'PlayBridge-Installer' }
        Assert-PlayBridgeManifest -Manifest $manifest
        $version = [string]$manifest.version
        $assetUrl = [string]$manifest.asset.url
        $expectedChecksum = ([string]$manifest.asset.sha256).ToLowerInvariant()
        if ($null -ne $manifest.asset.size) {
            $expectedSize = [UInt64]$manifest.asset.size
        }
    } else {
        if (-not $version) {
            $releases = Invoke-RestMethod -Uri "https://api.github.com/repos/$repository/releases?per_page=100" -Headers @{ 'Accept' = 'application/vnd.github+json'; 'User-Agent' = 'PlayBridge-Installer' }
            $release = $releases | Where-Object { $_.tag_name -like 'cli-v*' } | Select-Object -First 1
            if (-not $release) {
                throw 'playbridge installer: no CLI release was found'
            }
            $version = ([string]$release.tag_name).Substring(5)
        }
        if ($version.StartsWith('cli-v')) {
            $version = $version.Substring(5)
        }
        $releaseBaseUrl = "https://github.com/$repository/releases/download/cli-v$version"
        $assetUrl = "$releaseBaseUrl/$script:ExpectedAsset"
    }

    if ($version.StartsWith('cli-v')) {
        $version = $version.Substring(5)
    }
    if ([string]::IsNullOrWhiteSpace($version)) {
        throw 'playbridge installer: the release version is missing'
    }

    $temporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) "playbridge-install-$([Guid]::NewGuid().ToString('N'))"
    $archivePath = Join-Path $temporaryDirectory $script:ExpectedAsset
    $unpackedDirectory = Join-Path $temporaryDirectory 'unpacked'
    New-Item -ItemType Directory -Path $unpackedDirectory -Force | Out-Null

    try {
        Write-Host "Downloading PlayBridge CLI v$version for windows/$($target.Arch)..."
        Invoke-WebRequest -Uri $assetUrl -OutFile $archivePath -UseBasicParsing

        $archiveSize = (Get-Item -LiteralPath $archivePath).Length
        if ($archiveSize -le 0 -or $archiveSize -gt $script:MaxArchiveBytes) {
            throw 'playbridge installer: the downloaded archive has an invalid size'
        }
        if ($null -ne $expectedSize -and $archiveSize -ne $expectedSize) {
            throw 'playbridge installer: the downloaded archive size does not match the update manifest'
        }

        if (-not $expectedChecksum) {
            $checksumPath = Join-Path $temporaryDirectory 'SHA256SUMS'
            Invoke-WebRequest -Uri "https://github.com/$repository/releases/download/cli-v$version/SHA256SUMS" -OutFile $checksumPath -UseBasicParsing
            $expectedChecksum = Get-ReleaseChecksum -ChecksumFile $checksumPath -AssetName $script:ExpectedAsset
        }
        $actualChecksum = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualChecksum -ne $expectedChecksum) {
            throw 'playbridge installer: checksum verification failed'
        }

        $archiveEntries = @(& tar.exe -tzf $archivePath)
        if ($LASTEXITCODE -ne 0) {
            throw 'playbridge installer: could not inspect the release archive'
        }
        Assert-SafeArchiveEntries -Entries $archiveEntries
        & tar.exe -xzf $archivePath -C $unpackedDirectory
        if ($LASTEXITCODE -ne 0) {
            throw 'playbridge installer: could not extract the release archive'
        }

        $sourceBinary = Join-Path $unpackedDirectory 'playbridge.exe'
        if (-not (Test-Path -LiteralPath $sourceBinary -PathType Leaf)) {
            throw 'playbridge installer: playbridge.exe is missing from the release archive'
        }

        New-Item -ItemType Directory -Path $installDirectory -Force | Out-Null
        $destination = Join-Path $installDirectory 'playbridge.exe'
        $suffix = [Guid]::NewGuid().ToString('N')
        $stagedBinary = Join-Path $installDirectory ".playbridge-install-$suffix.exe"
        $backupBinary = Join-Path $installDirectory ".playbridge-backup-$suffix.exe"
        Copy-Item -LiteralPath $sourceBinary -Destination $stagedBinary

        & $stagedBinary --version | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw 'playbridge installer: the downloaded executable failed its version check'
        }

        $hadExisting = Test-Path -LiteralPath $destination -PathType Leaf
        try {
            if ($hadExisting) {
                Move-Item -LiteralPath $destination -Destination $backupBinary
            }
            Move-Item -LiteralPath $stagedBinary -Destination $destination
            & $destination --version | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw 'the installed executable failed its version check'
            }
            if ($hadExisting) {
                Remove-Item -LiteralPath $backupBinary -Force
            }
        } catch {
            Remove-Item -LiteralPath $destination -Force -ErrorAction SilentlyContinue
            if (Test-Path -LiteralPath $backupBinary -PathType Leaf) {
                Move-Item -LiteralPath $backupBinary -Destination $destination -Force
            }
            throw "playbridge installer: could not replace playbridge.exe safely: $($_.Exception.Message)"
        } finally {
            Remove-Item -LiteralPath $stagedBinary -Force -ErrorAction SilentlyContinue
        }

        Add-PlayBridgeToUserPath -InstallDirectory $installDirectory
        Write-Host "Installed playbridge to $destination"
        Write-Host 'Open a new terminal and run: playbridge'
    } finally {
        Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if ($env:PLAYBRIDGE_INSTALLER_TEST_MODE -ne '1') {
    Install-PlayBridge
}
