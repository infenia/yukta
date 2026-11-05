// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// plan [--full] [--base REF]: a dry run of the scoped check. Prints what a check of the current changes would run,
// one line per stage, and runs nothing:
//
//   <stage> TAB <dir, relative to the repo root> TAB <shell command> TAB <free automatic fix, if any>
//                                                                          ('# ...' lines are comments)
//
// The changes are everything that differs from the merge base with REF (default origin/main), uncommitted and
// untracked files included. --full plans every stage unscoped. How each ecosystem is scoped:
//   gradle  the changed modules get every stage; the modules that depend on them (project(":x") edges, and
//           library-/spring-conventions -> test-support) get compile and tests only. build-logic/, gradle/, config/ or a root
//           build file changed: every stage, unscoped.
//   go      cli/ is one Go module and its tests are cached: any change under cli/ plans the whole module, vuln only when
//           go.mod/go.sum changed.
//   ui      one pnpm package, no workspace: eslint and prettier on the changed files, vitest related with coverage
//           limited to those files, tsc always on the whole project. The commands call node_modules/.bin directly
//           (corepack's pnpm shim is not reliable). ponytail: when a pnpm workspace appears, pnpm --filter
//           "...[origin/main]" selects the packages instead.
//   repo    reuse on any change; actionlint, docs links when their files changed.
import { HERE, REPO_ROOT, dirname, existsSync, git, isFile, join, onPath, readFileSync, readJson, shq, sq } from './lib.ts'

const GRADLE_ROOT = /^(build-logic\/|gradle\/|config\/|settings\.gradle\.kts$|build\.gradle\.kts$|gradle\.properties$)/
const lines = (s: string): string[] => (s.length ? s.replace(/\n$/, '').split('\n') : [])

