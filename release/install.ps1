# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Install Yukta on Windows: the server (yukta.exe) and the command-line client (yukta-cli.exe).
#
#   irm https://github.com/infenia/yukta/releases/latest/download/install.ps1 | iex
#
# The copy attached to a release installs exactly that release. Options are environment variables,
# because a script piped to `iex` cannot take parameters:
#
#   $env:YUKTA_VERSION = '0.1.0-rc.1'        # install this version
#   $env:YUKTA_INSTALL_DIR = 'C:\Tools\Yukta'  # default: %LOCALAPPDATA%\Programs\Yukta
#   $env:YUKTA_CLI_ONLY = '1'                # only yukta-cli
#   $env:YUKTA_SERVER_ONLY = '1'             # only the server
#   $env:YUKTA_UNINSTALL = '1'               # remove the programs and the PATH entry
#
# It downloads SHA256SUMS and the files for your system from the GitHub release, stops if a
# checksum does not match, copies the programs into your user folder and adds that folder to your
# user PATH. It needs no administrator rights and sends nothing anywhere but GitHub.
#
# A native Windows server download is planned. Until a release has one, this script installs the
# client and tells you how to run the server with Docker Desktop.

& {
    $ErrorActionPreference = 'Stop'
    $ProgressPreference = 'SilentlyContinue'
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

    $repo = 'infenia/yukta'
    # release/release.sh replaces this when it attaches the script to a release.
    $defaultVersion = '@VERSION@'
    # Test and mirror hook: a server laid out like GitHub release downloads (<base>/v<ver>/<file>).
    $baseUrl = if ($env:YUKTA_DOWNLOAD_BASE) { $env:YUKTA_DOWNLOAD_BASE } else { "https://github.com/$repo/releases/download" }

    function Fail($message) { throw "install: $message" }

    $dir = if ($env:YUKTA_INSTALL_DIR) { $env:YUKTA_INSTALL_DIR } else { Join-Path $env:LOCALAPPDATA 'Programs\Yukta' }
    $cliOnly = $env:YUKTA_CLI_ONLY -eq '1'
    $serverOnly = $env:YUKTA_SERVER_ONLY -eq '1'
    if ($cliOnly -and $serverOnly) { Fail 'YUKTA_CLI_ONLY and YUKTA_SERVER_ONLY cannot be combined' }

    if ($env:YUKTA_UNINSTALL -eq '1') {
        foreach ($f in 'yukta.exe', 'yukta-cli.exe') {
            $p = Join-Path $dir $f
            if (Test-Path $p) { Remove-Item $p -Force; Write-Host "removed $p" }
        }
        $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
        if ($userPath) {
            $kept = ($userPath -split ';' | Where-Object { $_ -and $_.TrimEnd('\') -ne $dir.TrimEnd('\') }) -join ';'
            [Environment]::SetEnvironmentVariable('Path', $kept, 'User')
        }
        return
    }

    # Only 64-bit Intel/AMD Windows builds are published.
    $arch = if ($env:PROCESSOR_ARCHITEW6432) { $env:PROCESSOR_ARCHITEW6432 } else { $env:PROCESSOR_ARCHITECTURE }
    if ($arch -ne 'AMD64') { Fail "unsupported processor: $arch (only 64-bit Intel/AMD is published; use Docker)" }

    $version = $env:YUKTA_VERSION
    if (-not $version -and $defaultVersion -ne '@VERSION@') { $version = $defaultVersion }
    if (-not $version) {
        $release = Invoke-RestMethod "https://api.github.com/repos/$repo/releases/latest" -Headers @{ 'User-Agent' = 'yukta-install' }
        $version = $release.tag_name
    }
    $version = $version.TrimStart('v')

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("yukta-install-" + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        Write-Host "Installing Yukta $version for windows/amd64 into $dir"
        $sumsPath = Join-Path $tmp 'SHA256SUMS'
        Invoke-WebRequest -UseBasicParsing "$baseUrl/v$version/SHA256SUMS" -OutFile $sumsPath

        $sums = @{}
        foreach ($line in Get-Content $sumsPath) {
            if ($line -match '^([0-9a-fA-F]{64})\s+\*?(.+)$') { $sums[$Matches[2].Trim()] = $Matches[1].ToLower() }
        }

        function Get-Verified($name) {
            if (-not $sums.ContainsKey($name)) { Fail "$name is not listed in SHA256SUMS" }
            $path = Join-Path $tmp $name
            Invoke-WebRequest -UseBasicParsing "$baseUrl/v$version/$name" -OutFile $path
            if ((Get-FileHash $path -Algorithm SHA256).Hash.ToLower() -ne $sums[$name]) { Fail "checksum mismatch for $name; not installing" }
            Write-Host "  verified $name"
            return $path
        }

        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $archive = "yukta-$version-windows-amd64.zip"
        $installedServer = $false
        if ($sums.ContainsKey($archive)) {
            $zip = Get-Verified $archive
            Expand-Archive -Path $zip -DestinationPath $tmp -Force
            $src = Join-Path $tmp "yukta-$version-windows-amd64"
            if (-not $cliOnly) { Copy-Item (Join-Path $src 'yukta.exe') $dir -Force; $installedServer = $true }
            if (-not $serverOnly) { Copy-Item (Join-Path $src 'yukta-cli.exe') $dir -Force }
        } else {
            if ($serverOnly) { Fail 'this release has no native server for Windows; use Docker Desktop' }
            $cli = Get-Verified 'yukta-cli-windows-amd64.exe'
            Copy-Item $cli (Join-Path $dir 'yukta-cli.exe') -Force
        }

        Write-Host ''
        if (-not $cliOnly -and -not $installedServer) {
            Write-Host 'Note: this release has no native Windows server yet. Run the server with Docker Desktop instead:'
            Write-Host "  docker run --rm -p 127.0.0.1:8080:8080 -e YUKTA_API_KEY=change-me ghcr.io/infenia/yukta:$version"
        }
        if ($installedServer) { Write-Host "Installed: $(Join-Path $dir 'yukta.exe') (server)" }
        if (-not $serverOnly) { Write-Host "Installed: $(Join-Path $dir 'yukta-cli.exe') (client)" }

        $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
        $entries = if ($userPath) { $userPath -split ';' } else { @() }
        if (-not ($entries | Where-Object { $_.TrimEnd('\') -eq $dir.TrimEnd('\') })) {
            [Environment]::SetEnvironmentVariable('Path', (($entries + $dir) | Where-Object { $_ }) -join ';', 'User')
            $env:Path = "$env:Path;$dir"
            Write-Host "Added $dir to your user PATH. Open a new terminal for other windows to see it."
        }

        Write-Host ''
        Write-Host 'Next:'
        if ($installedServer) { Write-Host '  yukta                          # start the server on http://localhost:8080' }
        if (-not $serverOnly) { Write-Host '  yukta-cli session list         # talk to a running server' }
    }
    finally {
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}
