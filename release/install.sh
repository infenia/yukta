#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Install Yukta: the server (yukta) and the command-line client (yukta-cli) in one step.
#
#   curl -fsSL https://github.com/infenia/yukta/releases/latest/download/install.sh | sh
#
# The copy attached to a release installs exactly that release. Options go after `sh -s --`:
#
#   curl -fsSL <url> | sh -s -- --version 0.1.0-rc.1 --install-dir /opt/yukta/bin
#
# Options (environment variable in brackets):
#   --version <X.Y.Z>     install this version                       [YUKTA_VERSION]
#   --install-dir <dir>   where to put the programs                  [YUKTA_INSTALL_DIR]
#                         default: /usr/local/bin as root, else ~/.local/bin
#   --cli-only            install only yukta-cli
#   --server-only         install only the server
#   --check               say whether a newer release exists; install nothing (exit 1 if behind)
#   --force               reinstall even when this version is already installed
#   --agents <how>        connect AI agents (Claude Code, Codex, Antigravity, Cursor, VS Code,
#                         Windsurf, OpenCode, ...) to Yukta: the MCP server and the Yukta skills
#                           ask (default)  on a terminal, show the agents found and ask first
#                           auto           connect every agent found, without asking
#                           none           do not touch any agent
#                           codex,cursor   connect exactly these (or "all")        [YUKTA_AGENTS]
#   --uninstall           remove yukta and yukta-cli and disconnect the agents they connected
#   --no-animation        plain output: no colour, spinner or progress bar    [YUKTA_NO_ANIMATION]
#   --quiet               print nothing but errors
#   --help
#
# On a terminal the install shows its steps, a progress bar per download and a summary; with output
# redirected, NO_COLOR, TERM=dumb or CI set, it prints one plain line per step instead.
#
# Agents: set YUKTA_SERVER_URL (and YUKTA_TOKEN, or use `yukta-cli agents install --token-file`) before
# running to point the agents at another server. Later: yukta-cli agents list | install | uninstall.
#
# What it does: downloads SHA256SUMS and the files for your system from the GitHub release,
# refuses to continue if a checksum does not match (and, when cosign is installed, if the Sigstore
# signature of SHA256SUMS does not verify), and copies the programs into place. Running it again
# upgrades: the previous programs are kept as yukta.prev and yukta-cli.prev, and to roll back you
# can move them back or pass --version <old>. It does not use sudo, change your shell profile or
# send anything anywhere but GitHub.
#
# Native server downloads exist for Linux. On macOS the script installs the client and tells you how to
# run the server with Docker, until a native macOS server is published.
# Windows: use install.ps1.

set -eu

REPO="infenia/yukta"
# release/release.sh replaces this when it attaches the script to a release.
DEFAULT_VERSION="@VERSION@"
# Test and mirror hook: a server laid out like GitHub release downloads (<base>/v<ver>/<file>).
BASE_URL="${YUKTA_DOWNLOAD_BASE:-https://github.com/$REPO/releases/download}"
# Test hook: where the agents question is answered. stdin is the script itself under `curl | sh`.
TTY="${YUKTA_TTY:-/dev/tty}"

say() { printf '%s\n' "$*"; }
# Animation and colour only on a terminal (and never with NO_COLOR, CI or --no-animation), so pipes
# and logs stay plain.
plain() { fun=0; O=""; U=""; B=""; D=""; Z=""; }
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ] && [ -z "${CI:-}" ] && [ -z "${YUKTA_NO_ANIMATION:-}" ] &&
  [ "${TERM:-dumb}" != "dumb" ]; then
  # The Yukta logo colours: orange #E85D0C and blue #2F6BFF (256-colour approximations without truecolor).
  case "${COLORTERM:-}" in
    truecolor | 24bit) O="$(printf '\033[38;2;232;93;12m')"; U="$(printf '\033[38;2;47;107;255m')" ;;
    *) O="$(printf '\033[38;5;166m')"; U="$(printf '\033[38;5;69m')" ;;
  esac
  fun=1; B="$(printf '\033[1m')"; D="$(printf '\033[2m')"; Z="$(printf '\033[0m')"
