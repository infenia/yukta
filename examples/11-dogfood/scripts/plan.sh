#!/usr/bin/env bash
# plan.sh [--full] [--base REF]: a dry run of the scoped check. Prints what a check of the current changes would run,
# one line per stage, and runs nothing:
#
#   <stage> TAB <dir, relative to the repo root> TAB <shell command> TAB <free automatic fix, if any>
#                                                                          ('# ...' lines are comments)
#
# The changes are everything that differs from the merge base with REF (default origin/main), uncommitted and
# untracked files included. --full plans every stage unscoped. How each ecosystem is scoped:
#   gradle  the changed modules get every stage; the modules that depend on them (project(":x") edges, and
#           library-/spring-conventions -> test-support) get compile and tests only. build-logic/, gradle/, config/ or a root
#           build file changed: every stage, unscoped.
#   go      cli/ is one Go module and go test is cached: any change under cli/ plans the whole module, vuln only when
#           go.mod/go.sum changed.
#   ui      one pnpm package, no workspace: eslint and prettier on the changed files, vitest related with coverage
#           limited to those files, tsc always on the whole project. The commands call node_modules/.bin directly
#           (corepack's pnpm shim is not reliable). ponytail: when a pnpm workspace appears, pnpm --filter
#           "...[origin/main]" selects the packages instead.
#   repo    reuse on any change; actionlint, docs links when their files changed.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
root="$(git -C "$here" rev-parse --show-toplevel)"
cd "$root"

full=0 base=origin/main
while (($#)); do
  case "$1" in
    --full) full=1; shift ;;
    --base) base="${2:?--base needs a ref}"; shift 2 ;;
    *) echo "usage: plan.sh [--full] [--base REF]" >&2; exit 2 ;;
  esac
done

emit() { printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "${4:-}"; }
gr() { echo "./gradlew $* -q --console=plain"; }

# ---- the changed files ----
if ((full)); then
  files=()
else
  mb="$(git merge-base HEAD "$base")" || { echo "plan.sh: no merge base with $base" >&2; exit 1; }
  mapfile -t files < <( (git diff --name-only "$mb"; git ls-files --others --exclude-standard) | sort -u)