export function plan(full: boolean, base: string, root = REPO_ROOT): { text: string; code: number } {
  const out: string[] = []
  const emit = (stage: string, dir: string, cmd: string, auto = ''): void => void out.push([stage, dir, cmd, auto].join('\t'))
  const gr = (...a: string[]): string => `./gradlew ${a.join(' ')} -q --console=plain`
  const g = (args: string[]): string => git(args, root).stdout

  // ---- the changed files
  let files: string[] = []
  let mb = ''
  if (!full) {
    const m = git(['merge-base', 'HEAD', base], root)
    if (m.code) return { text: `plan: no merge base with ${base}\n`, code: 1 }
    mb = m.stdout.trim()
    files = [...new Set([...lines(g(['diff', '--name-only', mb])), ...lines(g(['ls-files', '--others', '--exclude-standard']))])].sort()
  }
  out.push(`# ${full ? 'full' : `${files.length} changed files since ${g(['rev-parse', '--short', mb]).trim()}`}`)
  if (!full && !files.length) {
    out.push('# nothing changed, nothing to check')
    return { text: out.join('\n') + '\n', code: 0 }
  }

  // ---- classify
  const mod = new Set<string>()
  const uifiles: string[] = []
  let fullJava = full,
    go = full,
    uiall = full,
    docs = full,
    wf = full,
    gomod = full,
    any = full,
    buildfile = full,
    dogfood = full
  for (const f of files) {
    any = true
    if (f.startsWith('examples/dogfood/')) dogfood = true
    if (f.endsWith('.md')) continue
    if (GRADLE_ROOT.test(f)) {
      fullJava = true
      buildfile = true
      continue
    }
    if (f.startsWith('cli/')) {
      go = true
      if (f === 'cli/go.mod' || f === 'cli/go.sum') gomod = true
      continue
    }
    if (f.startsWith('ui/')) {
      const u = f.slice(3)
      uifiles.push(u)
      if (/^(package\.json|pnpm-lock\.yaml|tsconfig.*\.json|vite\.config\.ts|vitest\.config\.ts|eslint\.config\.js|src\/test\/.*)$/.test(u))
        uiall = true
      continue
    }
    if (f.startsWith('docs/')) {
      docs = true
      continue
    }
    if (f.startsWith('.github/workflows/')) {
      wf = true
      continue
    }
    let d = dirname(f)
    while (d !== '.' && !isFile(join(root, d, 'build.gradle.kts'))) d = dirname(d)
    if (d === '.') continue
    mod.add(d.replaceAll('/', ':'))
    if (f.endsWith('/build.gradle.kts')) buildfile = true
  }

  // ---- gradle: changed modules, then the modules that depend on them
  // edges "module dependency". Same rule as .github/scripts/ci-tasks.sh (plan-vs-ci.test.sh keeps them equal): any quoted module path in a module's
  // build file that settings.gradle.kts includes, not only project(":x"). library-/spring-conventions add test-support.
  const settings = isFile(join(root, 'settings.gradle.kts')) ? readFileSync(join(root, 'settings.gradle.kts'), 'utf8') : ''
  const included = new Set([...settings.matchAll(/^include\("(.*)"\)/gm)].map((m) => m[1]!))
  const edgeSet = new Set<string>()
  for (const b of lines(g(['ls-files', '*build.gradle.kts'])).filter((b) => !/^(build-logic\/|build\.gradle\.kts$|ui\/|cli\/)/.test(b))) {
    const m = dirname(b).replaceAll('/', ':')
    const body = readFileSync(join(root, b), 'utf8')
    for (const t of new Set([...body.matchAll(/":[A-Za-z0-9_:-]+"/g)].map((x) => x[0].replaceAll('"', '').replace(/^:/, ''))))
      if (t !== m && included.has(t)) edgeSet.add(`${m} ${t}`)
    // library-conventions applies spring-conventions, which adds testImplementation(project(":test-support"))
    if (m !== 'test-support' && /yukta\.(library|spring)-conventions/.test(body)) edgeSet.add(`${m} test-support`)
  }
  const edges = [...edgeSet].sort().map((e) => e.split(' ') as [string, string])
  const dep = new Set<string>()
  for (let grew = true; grew;) {
    grew = false
    for (const [m, d] of edges)
      if ((mod.has(d) || dep.has(d)) && !mod.has(m) && !dep.has(m)) {
        dep.add(m)
        grew = true
      }
  }
  const changed = [...mod].sort()
  let dependents = [...dep].filter((m) => !mod.has(m)).sort()
  const scoped = (tasks: string, ms: string[]): string[] => ms.flatMap((m) => tasks.split(' ').map((x) => `:${m}:${x}`))

  /** ":m:test --tests a.b.FooTest ..." when every changed file is Java under a module's src/main|src/test, no diff line touches a public/protected
   * member or a type declaration, and every changed main class has a <Class>Test; else nothing (the whole modules run). */
  const narrowTests = (): string => {
    if (!files.length) return ''
    for (const f of files) if (!f.endsWith('.md') && !/^(.+)\/src\/(main|test)\/java\/(.+)\.java$/.test(f)) return ''
    const tracked = lines(g(['diff', '-U0', mb, '--', ...files]))
    const untracked = lines(g(['ls-files', '--others', '--exclude-standard'])).flatMap((f) => [
      '+++ ' + f,
      ...readFileSync(join(root, f), 'utf8')
        .split('\n')
        .map((l) => '+' + l),
    ])
    const diff = [...tracked, ...untracked].join('\n')
    if (/^[+-][ \t]*(public|protected|abstract|default)[ \t]|^[+-].*[ \t](interface|record|enum|@interface)[ \t]/m.test(diff)) return ''
    const outs: string[] = []
    for (const m of changed) {
      const dir = m.replaceAll(':', '/')
      let t = ''
      for (const f of files) {
        if (f.endsWith('.md') || !f.startsWith(`${dir}/src/`)) continue
        const pkgpath = f.replace(new RegExp(`^${dir.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}/src/[^/]*/java/`), '').replace(/\.java$/, '')
        const isTest = f.startsWith(`${dir}/src/test/java/`)
        const tf = isTest ? f : `${dir}/src/test/java/${pkgpath}Test.java`
        const cls = isTest ? pkgpath : `${pkgpath}Test`
        if (!isFile(join(root, tf))) return ''
        t += ` --tests ${cls.replaceAll('/', '.')}`
      }
      if (!t) return ''
      outs.push(`:${m}:test${t}`)
    }
    return outs.join(' ')
  }

  if (fullJava) {
    out.push(`# gradle: every stage, unscoped${full ? '' : ' (build-logic, gradle/, config/ or a root build file changed)'}`)
    emit('spotless', '.', gr('spotlessCheck'), gr('spotlessApply'))
    emit('compile', '.', gr('compileJava', 'compileTestJava'))
    emit('tests', '.', gr('test', 'jacocoTestReport'))
    emit('coverage', '.', gr('jacocoTestCoverageVerification', 'verifyCoverageData'))
    emit('style', '.', gr('checkstyleMain', 'checkstyleTest', 'pmdMain', 'pmdTest'))
    emit('spotbugs', '.', gr('spotbugsMain', 'spotbugsTest'))
    emit('licenses', '.', gr('licensee'))
    emit('security', '.', gr('opengrep', 'trivy'))
  } else if (changed.length) {
    // Tier 1: only Java files changed in the modules, none of the changed lines touches a public or protected member or a type declaration, and every
    // changed class has its <Class>Test: dependants cannot be affected, only those test classes run (no coverage stage: it would judge a partial run).
    // ponytail: a textual check of the diff, not an API diff; `yk check --full` is the safety net before a PR.
    const narrow = buildfile ? '' : narrowTests()
    if (narrow) {
      out.push(`# gradle: changed ${changed.join(' ')}; only the tests of the changed classes (no public API touched): ${narrow}`)
      dependents = []
    } else out.push(`# gradle: changed ${changed.join(' ')}; dependents (compile and tests only): ${dependents.join(' ') || 'none'}`)
    const both = [...changed, ...dependents]
    out.push(`# modules: ${changed.join(' ')}`)
    emit('spotless', '.', gr(...scoped('spotlessCheck', changed)), gr(...scoped('spotlessApply', changed)))
    emit('compile', '.', gr(...scoped('compileJava compileTestJava', both)))
    if (narrow) emit('tests', '.', gr(narrow))
    else {
      emit('tests', '.', gr(...scoped('test jacocoTestReport', both)))
      emit('coverage', '.', gr(...scoped('jacocoTestCoverageVerification verifyCoverageData', changed)))
    }
    emit('style', '.', gr(...scoped('checkstyleMain checkstyleTest pmdMain pmdTest', changed)))
    emit('spotbugs', '.', gr(...scoped('spotbugsMain spotbugsTest', changed)))
    if (buildfile) emit('licenses', '.', gr('licensee'))
    emit('security', '.', gr('opengrep', 'trivy'))
  }

  // ---- go
  if (go) {
    out.push('# go: cli/ is one module, the whole module (its tests are cached)')
    emit('cli.fmt-check', 'cli', 'make --no-print-directory fmt-check', 'make --no-print-directory fmt')
    emit('cli.mod-verify', 'cli', 'make --no-print-directory mod-verify', 'make --no-print-directory mod-tidy')
    for (const t of ['vet', 'lint', 'sec', 'cover-check']) emit(`cli.${t}`, 'cli', `make --no-print-directory ${t}`)
    if (gomod) emit('cli.vuln', 'cli', 'make --no-print-directory vuln')
  }

  // ---- ui
  if (uifiles.length || uiall) {
    const B = 'node_modules/.bin'
    const existing: string[] = [],
      unit: string[] = [],
      src: string[] = [],
      lintable: string[] = [],
      pretty: string[] = []
    for (const f of uifiles) {
      if (!f || !existsSync(join(root, 'ui', f))) continue
      existing.push(f)
      if (f.startsWith('src/')) unit.push(f)
      if (/\.(ts|tsx|js|jsx|mjs|cjs)$/.test(f)) lintable.push(f)
      if (/\.(ts|tsx|js|jsx|mjs|cjs|json|css|html)$/.test(f)) pretty.push(f)
      if (f.startsWith('src/') && /\.(ts|tsx)$/.test(f) && !/(\.test\.|__tests__\/|\.d\.ts$|^src\/test\/)/.test(f)) src.push(f)
    }
    const q = (a: string[]): string => a.map((x) => shq(x) + ' ').join('')
    out.push(`# ui: ${existing.length} files${uiall ? ', a config or the test setup changed: whole package' : ''}`)
    emit('ui.tsc', 'ui', `${B}/tsc --noEmit && ${B}/tsc --noEmit -p tsconfig.vitest.json`)
    if (uiall) {
      emit(
        'ui.eslint',
        'ui',
        `${B}/eslint --cache --cache-location node_modules/.cache/eslint .`,
        `${B}/eslint --fix --cache --cache-location node_modules/.cache/eslint .`,
      )
      emit('ui.vitest', 'ui', `${B}/vitest run --coverage`)
    } else {
      if (lintable.length)
        emit(
          'ui.eslint',
          'ui',
          `${B}/eslint --cache --cache-location node_modules/.cache/eslint ${q(lintable)}`,
          `${B}/eslint --fix --cache --cache-location node_modules/.cache/eslint ${q(lintable)}`,
        )
      // vitest only knows src/; the Playwright specs in e2e/ run in the full gate
      if (unit.length) {
        const cov = src.length ? ` --coverage ${src.map((s) => `--coverage.include=${shq(s)} `).join('')}` : ''
        emit('ui.vitest', 'ui', `${B}/vitest related ${q(unit)}--run${cov}`)
      }
    }
    // prettier is not a gate for the files already in the repo: only the changed ones
    if (pretty.length)
      emit(
        'ui.prettier',
        'ui',
        `${B}/prettier --check --ignore-unknown ${q(pretty)}`,
        `${B}/prettier --write --ignore-unknown ${q(pretty)}`,
      )
    emit('ui.headers', 'ui', 'node scripts/validate-headers.js')
    if (full) emit('ui.build', 'ui', `${B}/vite build && node scripts/check-bundle-size.js`)
  }

  // ---- dogfood: bun fmt (bunfig.toml [fmt], .prettierignore) over the whole folder; it is fast, and bun fmt is the free fix
  if (dogfood) {
    emit('dogfood.fmt', 'examples/dogfood', 'bun fmt --check', 'bun fmt')
    // bun check type checks like TypeScript 7 (tsc 7, all cores), whatever `typescript` is installed; one package each
    emit('dogfood.check', 'examples/dogfood', 'bun check && bun check -p channel')
  }

  // ---- repo
  const heal = readJson(join(HERE, 'heal.json'))
  const extra = (name: string): void => {
    // the first installed variant of heal.json's extra NAME as a shell line, or a comment
    const e = heal.extra[name]
    for (const v of e.variants) {
      if (!onPath(v.tool)) continue
      emit(name, '.', (v.command as string[]).map(sq).join(' '), String(e.auto ?? '').replace('@HERE@', HERE))
      return
    }
    out.push(`# ${name} skipped: none of ${e.variants.map((v: { tool: string }) => v.tool).join(', ')} is installed`)
  }
  if (any) extra('reuse')
  if (wf) extra('actionlint')
  if (docs) extra('docs')
  return { text: out.join('\n') + '\n', code: 0 }
}

export function main(argv: string[]): number {
  let full = false
  let base = 'origin/main'
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--full') full = true
    else if (argv[i] === '--base') {
      base = argv[++i] ?? ''
      if (!base) {
        console.error('--base needs a ref')
        return 2
      }
    } else {
      console.error('usage: plan [--full] [--base REF]')
      return 2
    }
  }
  const r = plan(full, base)
  process.stdout.write(r.text)
  return r.code
}

if (import.meta.main) process.exit(main(process.argv.slice(2)))
