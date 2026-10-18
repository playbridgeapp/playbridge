# PlayBridge Desktop installer for Windows.
#   irm https://playbridge.app/desktop.ps1 | iex
#
# Installs (or updates) the current Desktop release for this user only, without
# administrator rights: %LOCALAPPDATA%\Programs\PlayBridge, a Start menu shortcut,
# and an "Installed apps" entry whose uninstaller removes all of it. The release
# archive is verified against the SHA-256 published by playbridge.app (or GitHub
# when a version is pinned) before anything is installed.
#
# Optional environment variables:
#   PLAYBRIDGE_VERSION       install this version (e.g. 0.16.0) from GitHub
#   PLAYBRIDGE_INSTALL_DIR   install somewhere other than %LOCALAPPDATA%\Programs\PlayBridge
#   PLAYBRIDGE_NO_LAUNCH=1   do not start PlayBridge after installing
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$script:MaxArchiveBytes = 512MB
$script:AssetPattern = '^playbridge-desktop-windows-\d+\.\d+\.\d+\.zip$'
$script:ExeName = 'playbridge_desktop.exe'
$script:UninstallKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\PlayBridgeDesktop'
$script:Repository = 'playbridgeapp/playbridge'

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
            throw "PlayBridge installer: unsupported Windows architecture: $Architecture"
        }
    }
}

function Assert-PlayBridgeManifest {
    param([Parameter(Mandatory = $true)]$Manifest)

    if ($Manifest.schemaVersion -ne 1 -or
        $Manifest.product -ne 'desktop' -or
        $Manifest.channel -ne 'stable') {
        throw 'PlayBridge installer: the update service returned an unsupported manifest'
    }
    if ([string]$Manifest.version -notmatch '^\d+\.\d+\.\d+$') {
        throw 'PlayBridge installer: the update service returned an invalid version'
    }
    Assert-PlayBridgeAsset -Name ([string]$Manifest.asset.name) -Url ([string]$Manifest.asset.url) `
        -Sha256 ([string]$Manifest.asset.sha256) -Size $Manifest.asset.size
}

function Assert-PlayBridgeAsset {
    param(
        [string]$Name,
        [string]$Url,
        [string]$Sha256,
        $Size
    )

    if ($Name -notmatch $script:AssetPattern) {
        throw 'PlayBridge installer: the release has no Windows Desktop archive'
    }
    [Uri]$assetUri = $null
    if (-not [Uri]::TryCreate($Url, [UriKind]::Absolute, [ref]$assetUri) -or
        $assetUri.Scheme -ne 'https') {
        throw 'PlayBridge installer: the release has an insecure download URL'
    }
    if ($Sha256 -notmatch '^[a-fA-F0-9]{64}$') {
        throw 'PlayBridge installer: the release has no valid SHA-256 checksum'
    }
    if ($null -ne $Size) {
        $bytes = [UInt64]$Size
        if ($bytes -eq 0 -or $bytes -gt $script:MaxArchiveBytes) {
            throw 'PlayBridge installer: the release archive has an invalid size'
        }
    }
}

# GitHub reports each release asset's checksum as "sha256:<hex>".
function Get-AssetDigestSha256 {
    param([string]$Digest)

    if ($Digest -match '^sha256:([a-fA-F0-9]{64})$') {
        return $Matches[1].ToLowerInvariant()
    }
    return $null
}

function Get-PinnedRelease {
    param([Parameter(Mandatory = $true)][string]$Version)

    $Version = $Version.Trim()
    if ($Version.StartsWith('desktop-v')) {
        $Version = $Version.Substring(9)
    } elseif ($Version.StartsWith('v')) {
        $Version = $Version.Substring(1)
    }
    if ($Version -notmatch '^\d+\.\d+\.\d+$') {
        throw "PlayBridge installer: PLAYBRIDGE_VERSION must look like 0.16.0 (got '$Version')"
    }
    $headers = @{ 'Accept' = 'application/vnd.github+json'; 'User-Agent' = 'PlayBridge-Installer' }
    if ($env:GITHUB_TOKEN) {
        # Optional, for CI rate limits; only ever sent to api.github.com.
        $headers['Authorization'] = "Bearer $env:GITHUB_TOKEN"
    }
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$script:Repository/releases/tags/desktop-v$Version" `
        -Headers $headers
    $asset = @($release.assets | Where-Object { [string]$_.name -match $script:AssetPattern }) | Select-Object -First 1
    if (-not $asset) {
        throw "PlayBridge installer: Desktop v$Version has no Windows archive"
    }
    $digestProperty = $asset.PSObject.Properties['digest']
    $sha256 = if ($digestProperty) { Get-AssetDigestSha256 -Digest ([string]$digestProperty.Value) } else { $null }
    Assert-PlayBridgeAsset -Name ([string]$asset.name) -Url ([string]$asset.browser_download_url) -Sha256 ([string]$sha256) -Size $asset.size
    return [PSCustomObject]@{
        Version = $Version
        Url = [string]$asset.browser_download_url
        Sha256 = $sha256
        Size = [UInt64]$asset.size
    }
}

