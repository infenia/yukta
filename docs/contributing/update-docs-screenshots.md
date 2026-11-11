# Update the documentation screenshots

The web UI is alpha and changes often, so the pictures in the docs are generated, never edited by hand. One command redraws all of them from stubbed API data; no backend runs.

## What gets generated

Each shot is a light and a dark PNG at 1440x900 (the Control page is full height), written to `docs/images/ui/<name>.light.png` and `<name>.dark.png`. The docs show them with the `UiShot` snippet (`docs/snippets/ui-shot.jsx`), which takes only the shot `name`. When the UI changes you regenerate the images and no docs page changes.

| Shot | Route | Shows |
|---|---|---|
| `sessions-list` | `/sessions` | five sessions with tags and last run |
| `workflow-canvas` | `/sessions/release-pipeline/workflow/release` | the running release: VALIDATE and BRANCH steps, an LLM_CALL step, an ASK_INPUT step waiting |
| `workflow-editor` | `.../release/edit` | the editor with the `approve` node open in the inspector |
| `plugin-catalogue` | `/plugins` | plugins by category, CLASSIFY open |
| `supervisors-catalogue` | `/supervisors?tab=decision-makers` | the kinds of supervisor |
| `supervisor-details` | `/supervisors?supervisor=release-guard` | one supervisor and what it owns |
| `supervisor-chain` | same, Hierarchy tab | release-guard reports to release-lead |
| `control-dashboard` | `/control` | Mission Control with metrics, workers, scheduler |
| `inputs-panel` | the release run, Inputs region | an approval waiting, with its approval chain |

## Refresh

From the repository root (Playwright and the Vite dev server start themselves; nothing else needs to run):

```bash
yk tool -C ui pnpm docs:screenshots                        # all shots
yk tool -C ui pnpm docs:screenshots -- --only supervisor-chain   # one (comma-separate several)
yk tool -C ui pnpm docs:screenshots:check                  # list stale shots, write nothing
yk tool -C ui pnpm docs:screenshots:check -- --strict      # same, exit non-zero if any is stale
yk tool -C ui pnpm docs:screenshots:types                  # typecheck the stubs against the UI types
```

`--check` compares a fresh render with the committed image and calls a shot stale when more than 0.2 percent of pixels differ (`DOCS_SHOT_TOLERANCE=0.01` changes that). Look at the result with `git diff --stat docs/images/ui` and open the changed images before committing. Fonts and anti-aliasing differ a little between machines, so regenerate on the machine you normally use and expect small byte differences in unchanged shots; commit only the shots whose content changed.

A failure names the shot (`Shot "supervisor-chain" (dark) failed: ...`) and says what it waited for. `--only <name>` reruns just that one.

## When the UI changes

1. Run `docs:screenshots:types`. A UI type change that the stubs no longer match is a compile error in `ui/e2e/docs-screenshots/stubs/data.ts`; fix the data.
2. Run `docs:screenshots`. A renamed button, tab or heading makes a shot time out; the message names the shot.
3. Fix the shot in `ui/e2e/docs-screenshots/shots.ts`: the `ready` locator (what to wait for) or the `actions` (open a panel, select a node). Use role and visible text, for example `getByRole('tab', { name: 'Hierarchy' })`, never CSS paths.
4. A page that now asks an API call nobody stubbed shows an empty panel, and the runner prints `API calls without a stub: GET /api/...`. Add it to `stubs/routes.ts`: `base` holds the empty defaults, the other installers (`releaseRun`, `supervisors`, `control`, ...) hold the data of one area.
5. Open the new images and check both themes.

## Add a shot

Add one entry to `SHOTS` in `ui/e2e/docs-screenshots/shots.ts`:

```ts
{
  name: 'runs-search',                       // file stem and UiShot name
  shows: 'Search across runs.',
  route: '/runs',
  stubs: [stub.sessions],                   // installers; base always runs first
  ready: (page) => page.getByRole('heading', { name: 'Runs' }),
  actions: async (page) => { /* optional */ },
  fullPage: false,                          // optional: whole scrolling page
  region: undefined,                        // optional: screenshot one element only
  pages: ['/interfaces/web-ui'],            // docs pages that embed it
}
```

Then run `docs:screenshots -- --only runs-search`, and embed it with `<UiShot name="runs-search" alt="..." />`. The `pages` field is a note for the next maintainer (what to re-read when the shot changes); nothing enforces it.

## How it is built

- `ui/e2e/docs-screenshots/shots.ts`: the manifest. `capture.spec.ts` runs each shot in light and dark; `run.mjs` is the entry point that turns `--only`, `--check` and `--strict` into environment variables.
- `ui/e2e/docs-screenshots/stubs/data.ts`: one invented release pipeline (session `release-pipeline`, the supervisors `release-lead` (http), `release-guard` (rules, child of release-lead) and `triage-flow` (workflow), a pending approval, Control metrics). Typed against `ui/src` types.
- `ui/e2e/docs-screenshots/stubs/routes.ts`: `page.route()` installers against the Vite dev server (`:5173`, which proxies `/api` to a server that is never contacted).
- `ui/playwright.docs.config.ts`: a separate config. The normal `playwright test` has `testDir ./e2e/tests` and never picks these files up.
- Repeatability: fixed 1440x900 viewport, UTC, `en-US`, a fixed clock (`2026-07-26T14:30:00Z`), reduced motion, animations and the caret disabled, and the theme stored the way the theme toggle stores it.
