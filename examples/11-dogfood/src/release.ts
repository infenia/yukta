// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Download and verify a published Yukta server release (default: the latest, pre-releases included) and print the path of its native executable.
// Used by `yk up --release [tag]`, which runs it. Downloads the native archive for this OS/arch with gh, verifies the signature of the release's
// SHA256SUMS (cosign) and the archive against it, and caches it in $DEV_HOME/releases/<tag>.
//
// Usage: release [--version <tag>]     ($YUKTA_SERVER_VERSION also pins it)
// The signature must come from the release workflow or from the maintainer (the alpha.2 release was signed by hand). YUKTA_SKIP_VERIFY=1 skips the
// signature check (the checksum is still checked).
import { createHash } from 'node:crypto'
import { DEV_HOME, die, join, mkdirp, need, osArch, readFileSync, rmSync, run, isFile } from './lib.ts'

export function main(argv: string[]): number {
  let version = process.env.YUKTA_SERVER_VERSION ?? ''
  if (argv[0] === '--version') { version = argv[1] ?? die('--version needs a tag'); }
  need('gh'); need('tar')
  if (!process.env.YUKTA_SKIP_VERIFY) need('cosign') // the binary runs next to this checkout's credentials
  let tag = version || run(['gh', 'release', 'list', '-R', 'infenia/yukta', '--exclude-drafts', '--limit', '1', '--json', 'tagName', '-q', '.[0].tagName']).stdout.trim()
  if (!tag) die('no published release found; use the main build (yk up) or pin --version')
  tag = `v${tag.replace(/^v/, '')}`
  const ver = tag.slice(1)
  const archive = `yukta-${ver}-${osArch()}.tar.gz`
  const cache = join(DEV_HOME, 'releases', tag)
  const bin = join(cache, `yukta-${ver}-${osArch()}`, 'yukta')
  if (!isFile(bin)) {
    rmSync(cache, { recursive: true, force: true })
    mkdirp(cache)
    const d = run(['gh', 'release', 'download', tag, '-R', 'infenia/yukta', '-D', cache, '-p', 'SHA256SUMS', '-p', 'SHA256SUMS.sigstore.json', '-p', archive])
    process.stderr.write(d.stdout + d.stderr)
    if (d.code) { rmSync(cache, { recursive: true, force: true }); die(`could not download ${archive} from release ${tag} (no native server for ${osArch()}?)`) }
    if (!process.env.YUKTA_SKIP_VERIFY) {
      const v = run(['cosign', 'verify-blob', '--bundle', 'SHA256SUMS.sigstore.json', 'SHA256SUMS',
        '--certificate-identity-regexp', '^(https://github\\.com/infenia/yukta/\\.github/workflows/release\\.yml@refs/tags/v.*|arun@infenia\\.com)$',
        '--certificate-oidc-issuer-regexp', '^(https://token\\.actions\\.githubusercontent\\.com|https://github\\.com/login/oauth)$'], { cwd: cache })
      if (v.code) { rmSync(cache, { recursive: true, force: true }); die(`the signature of SHA256SUMS in release ${tag} does not verify; not using it`) }
    }
    const want = readFileSync(join(cache, 'SHA256SUMS'), 'utf8').split('\n').find(l => l.includes(` ${archive}`))?.split(/\s+/)[0]
    const got = createHash('sha256').update(readFileSync(join(cache, archive))).digest('hex')
    if (!want || want !== got) { rmSync(cache, { recursive: true, force: true }); die(`checksum mismatch for ${archive}; not using it`) }
    run(['tar', '-xzf', join(cache, archive), '-C', cache])
  }
  console.log(bin)
  return 0
}

if (import.meta.main) process.exit(main(process.argv.slice(2)))
