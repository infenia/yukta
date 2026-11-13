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
#   $env:YUKTA_AGENTS = 'auto'               # connect AI agents (Claude Code, Codex, Antigravity, Cursor, VS Code,
#                                            # Windsurf, OpenCode, ...) to Yukta: the MCP server and the Yukta skills.
#                                            # ask (default: on a terminal, shows the agents found and asks first),
#                                            # auto (every agent found), none, or a list such as 'claude,codex' or 'all'
#   $env:YUKTA_NO_ANIMATION = '1'            # plain output: no colour, spinner or progress bar
#   $env:YUKTA_QUIET = '1'                   # print nothing but errors
#
# For agents on another server set $env:YUKTA_SERVER_URL (and $env:YUKTA_TOKEN) first. Later:
# yukta-cli agents list | install | uninstall.
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

    # Animation and colour only on a terminal (and never with NO_COLOR, CI or YUKTA_NO_ANIMATION).
    # Yukta logo colours: orange #E85D0C and blue #2F6BFF.
    $quiet = $env:YUKTA_QUIET -eq '1'
    $fun = (-not $quiet) -and (-not [Console]::IsOutputRedirected) -and (-not $env:NO_COLOR) -and (-not $env:CI) -and (-not $env:YUKTA_NO_ANIMATION) -and ($Host.UI.SupportsVirtualTerminal -or $env:WT_SESSION)
    $esc = [char]27
    $O = if ($fun) { "$esc[38;2;232;93;12m" } else { '' }
    $U = if ($fun) { "$esc[38;2;47;107;255m" } else { '' }
    $B = if ($fun) { "$esc[1m" } else { '' }
    $D = if ($fun) { "$esc[2m" } else { '' }
    $Z = if ($fun) { "$esc[0m" } else { '' }
    function Say($message) { if (-not $quiet) { Write-Host $message } }

    # The steps are listed up front and each line is redrawn in place as the install moves along.
    $nSteps = 6
    $st = @{ cur = 0; text = ''; t0 = Get-Date }
    $OK = [char]0x2713; $BAD = [char]0x2717; $TODO = [char]0x25CB; $RUN = [char]0x25B8
    $spin = '|', '/', '-', '\'
    if ($fun) { $spin = 0x280B, 0x2819, 0x2839, 0x2838, 0x283C, 0x2834, 0x2826, 0x2827, 0x2807, 0x280F | ForEach-Object { [char]$_ } }

    # Redraw tracker line $n; the cursor rests on the line below the list.
    function Mark($n, $glyph, $text) {
        if (-not $fun) { return }
        $up = $nSteps - $n + 1
        Write-Host -NoNewline "$esc[${up}A`r$esc[K  $glyph $text$esc[${up}B`r"
    }
    function Plan {
        if (-not $fun) { return }
        Write-Host -NoNewline "$esc[?25l"
        foreach ($l in 'Detect system', 'Fetch checksums', 'Download', 'Verify', 'Install', 'Check PATH') { Write-Host "  $D$TODO $l$Z" }
    }
    function Step-Start($n, $text) { $st.cur = $n; $st.text = $text; $st.t0 = Get-Date; Mark $n "$U$RUN$Z" $text }
    function Step-Done($n, $text, $detail) {
        $st.cur = 0
        $secs = [int]((Get-Date) - $st.t0).TotalSeconds
        $t = if ($secs -gt 0) { " ${secs}s" } else { '' }
        if ($fun) { Mark $n "$O$OK$Z" "$text$(if ($detail) { "  $D$detail$t$Z" })" }
        else { Say "  $OK $text$(if ($detail) { "  ($detail$t)" })" }
    }
    function Mb($bytes) { '{0:N1}' -f ($bytes / 1MB) }

    # Download <url> to <out>; on a terminal, draw a progress bar on tracker line $n from the size of the file so far.
    function Get-File($url, $out, $n, $label) {
        if (-not $fun) { Invoke-WebRequest -UseBasicParsing $url -OutFile $out; return }
        $total = 0
        try { $total = [long](@((Invoke-WebRequest -UseBasicParsing -Method Head $url).Headers['Content-Length'])[0]) } catch { }
        $job = Start-Job { param($u, $o) $ProgressPreference = 'SilentlyContinue'; Invoke-WebRequest -UseBasicParsing $u -OutFile $o } -ArgumentList $url, $out
        try {
            $i = 0; $t0 = Get-Date
            while ($job.State -eq 'Running') {
                $got = if (Test-Path $out) { (Get-Item $out).Length } else { 0 }
                $text = $label
                if ($total -gt 0) {
                    $pct = [Math]::Min(100, [int]($got * 100 / $total))
                    $fill = [int]($pct * 16 / 100)
                    $text += "  $O$([string][char]0x2588 * $fill)$([string][char]0x2591 * (16 - $fill))$Z $pct%  $(Mb $got)/$(Mb $total) MB"
                } else { $text += "  $(Mb $got) MB" }
                $secs = ((Get-Date) - $t0).TotalSeconds
                if ($secs -ge 1 -and $got -gt 0) {
                    $bps = $got / $secs
                    $text += "  $(Mb $bps) MB/s"
                    if ($total -gt 0) { $text += "  ETA $([int](($total - $got) / $bps))s" }
                }
                Mark $n "$U$($spin[$i % $spin.Count])$Z" $text
                $i++; Start-Sleep -Milliseconds 100
            }
            Receive-Job $job -ErrorAction Stop | Out-Null
        } finally { Remove-Job $job -Force }
    }

    function Fail($message) { throw "install: $message" }

    $dir = if ($env:YUKTA_INSTALL_DIR) { $env:YUKTA_INSTALL_DIR } else { Join-Path $env:LOCALAPPDATA 'Programs\Yukta' }
    $cliOnly = $env:YUKTA_CLI_ONLY -eq '1'
    $serverOnly = $env:YUKTA_SERVER_ONLY -eq '1'
    if ($cliOnly -and $serverOnly) { Fail 'YUKTA_CLI_ONLY and YUKTA_SERVER_ONLY cannot be combined' }
    $agents = if ($env:YUKTA_AGENTS) { $env:YUKTA_AGENTS } else { 'ask' }
    if ($agents -notmatch '^[a-z,]+\z') { Fail "YUKTA_AGENTS takes ask, auto, none, all or a list like claude,codex (got '$agents')" }

    if ($env:YUKTA_UNINSTALL -eq '1') {
        # Disconnect the agents first: the client that knows how is about to go.
        $cliExe = Join-Path $dir 'yukta-cli.exe'
        if (Test-Path $cliExe) {
            try {
                & $cliExe agents uninstall --all *> $null
                if ($LASTEXITCODE -eq 0) { Say 'disconnected the AI agents Yukta had set up' }
            } catch { }
        }
        foreach ($f in 'yukta.exe', 'yukta-cli.exe') {
            $p = Join-Path $dir $f
            if (Test-Path $p) { Remove-Item $p -Force; Say "removed $p" }
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
    # Windows 11 on ARM (build 22000 and later) runs 64-bit Intel programs through emulation.
    if ($arch -eq 'ARM64' -and [Environment]::OSVersion.Version.Build -ge 22000) {
        Say 'Note: no ARM build is published; installing the 64-bit Intel/AMD build (runs through emulation).'
    } elseif ($arch -ne 'AMD64') {
        Fail "unsupported processor: $arch (only 64-bit Intel/AMD is published; use Docker)"
    }

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
    if ($found -eq $version -and $env:YUKTA_FORCE -ne '1') { Say "Yukta $version is already installed in $dir (set YUKTA_FORCE=1 to reinstall)."; return }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("yukta-install-" + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tmp | Out-Null
    $tStart = Get-Date
    try {
        Say "$U${B}Yukta installer$Z"
        if ($found) { Say "  found:      $found in $dir" }
        Say "  installing: $version  (windows/amd64)  into $dir"
        Say ''
        Plan
        $st.t0 = $tStart; Step-Done 1 'Detect system' 'windows/amd64'

        Step-Start 2 'Fetch checksums'
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
        Step-Done 2 'Fetch checksums' $(if ($sigNote) { 'checksum only' } else { 'signature verified (cosign)' })

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
            $label = if ($name -like '*.zip') { 'Download server + client' } else { 'Download client' }
            Step-Start 3 $label
            Get-File "$baseUrl/v$version/$name" $path 3 $label
            Step-Done 3 $label "$(Mb (Get-Item $path).Length) MB"
            Step-Start 4 'Verify checksum'
            if ((Get-FileHash $path -Algorithm SHA256).Hash.ToLower() -ne $sums[$name]) { Fail "checksum mismatch for $name; not installing" }
            Step-Done 4 'Verify checksum' "sha256 of $name matches"
            return $path
        }

        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $archive = "yukta-$version-windows-amd64.zip"
        $installedServer = $false
        if ($sums.ContainsKey($archive)) {
            $zip = Get-Verified $archive
            Step-Start 5 'Install'
            # Not Expand-Archive: it draws its own progress bar even with $ProgressPreference set.
            Add-Type -AssemblyName System.IO.Compression.FileSystem
            [IO.Compression.ZipFile]::ExtractToDirectory($zip, $tmp)
            $src = Join-Path $tmp "yukta-$version-windows-amd64"
            if (-not $cliOnly) { Keep-Prev 'yukta.exe'; Copy-Item (Join-Path $src 'yukta.exe') $dir -Force; $installedServer = $true }
            if (-not $serverOnly) { Keep-Prev 'yukta-cli.exe'; Copy-Item (Join-Path $src 'yukta-cli.exe') $dir -Force }
        } else {
            if ($serverOnly) { Fail 'this release has no native server for Windows; use Docker Desktop' }
            $cli = Get-Verified 'yukta-cli-windows-amd64.exe'
            Step-Start 5 'Install'
            Keep-Prev 'yukta-cli.exe'
            Copy-Item $cli (Join-Path $dir 'yukta-cli.exe') -Force
        }
        Step-Done 5 'Install' $dir

        # The user PATH entry is added here, so the whole tracker is finished before other lines print.
        Step-Start 6 'Check PATH'
        $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
        $entries = if ($userPath) { $userPath -split ';' } else { @() }
        $pathAdded = $false
        if (-not ($entries | Where-Object { $_.TrimEnd('\') -eq $dir.TrimEnd('\') })) {
            [Environment]::SetEnvironmentVariable('Path', (($entries + $dir) | Where-Object { $_ }) -join ';', 'User')
            $env:Path = "$env:Path;$dir"
            $pathAdded = $true
        }
        Step-Done 6 'Check PATH' $(if ($pathAdded) { "added $dir to your user PATH" } else { "$dir is on your PATH" })

        Say ''
        if (-not $cliOnly -and -not $installedServer) {
            Say 'Note: this release has no native Windows server yet. Run the server with Docker Desktop instead:'
            Say "  docker run --rm -p 127.0.0.1:9858:9858 -e YUKTA_API_KEYS_0_NAME=admin -e YUKTA_API_KEYS_0_KEY=change-me -e YUKTA_API_KEYS_0_ROLE=ADMIN ghcr.io/infenia/yukta:$version"
        }
        Say "  verified:   $verified$(if ($sigNote) { "  [$sigNote]" })"
        if ($installedServer) { Say "Installed: $(Join-Path $dir 'yukta.exe') (server) $version" }
        if (-not $serverOnly) { Say "Installed: $(Join-Path $dir 'yukta-cli.exe') (client) $version" }
        if ($prevKept) { Say 'Previous version kept as *.prev; to roll back move them back, or set YUKTA_VERSION to the old version.' }
        if ($installedServer -and (Get-Process -Name yukta -ErrorAction SilentlyContinue)) {
            Say ''
            Say 'Note: a yukta server is running and keeps running the old version. Stop it, start the new one'
            Say 'and re-apply your sessions; sessions and runs kept in memory are lost on restart (set'
            Say 'YUKTA_BOOTSTRAP_DIR to reload them; see https://yukta.infenia.com/guides/operate/persist-sessions).'
        }
        foreach ($f in 'yukta.exe', 'yukta-cli.exe') {
            $other = Get-Command $f -ErrorAction SilentlyContinue
            if ($other -and (Test-Path (Join-Path $dir $f)) -and $other.Source -ne (Join-Path $dir $f)) { Say "Warning: $($other.Source) comes first on your PATH and hides the $f just installed." }
        }

        if ($pathAdded) { Say "Open a new terminal for other windows to see the PATH change." }

        # Offer the agent integration (MCP server and skills). It never fails the install: the programs
        # are already there, and `yukta-cli agents install` can be run any time.
        if (-not $serverOnly -and $agents -ne 'none') {
            $cliExe = Join-Path $dir 'yukta-cli.exe'
            try {
                & $cliExe agents install --refresh *> $null   # an upgrade refreshes the skills of agents connected earlier
                $connect = ''
                if ($agents -in 'ask', 'auto') {
                    $found = (& $cliExe agents list -o json | ConvertFrom-Json | Where-Object { $_.detected -and $_.id -ne 'generic' } | ForEach-Object { $_.id }) -join ','
                    $canAsk = (-not $quiet) -and (-not $env:CI) -and [Environment]::UserInteractive -and (-not [Console]::IsInputRedirected)
                    if (-not $found) {
                        Say ''; Say 'No AI agent found to connect (yukta-cli agents list shows what is looked for).'
                    } elseif ($agents -eq 'auto') {
                        $connect = $found
                    } elseif (-not $canAsk) {
                        Say ''; Say "AI agents found: $found. Connect them with: yukta-cli agents install"
                    } else {
                        Say ''
                        $reply = Read-Host "Connect Yukta to your AI agents? Found: $found  [Y] connect them  [n] skip  [c] choose"
                        if ($reply -match '^[Cc]') { $connect = Read-Host 'Agents (comma separated, or "all")' }
                        elseif (-not $reply -or $reply -match '^[Yy]') { $connect = $found }
                        else { Say 'Skipped. Later: yukta-cli agents install' }
                    }
                } else { $connect = $agents }
                if ($connect) {
                    Say ''
                    if ($quiet) { & $cliExe agents install --agent $connect *> $null } else { & $cliExe agents install --agent $connect }
                    if ($LASTEXITCODE -ne 0) { Say "Note: not every agent could be set up (see above). Retry later: yukta-cli agents install --agent $connect" }
                }
            } catch { Say "Note: could not set up the AI agents: $_. Run later: yukta-cli agents install" }
        }

        Say ''
        if ($fun) {
            Write-Host -NoNewline '  '
            foreach ($j in 0..39) { Write-Host -NoNewline "$(if ($j -lt 20) { $O } else { $U })$([char]0x2501)$Z"; Start-Sleep -Milliseconds 15 }
            Write-Host ''
        }
        Say "$O${B}Done!$Z Yukta $version is ready (took $([int]((Get-Date) - $tStart).TotalSeconds)s)."
        function Nx($cmd, $note) { Say ("  $B{0,-34}$Z $D{1}$Z" -f $cmd, $note) }
        Say ''
        Say "$O${B}Get started$Z"
        if ($installedServer) {
            Nx '1  yukta' 'start the server'
            Nx '2  http://localhost:9858' 'open the web UI: sessions, live runs, logs'
            Nx '3  yukta.infenia.com/learn/your-first-workflow' 'build and run one in 5 minutes'
            Say ''
            Say "$U${B}Let your AI assistant drive it$Z"
            if (-not $serverOnly) { Say "  yukta-cli agents install        ${D}# connects Claude Code, Codex, Cursor, ... in one step$Z" }
            Say '  claude mcp add --transport http yukta http://localhost:9858/mcp'
            Say "  ${D}then ask: `"Create a Yukta workflow that runs my tests on every push`"$Z"
        }
        else {
            Nx 'yukta-cli --url http://HOST:9858 session list' 'point the client at a running server'
            Nx 'yukta.infenia.com/learn/your-first-workflow' 'build and run a workflow in 5 minutes'
        }
        Say ''
        Say "$U${B}More$Z"
        if (-not $serverOnly) { Nx 'yukta-cli session list' 'manage sessions and runs from the terminal' }
        Nx 'yukta.infenia.com/examples' 'ready-made workflows: CI gates, pipelines, approvals'
    }
    catch {
        if ($st.cur) { Mark $st.cur "$O$BAD$Z" $st.text }
        throw
    }
    finally {
        if ($fun) { Write-Host -NoNewline "$esc[?25h" }
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}