else
  plain
fi
# Unicode glyphs only in a UTF-8 locale.
case "${LC_ALL:-${LC_CTYPE:-${LANG:-}}}" in
  *[Uu][Tt][Ff]-8* | *[Uu][Tt][Ff]8*) utf=1; OK='✓'; BAD='✗'; TODO='○'; RUN='▸'; FULL='█'; EMPTY='░'; RULE='━' ;;
  *) utf=0; OK='+'; BAD='x'; TODO='.'; RUN='>'; FULL='#'; EMPTY='-'; RULE='=' ;;
esac

# The steps are listed up front and each line is redrawn in place as the install moves along.
NSTEPS=6
cur=0; curtext=""; step_t0=0; job=""; tmp=""

# Redraw tracker line <step> with <glyph> and <text>; the cursor rests on the line below the list.
mark() {
  [ "$fun" = "1" ] || return 0
  up=$(($NSTEPS - $1 + 1))
  printf '\033[%sA\r\033[K  %s %s\033[%sB\r' "$up" "$2" "$3" "$up"
}
plan() {
  [ "$fun" = "1" ] || return 0
  printf '\033[?25l'
  for l in "Detect system" "Fetch checksums" "Download" "Verify" "Install" "Check PATH"; do
    printf '  %s%s %s%s\n' "$D" "$TODO" "$l" "$Z"
  done
}
step_start() { cur="$1"; curtext="$2"; step_t0="$(date +%s)"; mark "$1" "$U$RUN$Z" "$2"; }
step_done() {
  cur=0
  el=$(($(date +%s) - step_t0)); [ "$el" -gt 0 ] && t=" ${el}s" || t=""
  if [ "$fun" = "1" ]; then mark "$1" "$O$OK$Z" "$2${3:+  $D$3$t$Z}"
  else say "  $OK $2${3:+  ($3$t)}"; fi
}
# Spinner frame <tick> into $frame_c.
frame() {
  if [ "$utf" = "1" ]; then set -- "$1" ⠋ ⠙ ⠹ ⠸ ⠼ ⠴ ⠦ ⠧ ⠇ ⠏; n=10; else set -- "$1" '|' / - '\'; n=4; fi
  eval "frame_c=\${$(($1 % n + 2))}"
}
# Run "$@" in the background and spin on tracker line <step> until it ends; return its status.
spin() {
  n="$1"; text="$2"; shift 2
  if [ "$fun" = "0" ]; then "$@"; return; fi
  "$@" & job=$!
  i=0
  while kill -0 "$job" 2>/dev/null; do
    frame "$i"; mark "$n" "$U$frame_c$Z" "$text"; i=$((i + 1)); sleep 0.1
  done
  wait "$job"
}
# Tenths of a megabyte as "12.3".
mb() { t=$(($1 * 10 / 1048576)); printf '%s.%s' "$((t / 10))" "$((t % 10))"; }
# Download <url> to <dest>, drawing a progress bar on tracker line 3 from the size of the file so far.
download() {
  total="$(content_len "$1")"; total="${total:-0}"
  if [ "$fun" = "0" ]; then fetch "$1" "$2"; return; fi
  fetch "$1" "$2" & job=$!
  i=0; secs=0; t0="$(date +%s)"
  while kill -0 "$job" 2>/dev/null; do
    got=0; [ ! -f "$2" ] || got="$(wc -c < "$2")"; got=$((got + 0))
    [ $((i % 10)) -ne 0 ] || secs=$(($(date +%s) - t0))
    text="$3"
    if [ "$total" -gt 0 ]; then
      pct=$((got * 100 / total)); [ "$pct" -le 100 ] || pct=100
      fill=$((pct * 16 / 100)); bar=""; j=0
      while [ "$j" -lt 16 ]; do
        if [ "$j" -lt "$fill" ]; then bar="$bar$FULL"; else bar="$bar$EMPTY"; fi
        j=$((j + 1))
      done
      text="$text  $O$bar$Z $pct%  $(mb "$got")/$(mb "$total") MB"
    else
      text="$text  $(mb "$got") MB"
    fi
    if [ "$secs" -gt 0 ] && [ "$got" -gt 0 ]; then
      bps=$((got / secs)); text="$text  $(mb "$bps") MB/s"
      [ "$total" -le 0 ] || [ "$bps" -le 0 ] || text="$text  ETA $(((total - got) / bps))s"
    fi
    frame "$i"; mark 3 "$U$frame_c$Z" "$text"; i=$((i + 1)); sleep 0.1
  done
  wait "$job"
}
# Leave the terminal as it was, also on Ctrl-C: show the cursor, stop a running download, drop the temp dir.
cleanup() {
  [ "$fun" = "0" ] || printf '\033[?25h'
  [ -z "$job" ] || kill "$job" 2>/dev/null || true
  [ -z "$tmp" ] || rm -rf "$tmp"
}
err() {
  [ "$cur" = "0" ] || mark "$cur" "$O$BAD$Z" "$curtext"
  printf 'install: %s\n' "$*" >&2; exit 1
}

