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
#   $env:YUKTA_CHECK = '1'                   # say whether a newer release exists; install nothing
#   $env:YUKTA_FORCE = '1'                   # reinstall even when this version is already installed
#
# It downloads SHA256SUMS and the files for your system from the GitHub release, stops if a
# checksum (or, when cosign is installed, the Sigstore signature of SHA256SUMS) does not match, keeps
# the previous programs as yukta.exe.prev and yukta-cli.exe.prev, copies the programs into your user folder and adds that folder to your
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

    # Animation and colour only on a terminal (and never with NO_COLOR). Yukta logo colours:
    # orange #E85D0C and blue #2F6BFF.
    $fun = (-not [Console]::IsOutputRedirected) -and (-not $env:NO_COLOR) -and ($Host.UI.SupportsVirtualTerminal -or $env:WT_SESSION)
    $esc = [char]27
    $O = if ($fun) { "$esc[38;2;232;93;12m" } else { '' }
    $U = if ($fun) { "$esc[38;2;47;107;255m" } else { '' }
    $B = if ($fun) { "$esc[1m" } else { '' }
    $Z = if ($fun) { "$esc[0m" } else { '' }

    # Download with a spinner on a terminal; plain Invoke-WebRequest otherwise.
    function Get-File($url, $out, $label) {
        if (-not $fun) { Invoke-WebRequest -UseBasicParsing $url -OutFile $out; return }
        $job = Start-Job { param($u, $o) $ProgressPreference = 'SilentlyContinue'; Invoke-WebRequest -UseBasicParsing $u -OutFile $o } -ArgumentList $url, $out
        $frames = '|', '/', '-', '\'; $i = 0
        while ($job.State -eq 'Running') {
            Write-Host -NoNewline "`r  $U$($frames[$i % 4])$Z $label "
            $i++; Start-Sleep -Milliseconds 100
        }
        Write-Host -NoNewline "`r$esc[K"
        try { Receive-Job $job -ErrorAction Stop | Out-Null } finally { Remove-Job $job -Force }
    }

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
    # The version goes into URLs and file names: X.Y.Z or X.Y.Z-rc.1, nothing else (\z: no trailing newline).
    if ($version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?\z') { Fail "'$version' is not a version (expected X.Y.Z or X.Y.Z-rc.1)" }

    $cliPath = Join-Path $dir 'yukta-cli.exe'
    $found = $null
    if (Test-Path $cliPath) { $found = ([regex]::Match((& $cliPath --version 2>$null | Out-String), '[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?')).Value }
    if ($env:YUKTA_CHECK -eq '1') {
        Write-Host "installed: $(if ($found) { $found } else { "none in $dir" })"
        Write-Host "latest:    $version"
        if ($found -eq $version) { return }
        Write-Host 'A newer release is available; run this script again to upgrade.'
        $global:LASTEXITCODE = 1
        return
    }
    if ($found -eq $version -and $env:YUKTA_FORCE -ne '1') { Write-Host "Yukta $version is already installed in $dir (set YUKTA_FORCE=1 to reinstall)."; return }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("yukta-install-" + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        Write-Host "$U${B}Yukta installer$Z"
        if ($found) { Write-Host "  found:      $found in $dir" }
        Write-Host "  installing: $version  (windows/amd64)  into $dir"
        $sumsPath = Join-Path $tmp 'SHA256SUMS'
        Invoke-WebRequest -UseBasicParsing "$baseUrl/v$version/SHA256SUMS" -OutFile $sumsPath

        $verified = 'checksum'
        $sigNote = 'signature not checked (install cosign to check it)'
        if (Get-Command cosign -ErrorAction SilentlyContinue) {
            $bundle = Join-Path $tmp 'SHA256SUMS.sigstore.json'
            try { Invoke-WebRequest -UseBasicParsing "$baseUrl/v$version/SHA256SUMS.sigstore.json" -OutFile $bundle } catch { $bundle = $null }
            if ($bundle) {
                & cosign verify-blob --bundle $bundle --certificate-identity-regexp "^https://github.com/$repo/" --certificate-oidc-issuer https://token.actions.githubusercontent.com $sumsPath *> $null
                if ($LASTEXITCODE -ne 0) { Fail 'signature of SHA256SUMS does not verify; not installing' }
                $verified = 'checksum, signature (cosign)'; $sigNote = $null
            } else { $sigNote = 'signature not checked (this release has no SHA256SUMS.sigstore.json)' }
        }

        function Keep-Prev($name) {
            $p = Join-Path $dir $name
            if (Test-Path $p) { Copy-Item $p "$p.prev" -Force; Set-Variable -Scope 1 -Name prevKept -Value $true }
        }
        $prevKept = $false

        $sums = @{}
        foreach ($line in Get-Content $sumsPath) {
            if ($line -match '^([0-9a-fA-F]{64})\s+\*?(.+)$') { $sums[$Matches[2].Trim()] = $Matches[1].ToLower() }
        }

        function Get-Verified($name) {
            if (-not $sums.ContainsKey($name)) { Fail "$name is not listed in SHA256SUMS" }
            $path = Join-Path $tmp $name
            Get-File "$baseUrl/v$version/$name" $path "downloading $name"
            if ((Get-FileHash $path -Algorithm SHA256).Hash.ToLower() -ne $sums[$name]) { Fail "checksum mismatch for $name; not installing" }
            Write-Host "  $O$([char]0x2713)$Z verified    $name"
            return $path
        }

        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $archive = "yukta-$version-windows-amd64.zip"
        $installedServer = $false
        if ($sums.ContainsKey($archive)) {
            $zip = Get-Verified $archive
            # Not Expand-Archive: it draws its own progress bar even with $ProgressPreference set.
            Add-Type -AssemblyName System.IO.Compression.FileSystem
            [IO.Compression.ZipFile]::ExtractToDirectory($zip, $tmp)
            $src = Join-Path $tmp "yukta-$version-windows-amd64"
            if (-not $cliOnly) { Keep-Prev 'yukta.exe'; Copy-Item (Join-Path $src 'yukta.exe') $dir -Force; $installedServer = $true }
            if (-not $serverOnly) { Keep-Prev 'yukta-cli.exe'; Copy-Item (Join-Path $src 'yukta-cli.exe') $dir -Force }
        } else {
            if ($serverOnly) { Fail 'this release has no native server for Windows; use Docker Desktop' }
            $cli = Get-Verified 'yukta-cli-windows-amd64.exe'
            Keep-Prev 'yukta-cli.exe'
            Copy-Item $cli (Join-Path $dir 'yukta-cli.exe') -Force
        }

        Write-Host ''
        if (-not $cliOnly -and -not $installedServer) {
            Write-Host 'Note: this release has no native Windows server yet. Run the server with Docker Desktop instead:'
            Write-Host "  docker run --rm -p 127.0.0.1:8080:8080 -e YUKTA_API_KEY=change-me ghcr.io/infenia/yukta:$version"
        }
        Write-Host "  verified:   $verified$(if ($sigNote) { "  [$sigNote]" })"
        if ($installedServer) { Write-Host "Installed: $(Join-Path $dir 'yukta.exe') (server) $version" }
        if (-not $serverOnly) { Write-Host "Installed: $(Join-Path $dir 'yukta-cli.exe') (client) $version" }
        if ($prevKept) { Write-Host 'Previous version kept as *.prev; to roll back move them back, or set YUKTA_VERSION to the old version.' }
        if ($installedServer -and (Get-Process -Name yukta -ErrorAction SilentlyContinue)) {
            Write-Host ''
            Write-Host 'Note: a yukta server is running and keeps running the old version. Stop it, start the new one'
            Write-Host 'and re-apply your sessions; sessions and runs kept in memory are lost on restart (set'
            Write-Host 'YUKTA_BOOTSTRAP_DIR to reload them; see https://yukta.infenia.com/operations/persistence).'
        }
        foreach ($f in 'yukta.exe', 'yukta-cli.exe') {
            $other = Get-Command $f -ErrorAction SilentlyContinue
            if ($other -and (Test-Path (Join-Path $dir $f)) -and $other.Source -ne (Join-Path $dir $f)) { Write-Host "Warning: $($other.Source) comes first on your PATH and hides the $f just installed." }
        }

        $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
        $entries = if ($userPath) { $userPath -split ';' } else { @() }
        if (-not ($entries | Where-Object { $_.TrimEnd('\') -eq $dir.TrimEnd('\') })) {
            [Environment]::SetEnvironmentVariable('Path', (($entries + $dir) | Where-Object { $_ }) -join ';', 'User')
            $env:Path = "$env:Path;$dir"
            Write-Host "Added $dir to your user PATH. Open a new terminal for other windows to see it."
        }

        Write-Host ''
        Write-Host "$O${B}Done!$Z Yukta $version is ready."
        Write-Host 'Next:'
        if ($installedServer) { Write-Host '  yukta                          # start the server on http://localhost:8080' }
        if (-not $serverOnly) { Write-Host '  yukta-cli session list         # talk to a running server' }
    }
    finally {
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}