function Assert-SafeArchiveEntries {
    param([Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$Entries)

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
            throw "PlayBridge installer: the release archive contains an unsafe path: $entry"
        }
    }
}

function Expand-PlayBridgeArchive {
    param(
        [Parameter(Mandatory = $true)][string]$ArchivePath,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        Assert-SafeArchiveEntries -Entries @($zip.Entries | ForEach-Object { $_.FullName })
    } finally {
        $zip.Dispose()
    }
    [IO.Compression.ZipFile]::ExtractToDirectory($ArchivePath, $Destination)
}

# The uninstall command shown in "Installed apps". The uninstaller lives outside
# the install folder so it can delete that folder.
function Get-UninstallCommand {
    param([Parameter(Mandatory = $true)][string]$UninstallScript)

    return "powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$UninstallScript`""
}

function Get-UninstallScript {
    param(
        [Parameter(Mandatory = $true)][string]$InstallDirectory,
        [Parameter(Mandatory = $true)][string]$ShortcutPath
    )

    # Values are embedded as single-quoted PowerShell literals.
    $installLiteral = "'" + $InstallDirectory.Replace("'", "''") + "'"
    $shortcutLiteral = "'" + $ShortcutPath.Replace("'", "''") + "'"
    $keyLiteral = "'" + $script:UninstallKey.Replace("'", "''") + "'"
    return @"
# PlayBridge Desktop uninstaller (generated by the installer).
`$ErrorActionPreference = 'Continue'
`$installDirectory = $installLiteral
Get-Process -Name 'playbridge_desktop' -ErrorAction SilentlyContinue |
    Where-Object { `$_.Path -and `$_.Path.StartsWith(`$installDirectory, [StringComparison]::OrdinalIgnoreCase) } |
    Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 500
# Browser native messaging and the Explorer "Play on TV" menu registered by the app.
foreach (`$vendor in @('Google\Chrome', 'Chromium', 'Microsoft\Edge', 'BraveSoftware\Brave-Browser', 'Mozilla')) {
    Remove-Item -LiteralPath "HKCU:\Software\`$vendor\NativeMessagingHosts\com.playbridge.host" -Recurse -Force -ErrorAction SilentlyContinue
}
foreach (`$type in @('video', 'audio')) {
    Remove-Item -LiteralPath "HKCU:\Software\Classes\SystemFileAssociations\`$type\shell\PlayBridge" -Recurse -Force -ErrorAction SilentlyContinue
}
Remove-Item -LiteralPath $shortcutLiteral -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath `$installDirectory -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $keyLiteral -Recurse -Force -ErrorAction SilentlyContinue
Write-Host 'PlayBridge Desktop was uninstalled. Your settings and pairings were kept.'
Remove-Item -LiteralPath `$MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue
"@
}

function Stop-InstalledPlayBridge {
    param([Parameter(Mandatory = $true)][string]$InstallDirectory)

    $running = @(Get-Process -Name 'playbridge_desktop' -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -and $_.Path.StartsWith($InstallDirectory, [StringComparison]::OrdinalIgnoreCase) })
    if ($running.Count -eq 0) {
        return $false
    }
    Write-Host 'Closing the running PlayBridge Desktop to update it...'
    $running | ForEach-Object { [void]$_.CloseMainWindow() }
    $running | Wait-Process -Timeout 5 -ErrorAction SilentlyContinue
    $running | Where-Object { -not $_.HasExited } | Stop-Process -Force -ErrorAction SilentlyContinue
    $running | Wait-Process -Timeout 10 -ErrorAction SilentlyContinue
    return $true
}

function New-StartMenuShortcut {
    param(
        [Parameter(Mandatory = $true)][string]$ShortcutPath,
        [Parameter(Mandatory = $true)][string]$TargetPath
    )

    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($ShortcutPath)
    $shortcut.TargetPath = $TargetPath
    $shortcut.WorkingDirectory = Split-Path -Parent $TargetPath
    $shortcut.IconLocation = "$TargetPath,0"
    $shortcut.Description = 'PlayBridge Desktop'
    $shortcut.Save()
}

function Register-PlayBridgeUninstall {
    param(
        [Parameter(Mandatory = $true)][string]$InstallDirectory,
        [Parameter(Mandatory = $true)][string]$Version,
        [Parameter(Mandatory = $true)][string]$UninstallScript
    )

    New-Item -Path $script:UninstallKey -Force | Out-Null
    $values = @{
        DisplayName = 'PlayBridge Desktop'
        DisplayVersion = $Version
        Publisher = 'PlayBridge'
        InstallLocation = $InstallDirectory
        DisplayIcon = Join-Path $InstallDirectory $script:ExeName
        UninstallString = Get-UninstallCommand -UninstallScript $UninstallScript
        URLInfoAbout = 'https://playbridge.app'
    }
    foreach ($name in $values.Keys) {
        New-ItemProperty -Path $script:UninstallKey -Name $name -Value $values[$name] -PropertyType String -Force | Out-Null
    }
    foreach ($name in @('NoModify', 'NoRepair')) {
        New-ItemProperty -Path $script:UninstallKey -Name $name -Value 1 -PropertyType DWord -Force | Out-Null
    }
}

function Install-PlayBridgeDesktop {
    $target = Get-PlayBridgeWindowsTarget
    if ($target.Emulated) {
        Write-Host 'Windows ARM64 detected; installing the x64 build, which runs under Windows emulation.'
    }

    $updateBaseUrl = if ($env:PLAYBRIDGE_UPDATE_BASE_URL) {
        $env:PLAYBRIDGE_UPDATE_BASE_URL.TrimEnd('/')
    } else {
        'https://playbridge.app'
    }
    $installDirectory = if ($env:PLAYBRIDGE_INSTALL_DIR) {
        $env:PLAYBRIDGE_INSTALL_DIR.TrimEnd([char[]]'\/')
    } else {
        Join-Path $env:LOCALAPPDATA 'Programs\PlayBridge'
    }

    if ($env:PLAYBRIDGE_VERSION) {
        $release = Get-PinnedRelease -Version $env:PLAYBRIDGE_VERSION
    } else {
        $manifest = Invoke-RestMethod -Uri "$updateBaseUrl/api/v1/updates/desktop?os=windows&arch=$($target.Arch)" `
            -Headers @{ 'User-Agent' = 'PlayBridge-Installer' }
        Assert-PlayBridgeManifest -Manifest $manifest
        $size = $null
        if ($null -ne $manifest.asset.size) {
            $size = [UInt64]$manifest.asset.size
        }
        $release = [PSCustomObject]@{
            Version = [string]$manifest.version
            Url = [string]$manifest.asset.url
            Sha256 = ([string]$manifest.asset.sha256).ToLowerInvariant()
            Size = $size
        }
    }

    $temporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) "playbridge-desktop-$([Guid]::NewGuid().ToString('N'))"
    $archivePath = Join-Path $temporaryDirectory 'playbridge-desktop.zip'
    New-Item -ItemType Directory -Path $temporaryDirectory -Force | Out-Null
    # Staged beside the install folder (same drive) so the final swap is a rename.
    $suffix = [Guid]::NewGuid().ToString('N')
    $stagedDirectory = "$installDirectory.installing-$suffix"
    $backupDirectory = "$installDirectory.previous-$suffix"

    try {
        Write-Host "Downloading PlayBridge Desktop v$($release.Version)..."
        $previousProgress = $ProgressPreference
        $ProgressPreference = 'SilentlyContinue'  # the progress bar slows Windows PowerShell downloads a lot
        try {
            Invoke-WebRequest -Uri $release.Url -OutFile $archivePath -UseBasicParsing
        } finally {
            $ProgressPreference = $previousProgress
        }

        $archiveSize = (Get-Item -LiteralPath $archivePath).Length
        if ($archiveSize -le 0 -or $archiveSize -gt $script:MaxArchiveBytes) {
            throw 'PlayBridge installer: the downloaded archive has an invalid size'
        }
        if ($null -ne $release.Size -and $archiveSize -ne $release.Size) {
            throw 'PlayBridge installer: the downloaded archive size does not match the release'
        }
        $actualChecksum = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualChecksum -ne $release.Sha256) {
            throw 'PlayBridge installer: checksum verification failed'
        }

        New-Item -ItemType Directory -Path (Split-Path -Parent $installDirectory) -Force | Out-Null
        Expand-PlayBridgeArchive -ArchivePath $archivePath -Destination $stagedDirectory
        if (-not (Test-Path -LiteralPath (Join-Path $stagedDirectory $script:ExeName) -PathType Leaf)) {
            throw "PlayBridge installer: $script:ExeName is missing from the release archive"
        }

        # Swap folders so a failed update leaves the previous install in place.
        $wasRunning = Stop-InstalledPlayBridge -InstallDirectory $installDirectory
        $hadExisting = Test-Path -LiteralPath $installDirectory
        try {
            if ($hadExisting) {
                Move-Item -LiteralPath $installDirectory -Destination $backupDirectory
            }
            Move-Item -LiteralPath $stagedDirectory -Destination $installDirectory
        } catch {
            if (Test-Path -LiteralPath $backupDirectory) {
                Remove-Item -LiteralPath $installDirectory -Recurse -Force -ErrorAction SilentlyContinue
                Move-Item -LiteralPath $backupDirectory -Destination $installDirectory -Force
            }
            throw "PlayBridge installer: could not replace the existing install (is PlayBridge still running?): $($_.Exception.Message)"
        }
        if ($hadExisting) {
            Remove-Item -LiteralPath $backupDirectory -Recurse -Force -ErrorAction SilentlyContinue
        }

        $exePath = Join-Path $installDirectory $script:ExeName
        $programsDirectory = [Environment]::GetFolderPath('Programs')
        $shortcutPath = Join-Path $programsDirectory 'PlayBridge.lnk'
        New-StartMenuShortcut -ShortcutPath $shortcutPath -TargetPath $exePath

        $uninstallScript = Join-Path $env:LOCALAPPDATA 'PlayBridge\uninstall-desktop.ps1'
        New-Item -ItemType Directory -Path (Split-Path -Parent $uninstallScript) -Force | Out-Null
        Set-Content -LiteralPath $uninstallScript -Encoding UTF8 `
            -Value (Get-UninstallScript -InstallDirectory $installDirectory -ShortcutPath $shortcutPath)
        Register-PlayBridgeUninstall -InstallDirectory $installDirectory -Version $release.Version -UninstallScript $uninstallScript

        Write-Host "Installed PlayBridge Desktop v$($release.Version) to $installDirectory"
        Write-Host 'Find it in the Start menu as "PlayBridge"; uninstall it from Settings > Apps > Installed apps.'
        if ($env:PLAYBRIDGE_NO_LAUNCH -ne '1' -or $wasRunning) {
            Start-Process -FilePath $exePath -WorkingDirectory $installDirectory
        }
    } finally {
        Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $stagedDirectory -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if ($env:PLAYBRIDGE_INSTALLER_TEST_MODE -ne '1') {
    Install-PlayBridgeDesktop
}