usage() {
  cat <<'USAGE'
Install Yukta: the server (yukta) and the client (yukta-cli) in one step.

Options (environment variable in brackets):
  --version <X.Y.Z>     install this version                   [YUKTA_VERSION]
  --install-dir <dir>   where to put the programs              [YUKTA_INSTALL_DIR]
                        default: /usr/local/bin as root, else ~/.local/bin
  --cli-only            install only yukta-cli
  --server-only         install only the server
  --check               say whether a newer release exists; install nothing (exit 1 if behind)
  --force               reinstall even when this version is already installed
  --agents <how>        connect AI agents to Yukta (MCP server + skills): ask (default, on a
                        terminal), auto, none, or a list such as claude,codex,cursor [YUKTA_AGENTS]
  --uninstall           remove yukta and yukta-cli and disconnect the agents they connected
  --no-animation        plain output: no colour, spinner or progress bar
  --quiet               print nothing but errors
  --help
USAGE
}

fetch() {
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL --retry 3 -o "$2" "$1"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$2" "$1"
  else
    err "curl or wget is required"
  fi
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1
  else err "sha256sum or shasum is required to verify the download"; fi
}

# Is <file> listed in SHA256SUMS?
listed() { awk -v f="$1" '$2 == f || $2 == "*" f { found = 1 } END { exit !found }' "$tmp/SHA256SUMS"; }

# Download <file> and check it against SHA256SUMS (tracker steps 3 and 4).
get_verified() {
  case "$1" in *.tar.gz) label="Download server + client" ;; *) label="Download client" ;; esac
  step_start 3 "$label"
  download "$BASE_URL/v$version/$1" "$tmp/$1" "$label" || err "download failed: $BASE_URL/v$version/$1"
  step_done 3 "$label" "$(mb "$(wc -c < "$tmp/$1")") MB"
  step_start 4 "Verify checksum"
  want="$(awk -v f="$1" '$2 == f || $2 == "*" f { print $1; exit }' "$tmp/SHA256SUMS")"
  [ -n "$want" ] || err "$1 is not listed in SHA256SUMS"
  [ "$(sha256_of "$tmp/$1")" = "$want" ] || err "checksum mismatch for $1; not installing"
  step_done 4 "Verify checksum" "sha256 of $1 matches"
}

# Verify the Sigstore signature of SHA256SUMS when cosign is installed; say what was checked.
verify_signature() {
  verified="checksum"
  if ! command -v cosign >/dev/null 2>&1; then
    sig_note="signature not checked (install cosign to check it)"; return
  fi
  if ! fetch_quiet "$BASE_URL/v$version/SHA256SUMS.sigstore.json" "$tmp/SHA256SUMS.sigstore.json"; then
    sig_note="signature not checked (this release has no SHA256SUMS.sigstore.json)"; return
  fi
  cosign verify-blob --bundle "$tmp/SHA256SUMS.sigstore.json" \
    --certificate-identity-regexp "^https://github.com/$REPO/" \
    --certificate-oidc-issuer https://token.actions.githubusercontent.com \
    "$tmp/SHA256SUMS" >/dev/null 2>&1 || err "signature of SHA256SUMS does not verify; not installing"
  verified="checksum, signature (cosign)"; sig_note=""
}