fi
echo "# $( ((full)) && echo full || echo "${#files[@]} changed files since $(git rev-parse --short "$mb")" )"
((full || ${#files[@]})) || { echo "# nothing changed, nothing to check"; exit 0; }

# ---- classify ----
declare -A mod=()
uifiles=() full_java=$full go=$full uiall=$full docs=$full wf=$full gomod=$full
any=$full buildfile=$full
for f in "${files[@]}"; do
  any=1
  [[ "$f" == *.md ]] && continue
  case "$f" in
    build-logic/* | gradle/* | config/* | settings.gradle.kts | build.gradle.kts | gradle.properties) full_java=1; buildfile=1; continue ;;
    cli/*) go=1; [[ "$f" == cli/go.mod || "$f" == cli/go.sum ]] && gomod=1; continue ;;
    ui/*)
      uifiles+=("${f#ui/}")
      case "${f#ui/}" in
        package.json | pnpm-lock.yaml | tsconfig*.json | vite.config.ts | vitest.config.ts | eslint.config.js | src/test/* ) uiall=1 ;;
      esac
      continue ;;
    docs/*) docs=1; continue ;;
    .github/workflows/*) wf=1; continue ;;
  esac
  d="$(dirname "$f")"
  while [[ "$d" != . && ! -f "$d/build.gradle.kts" ]]; do d="$(dirname "$d")"; done
  [[ "$d" == . ]] && continue
  mod["${d//\//:}"]=1
  [[ "$f" == */build.gradle.kts ]] && buildfile=1
done

# ---- gradle: changed modules, then the modules that depend on them ----
# edges "module dependency", from project(":x") in each module's build file; library-/spring-conventions add test-support
mapfile -t edges < <(git ls-files '*build.gradle.kts' | grep -vE '^(build-logic/|build.gradle.kts$|ui/|cli/)' | while read -r b; do
  m="$(dirname "$b")"; m="${m//\//:}"
  { grep -o 'project(":[^"]*")' "$b" || true; } | sed -E 's/project\(":(.*)"\)/\1/' | sed "s/^/$m /"
  # library-conventions applies spring-conventions, which adds testImplementation(project(":test-support"))
  if [[ "$m" != test-support ]] && grep -qE 'yukta\.(library|spring)-conventions' "$b"; then echo "$m test-support"; fi
done | sort -u)
declare -A dep=()
for m in "${!mod[@]}"; do dep[$m]=0; done
changed=("${!mod[@]}")
grew=1
while ((grew)); do
  grew=0
  for e in "${edges[@]}"; do
    read -r m d <<<"$e"
    if [[ -n "${mod[$d]:-}" || -n "${dep[$d]:-}" ]] && [[ -z "${mod[$m]:-}" && -z "${dep[$m]:-}" ]]; then dep[$m]=1; grew=1; fi
  done
done
dependents=(); for m in "${!dep[@]}"; do [[ -z "${mod[$m]:-}" ]] && dependents+=("$m"); done
mapfile -t changed < <(printf '%s\n' "${changed[@]:-}" | sort | grep .) || true
mapfile -t dependents < <(printf '%s\n' "${dependents[@]:-}" | sort | grep .) || true

# narrow_tests: ":m:test --tests a.b.FooTest ..." when every changed file is Java under a module's src/main|src/test, no diff line touches a
# public/protected member or a type declaration, and every changed main class has a <Class>Test; else nothing (the whole modules run).
narrow_tests() {
  local f m dir out="" t cls tf pkgpath diff
  ((${#files[@]})) || return 0
  for f in "${files[@]}"; do
    [[ "$f" == *.md ]] && continue
    [[ "$f" =~ ^(.+)/src/(main|test)/java/(.+)\.java$ ]] || return 0
  done
  diff="$( (git diff -U0 "$mb" -- "${files[@]}"; for f in $(git ls-files --others --exclude-standard); do echo "+++ $f"; sed 's/^/+/' "$f"; done) 2>/dev/null)"
  grep -Eq '^[+-][[:space:]]*(public|protected|abstract|default)[[:space:]]|^[+-].*[[:space:]](interface|record|enum|@interface)[[:space:]]' <<<"$diff" && return 0
  for m in "${changed[@]}"; do
    dir="${m//://}"; t=""
    for f in "${files[@]}"; do
      [[ "$f" == *.md ]] && continue
      [[ "$f" == "$dir"/src/* ]] || continue
      pkgpath="${f#"$dir"/src/*/java/}"; pkgpath="${pkgpath%.java}"
      if [[ "$f" == "$dir"/src/test/java/* ]]; then tf="$f"; cls="$pkgpath"; else tf="$dir/src/test/java/${pkgpath}Test.java"; cls="${pkgpath}Test"; fi
      [[ -f "$tf" ]] || return 0
      t+=" --tests ${cls//\//.}"
    done
    [[ -n "$t" ]] || return 0
    out+=" :$m:test$t"
  done
  echo "${out# }"
}

# per-module task list: scoped "<task>" -> ":m:<task>" for each module
scoped() { local t="$1"; shift; local out=() m x; for m in "$@"; do for x in $t; do out+=(":$m:$x"); done; done; echo "${out[*]}"; }
if ((full_java)); then
  echo "# gradle: every stage, unscoped$( ((full)) || echo " (build-logic, gradle/, config/ or a root build file changed)")"
  emit spotless . "$(gr spotlessCheck)" "$(gr spotlessApply)"; emit compile . "$(gr compileJava compileTestJava)"
  emit tests . "$(gr test jacocoTestReport)"; emit coverage . "$(gr jacocoTestCoverageVerification verifyCoverageData)"
  emit style . "$(gr checkstyleMain checkstyleTest pmdMain pmdTest)"; emit spotbugs . "$(gr spotbugsMain spotbugsTest)"
  emit licenses . "$(gr licensee)"; emit security . "$(gr opengrep trivy)"
elif ((${#changed[@]})); then
  # Tier 1: only Java files changed in the modules, none of the changed lines touches a public or protected member or a type declaration,
  # and every changed class has its <Class>Test: dependants cannot be affected, only those test classes run (no coverage stage: it
  # would judge a partial run). ponytail: a textual check of the diff, not an API diff; `yk check --full` is the safety net before a PR.
  narrow=""
  if ((! buildfile)); then
    narrow="$(narrow_tests)"
  fi
  if [[ -n "$narrow" ]]; then
    echo "# gradle: changed ${changed[*]}; only the tests of the changed classes (no public API touched): $narrow"
    dependents=()
  else
    echo "# gradle: changed ${changed[*]}; dependents (compile and tests only): ${dependents[*]:-none}"
  fi
  both=("${changed[@]}" "${dependents[@]}")
  echo "# modules: ${changed[*]}"
  emit spotless . "$(gr $(scoped spotlessCheck "${changed[@]}"))" "$(gr $(scoped spotlessApply "${changed[@]}"))"
  emit compile . "$(gr $(scoped 'compileJava compileTestJava' "${both[@]}"))"
  if [[ -n "$narrow" ]]; then
    emit tests . "$(gr $narrow)"
  else
    emit tests . "$(gr $(scoped 'test jacocoTestReport' "${both[@]}"))"
    emit coverage . "$(gr $(scoped 'jacocoTestCoverageVerification verifyCoverageData' "${changed[@]}"))"
  fi
  emit style . "$(gr $(scoped 'checkstyleMain checkstyleTest pmdMain pmdTest' "${changed[@]}"))"
  emit spotbugs . "$(gr $(scoped 'spotbugsMain spotbugsTest' "${changed[@]}"))"
  ((buildfile)) && emit licenses . "$(gr licensee)"
  emit security . "$(gr opengrep trivy)"
fi

# ---- go ----
if ((go)); then
  echo "# go: cli/ is one module, the whole module (go test is cached)"
  emit cli.fmt-check cli "make --no-print-directory fmt-check" "make --no-print-directory fmt"
  emit cli.mod-verify cli "make --no-print-directory mod-verify" "make --no-print-directory mod-tidy"
  for t in vet lint sec cover-check; do emit "cli.$t" cli "make --no-print-directory $t"; done
  ((gomod)) && emit cli.vuln cli "make --no-print-directory vuln"
fi

# ---- ui ----
if ((${#uifiles[@]} || uiall)); then
  B=node_modules/.bin
  existing=(); unit=(); src=(); lintable=(); pretty=()
  for f in "${uifiles[@]:-}"; do
    [[ -n "$f" && -e "ui/$f" ]] || continue
    existing+=("$f")
    [[ "$f" == src/* ]] && unit+=("$f")
    [[ "$f" =~ \.(ts|tsx|js|jsx|mjs|cjs)$ ]] && lintable+=("$f")
    [[ "$f" =~ \.(ts|tsx|js|jsx|mjs|cjs|json|css|html)$ ]] && pretty+=("$f")
    [[ "$f" == src/* && "$f" =~ \.(ts|tsx)$ && ! "$f" =~ (\.test\.|__tests__/|\.d\.ts$|^src/test/) ]] && src+=("$f")
  done
  q() { printf '%q ' "$@"; }
  echo "# ui: ${#existing[@]} files$( ((uiall)) && echo ", a config or the test setup changed: whole package")"
  emit ui.tsc ui "$B/tsc --noEmit && $B/tsc --noEmit -p tsconfig.vitest.json"
  if ((uiall)); then
    emit ui.eslint ui "$B/eslint --cache --cache-location node_modules/.cache/eslint ." "$B/eslint --fix --cache --cache-location node_modules/.cache/eslint ."
    emit ui.vitest ui "$B/vitest run --coverage"
  else
    ((${#lintable[@]})) && emit ui.eslint ui "$B/eslint --cache --cache-location node_modules/.cache/eslint $(q "${lintable[@]}")" "$B/eslint --fix --cache --cache-location node_modules/.cache/eslint $(q "${lintable[@]}")"
    # vitest only knows src/; the Playwright specs in e2e/ run in the full gate
    if ((${#unit[@]})); then
      cov=""; ((${#src[@]})) && cov=" --coverage $(printf -- '--coverage.include=%q ' "${src[@]}")"
      emit ui.vitest ui "$B/vitest related $(q "${unit[@]}")--run$cov"
    fi
  fi
  # prettier is not a gate for the files already in the repo: only the changed ones
  ((${#pretty[@]})) && emit ui.prettier ui "$B/prettier --check --ignore-unknown $(q "${pretty[@]}")" "$B/prettier --write --ignore-unknown $(q "${pretty[@]}")"
  emit ui.headers ui "node scripts/validate-headers.js"
  ((full)) && emit ui.build ui "$B/vite build && node scripts/check-bundle-size.js"
fi

# ---- repo ----
extra() { # NAME: the first installed variant of heal.json's extra NAME as a shell line, or a comment
  local t
  for t in $(jq -r --arg n "$1" '.extra[$n].variants[].tool' "$here/heal.json"); do
    command -v "$t" >/dev/null 2>&1 || continue
    emit "$1" . "$(jq -r --arg n "$1" --arg t "$t" '.extra[$n].variants[] | select(.tool == $t) | .command | @sh' "$here/heal.json")" \
      "$(jq -r --arg n "$1" '.extra[$n].auto // empty' "$here/heal.json" | sed "s#@HERE@#$here#")"
    return
  done
  echo "# $1 skipped: none of $(jq -r --arg n "$1" '[.extra[$n].variants[].tool] | join(", ")' "$here/heal.json") is installed"
}
((any)) && extra reuse
((wf)) && extra actionlint
((docs)) && extra docs
exit 0