fetch_quiet() {
  if command -v curl >/dev/null 2>&1; then curl -fsSL --retry 3 -o "$2" "$1" 2>/dev/null
  else wget -q -O "$2" "$1" 2>/dev/null; fi
}

# Size of a download in bytes, or nothing when the server does not say.
content_len() {
  command -v curl >/dev/null 2>&1 || return 0
  curl -fsSLI "$1" 2>/dev/null | awk 'tolower($1) == "content-length:" { n = $2 + 0 } END { if (n) print n }'
}
size_mb() {
  bytes="$(content_len "$1")"
  [ -z "$bytes" ] || printf '%s MB' "$((bytes / 1048576))"
}

# Version of the yukta-cli in <dir>, or nothing. The server cannot report its version.
installed_version() {
  [ -x "$1/yukta-cli" ] || return 0
  "$1/yukta-cli" --version 2>/dev/null | grep -Eo '[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?' | head -n 1 || true
}

latest_version() {
  command -v curl >/dev/null 2>&1 || err "cannot look up the latest version without curl; pass --version"
  url="$(curl -fsSLI -o /dev/null -w '%{url_effective}' "https://github.com/$REPO/releases/latest")" ||
    err "could not look up the latest release; pass --version"
  case "$url" in
    */releases/tag/v*) printf '%s' "${url##*/tag/v}" ;;
    *) err "there is no published release yet; pass --version, or see the Quickstart for Docker" ;;
  esac
}

# Offer the agent integration (MCP server and skills) once the client is in place. It never fails the
# install: the programs are already there, and `yukta-cli agents install` can be run any time.
connect_agents() {
  cli="$dir/yukta-cli"
  [ "$do_cli" = "1" ] && [ -x "$cli" ] && [ "$agents" != none ] || return 0
  # An upgrade refreshes the skills of agents connected earlier; quiet, and nothing else changes.
  "$cli" agents install --refresh >/dev/null 2>&1 || true
  list="$agents"
  case "$agents" in
    ask | auto)
      list="$("$cli" agents list 2>/dev/null |
        awk 'NR > 1 && $(NF - 1) == "yes" && $1 != "generic" { printf "%s%s", sep, $1; sep = "," }')"
      if [ -z "$list" ]; then
        say ""; say "No AI agent found to connect (yukta-cli agents list shows what is looked for)."; return 0
      fi
      if [ "$agents" = ask ]; then
        # Ask only where someone can answer; otherwise stay out of their configuration.
        { [ -z "${CI:-}" ] && [ "$quiet" = "0" ] && [ -r "$TTY" ] && : <"$TTY"; } 2>/dev/null || {
          say ""; say "AI agents found: $list. Connect them with: yukta-cli agents install"; return 0
        }
        say ""
        say "Connect Yukta to your AI agents? Found: $list"
        printf '  [Y] connect them   [n] skip   [c] choose: '
        exec 3<"$TTY" # one descriptor for both questions, so a file as the answer source reads on
        read -r reply <&3 || reply=n
        case "$reply" in
          "" | [Yy]*) ;;
          [Cc]*) printf '  Agents (comma separated, or "all"): '; read -r list <&3 || list="" ;;
          *) exec 3<&-; say "Skipped. Later: yukta-cli agents install"; return 0 ;;
        esac
        exec 3<&-
        [ -n "$list" ] || return 0
      fi
      ;;
  esac
  say ""
  "$cli" agents install --agent "$list" ||
    say "Note: not every agent could be set up (see above). Retry later: yukta-cli agents install --agent $list"
}

main() {
  version="${YUKTA_VERSION:-}"
  dir="${YUKTA_INSTALL_DIR:-}"
  do_cli=1; do_server=1; uninstall=0; check=0; force=0; quiet=0
  agents="${YUKTA_AGENTS:-ask}"

  while [ $# -gt 0 ]; do
    case "$1" in
      --version) [ $# -ge 2 ] || err "--version needs a value"; version="$2"; shift ;;
      --install-dir) [ $# -ge 2 ] || err "--install-dir needs a value"; dir="$2"; shift ;;
      --cli-only) do_server=0 ;;
      --server-only) do_cli=0 ;;
      --check) check=1 ;;
      --force) force=1 ;;
      --agents) [ $# -ge 2 ] || err "--agents needs a value"; agents="$2"; shift ;;
      --uninstall) uninstall=1 ;;
      --no-animation) plain ;;
      --quiet) quiet=1; plain ;;
      -h | --help) usage; exit 0 ;;
      *) err "unknown option $1 (see --help)" ;;
    esac
    shift
  done
  # --quiet silences stdout (errors go to stderr); --check exists to print.
  [ "$quiet" = "0" ] || [ "$check" = "1" ] || exec >/dev/null
  [ "$do_cli$do_server" != "00" ] || err "--cli-only and --server-only cannot be combined"
  case "$agents" in
    "" | *[!a-z,]*) err "--agents takes ask, auto, none, all or a list like claude,codex (got '$agents')" ;;
  esac

  if [ -z "$dir" ]; then
    if [ "$(id -u)" = "0" ]; then dir=/usr/local/bin; else dir="${HOME:?HOME is not set}/.local/bin"; fi
  fi

  if [ "$uninstall" = "1" ]; then
    # Disconnect the agents first: the client that knows how is about to go.
    if [ -x "$dir/yukta-cli" ] && "$dir/yukta-cli" agents uninstall --all >/dev/null 2>&1; then
      say "disconnected the AI agents Yukta had set up"
    fi
    for f in yukta yukta-cli yukta.prev yukta-cli.prev; do
      if [ -f "$dir/$f" ]; then rm -f "$dir/$f" && say "removed $dir/$f"; fi
    done
    exit 0
  fi

  os="${YUKTA_OS:-$(uname -s)}"
  case "$os" in
    Linux | linux) os=linux ;;
    Darwin | darwin) os=darwin ;;
    MINGW* | MSYS* | CYGWIN*) err "on Windows use install.ps1 (PowerShell)" ;;
    *) err "unsupported operating system: $os" ;;
  esac
  arch="${YUKTA_ARCH:-$(uname -m)}"
  case "$arch" in
    x86_64 | amd64) arch=amd64 ;;
    aarch64 | arm64) arch=arm64 ;;
    *) err "unsupported processor: $arch" ;;
  esac
  # A shell running under Rosetta 2 reports x86_64 on Apple silicon: ask the hardware.
  if [ -z "${YUKTA_ARCH:-}" ] && [ "$os" = darwin ] && [ "$(sysctl -n hw.optional.arm64 2>/dev/null)" = 1 ]; then
    arch=arm64
  fi

  if [ -z "$version" ] && [ "$DEFAULT_VERSION" != "@VERSION@" ]; then version="$DEFAULT_VERSION"; fi
  if [ -z "$version" ]; then version="$(latest_version)"; fi
  version="${version#v}"
  # The version goes into URLs and file names, so it must be one: X.Y.Z or X.Y.Z-rc.1, nothing else
  # (no "/", "..", "?" or "#", no newline). release.sh checks the same pattern.
  case "$version" in
    *[!0-9A-Za-z.-]* | "") err "'$version' is not a version (expected X.Y.Z or X.Y.Z-rc.1)" ;;
  esac
  printf '%s\n' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$' ||
    err "'$version' is not a version (expected X.Y.Z or X.Y.Z-rc.1)"

  found="$(installed_version "$dir")"
  if [ "$check" = "1" ]; then
    say "installed: ${found:-none in $dir}"
    say "latest:    $version"
    [ "$found" != "$version" ] || exit 0
    say "A newer release is available; run this script again to upgrade."
    exit 1
  fi
  if [ "$found" = "$version" ] && [ "$force" = "0" ]; then
    say "Yukta $version is already installed in $dir (use --force to reinstall)."
    exit 0
  fi

  tmp="$(mktemp -d)"
  trap cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  t_start="$(date +%s)"

  say "${U}${B}Yukta installer${Z}"
  if [ -n "$found" ]; then say "  found:      $found in $dir"; elif [ -e "$dir/yukta" ]; then say "  found:      an unknown version in $dir"; fi
  size=""
  [ "$do_server" = "0" ] || size="$(size_mb "$BASE_URL/v$version/yukta-$version-$os-$arch.tar.gz")"
  say "  installing: $version  ($os/$arch${size:+, $size})  into $dir"
  say ""
  plan
  step_t0="$t_start"; step_done 1 "Detect system" "$os/$arch"

  step_start 2 "Fetch checksums"
  spin 2 "Fetch checksums" fetch "$BASE_URL/v$version/SHA256SUMS" "$tmp/SHA256SUMS" ||
    err "download failed: $BASE_URL/v$version/SHA256SUMS"
  verify_signature
  if [ -n "$sig_note" ]; then step_done 2 "Fetch checksums" "checksum only"; else step_done 2 "Fetch checksums" "signature verified (cosign)"; fi

  mkdir -p "$dir" 2>/dev/null || true
  [ -d "$dir" ] && [ -w "$dir" ] ||
    err "cannot write to $dir; choose another with --install-dir, or run as root"

  keep_prev() { if [ -f "$dir/$1" ]; then cp -p "$dir/$1" "$dir/$1.prev"; prev_kept=1; fi; }
  prev_kept=0
  # sarch: the processor the server is built for. The macOS server is built for Intel only; Apple
  # silicon runs it through Rosetta 2 and still gets its own client.
  sarch="$arch"
  if [ "$os-$arch" = darwin-arm64 ] && [ "$do_server" = "1" ] &&
    ! listed "yukta-$version-darwin-arm64.tar.gz" && listed "yukta-$version-darwin-amd64.tar.gz"; then
    say "Note: this release has no Apple silicon server; installing the Intel one (needs Rosetta 2:
  softwareupdate --install-rosetta)."
    sarch=amd64
  fi
  archive="yukta-$version-$os-$sarch.tar.gz"
  cli_asset="yukta-cli-$os-$arch"
  installed_server=0

  if listed "$archive"; then
    get_verified "$archive"
    step_start 5 "Install"
    tar -xzf "$tmp/$archive" -C "$tmp"
    src="$tmp/yukta-$version-$os-$sarch"
    # Install only regular files that the archive put in its own folder, never a link or a stray path.
    for f in yukta yukta-cli; do
      [ -f "$src/$f" ] && [ ! -L "$src/$f" ] || err "$archive does not contain a regular file $f; not installing"
    done
    [ "$do_server" = "1" ] && { keep_prev yukta; install -m 0755 "$src/yukta" "$dir/yukta"; installed_server=1; }
    cli_src="$src/yukta-cli"
    if [ "$do_cli" = "1" ] && [ "$sarch" != "$arch" ] && listed "$cli_asset"; then
      get_verified "$cli_asset"; cli_src="$tmp/$cli_asset"
    fi
    [ "$do_cli" = "1" ] && { keep_prev yukta-cli; install -m 0755 "$cli_src" "$dir/yukta-cli"; }
  else
    [ "$do_server" = "0" ] || [ "$do_cli" = "1" ] || err "this release has no native server for $os/$arch; use Docker"
    if [ "$do_cli" = "1" ]; then
      get_verified "$cli_asset"
      step_start 5 "Install"
      keep_prev yukta-cli
      install -m 0755 "$tmp/$cli_asset" "$dir/yukta-cli"
    fi
  fi
  step_done 5 "Install" "$dir"

  step_start 6 "Check PATH"
  case ":$PATH:" in
    *":$dir:"*) on_path=1; step_done 6 "Check PATH" "$dir is on your PATH" ;;
    *) on_path=0; step_done 6 "Check PATH" "$dir is not on your PATH" ;;
  esac

  say ""
  [ "$do_server" = "0" ] || [ "$installed_server" = "1" ] ||
    say "Note: this release has no native server for $os/$arch yet. Run the server with Docker instead:
  docker run --rm -p 127.0.0.1:9858:9858 -e YUKTA_API_KEYS_0_NAME=admin -e YUKTA_API_KEYS_0_KEY=change-me -e YUKTA_API_KEYS_0_ROLE=ADMIN ghcr.io/infenia/yukta:$version"
  say "  verified:   $verified${sig_note:+  [$sig_note]}"
  [ "$installed_server" = "0" ] || say "Installed: $dir/yukta (server) $version"
  [ "$do_cli" = "0" ] || say "Installed: $dir/yukta-cli (client) $version"
  if [ "$prev_kept" = "1" ]; then
    say "Previous version kept as *.prev; to roll back move them back, or run again with --version <old>."
  fi
  # NixOS has no /lib64/ld-linux*: the dynamically linked server cannot start there without nix-ld.
  # (yukta-cli is a static Go binary and runs anyway.)
  if [ "$installed_server" = "1" ] && [ "$os" = linux ] && grep -qs '^ID=nixos' /etc/os-release &&
    [ -z "${NIX_LD:-}" ]; then
    say ""
    say "Note: NixOS cannot run the yukta server as is (no dynamic loader at /lib64). Enable nix-ld"
    say "and re-login, or run the server with Docker. In configuration.nix:"
    say "  programs.nix-ld.enable = true;"
    say "  programs.nix-ld.libraries = with pkgs; [ zlib stdenv.cc.cc.lib ];"
  fi
  if [ "$installed_server" = "1" ] && command -v pgrep >/dev/null 2>&1 && pgrep -x yukta >/dev/null 2>&1; then
    say ""
    say "Note: a yukta server is running and keeps running the old version. Stop it, start the new one"
    say "and re-apply your sessions; sessions and runs kept in memory are lost on restart (set"
    say "YUKTA_BOOTSTRAP_DIR to reload them; see https://yukta.infenia.com/guides/operate/persist-sessions)."
  fi
  for f in yukta yukta-cli; do
    [ -f "$dir/$f" ] || continue
    other="$(command -v "$f" 2>/dev/null || true)"
    [ -z "$other" ] || [ "$other" = "$dir/$f" ] ||
      say "Warning: $other comes first on your PATH and hides the $f just installed."
  done

  if [ "$on_path" = "0" ]; then
    say ""; say "$dir is not on your PATH. Add it, for example:"; say "  export PATH=\"$dir:\$PATH\""
  fi

  connect_agents

  say ""
  if [ "$fun" = "1" ]; then
    printf '  '
    j=0
    while [ "$j" -lt 40 ]; do
      if [ "$j" -lt 20 ]; then printf '%s%s%s' "$O" "$RULE" "$Z"; else printf '%s%s%s' "$U" "$RULE" "$Z"; fi
      j=$((j + 1)); sleep 0.015
    done
    printf '\n'
  fi
  say "${O}${B}Done!${Z} Yukta $version is ready (took $(($(date +%s) - t_start))s)."
  nx() { printf '  %s%-34s%s %s%s%s\n' "$B" "$1" "$Z" "$D" "$2" "$Z"; }
  say ""
  say "${O}${B}Get started${Z}"
  if [ "$installed_server" = "1" ]; then
    nx "1  yukta" "start the server"
    nx "2  http://localhost:9858" "open the web UI: sessions, live runs, logs"
    nx "3  yukta.infenia.com/learn/your-first-workflow" "build and run one in 5 minutes"
    say ""
    say "${U}${B}Let your AI assistant drive it${Z}"
    [ "$do_cli" = "0" ] || say "  yukta-cli agents install        ${D}# connects Claude Code, Codex, Cursor, ... in one step${Z}"
    say "  claude mcp add --transport http yukta http://localhost:9858/mcp"
    say "  ${D}then ask: \"Create a Yukta workflow that runs my tests on every push\"${Z}"
  else
    nx "yukta-cli --url http://HOST:9858 session list" "point the client at a running server"
    nx "yukta.infenia.com/learn/your-first-workflow" "build and run a workflow in 5 minutes"
  fi
  say ""
  say "${U}${B}More${Z}"
  [ "$do_cli" = "0" ] || nx "yukta-cli session list" "manage sessions and runs from the terminal"
  nx "yukta.infenia.com/examples" "ready-made workflows: CI gates, pipelines, approvals"
}

main "$@"
