// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/*
 * Regenerates (or, with DOCS_SHOT_CHECK=1, only compares) the documentation screenshots.
 * Not part of `playwright test`: run it through ui/playwright.docs.config.ts, i.e.
 * `pnpm docs:screenshots [--only <name>] [--check [--strict]]` (see run.mjs).
 */
import { test, expect, type Page } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { SHOTS } from './shots';
import { base, unmocked } from './stubs/routes';
import { NOW } from './stubs/data';

const OUT_DIR = resolve(
  dirname(fileURLToPath(import.meta.url)),
  '../../../docs/images/ui'
);
const ONLY = (process.env['DOCS_SHOT_ONLY'] ?? '')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean);
const CHECK = process.env['DOCS_SHOT_CHECK'] === '1';
/** Share of pixels that may differ before a committed image counts as stale. */
const TOLERANCE = Number(process.env['DOCS_SHOT_TOLERANCE'] ?? '0.002');
const THEMES = ['light', 'dark'] as const;

const unknown = ONLY.filter((n) => !SHOTS.some((s) => s.name === n));
if (unknown.length > 0) {
  throw new Error(
    `Unknown shot(s): ${unknown.join(', ')}. Known: ${SHOTS.map((s) => s.name).join(', ')}`
  );
}

/** Share of pixels whose colour differs by more than a few levels (decoded in the browser). */
async function diffShare(page: Page, a: Buffer, b: Buffer): Promise<number> {
  const url = (buf: Buffer) =>
    `data:image/png;base64,${buf.toString('base64')}`;
  return page.evaluate(
    async ([ua, ub]) => {
      const load = (src: string) =>
        new Promise<ImageData>((done, fail) => {
          const img = new Image();
          img.onload = () => {
            const c = document.createElement('canvas');
            c.width = img.width;
            c.height = img.height;
            const ctx = c.getContext('2d') as CanvasRenderingContext2D;
            ctx.drawImage(img, 0, 0);
            done(ctx.getImageData(0, 0, img.width, img.height));
          };
          img.onerror = () => fail(new Error('cannot decode image'));
          img.src = src;
        });
      const [x, y] = await Promise.all([load(ua), load(ub)]);
      if (x.width !== y.width || x.height !== y.height) return 1;
      let diff = 0;
      for (let i = 0; i < x.data.length; i += 4) {
        const d =
          Math.abs(x.data[i] - y.data[i]) +
          Math.abs(x.data[i + 1] - y.data[i + 1]) +
          Math.abs(x.data[i + 2] - y.data[i + 2]);
        if (d > 48) diff++;
      }
      return diff / (x.width * x.height);
    },
    [url(a), url(b)] as const
  );
}

test.use({
  viewport: { width: 1440, height: 900 },
  locale: 'en-US',
  timezoneId: 'UTC',
  reducedMotion: 'reduce',
  deviceScaleFactor: 1,
});

for (const shot of SHOTS.filter(
  (s) => ONLY.length === 0 || ONLY.includes(s.name)
)) {
  for (const theme of THEMES) {
    test(`${shot.name} (${theme})`, async ({ page }) => {
      const file = resolve(OUT_DIR, `${shot.name}.${theme}.png`);
      try {
        await page.emulateMedia({
          colorScheme: theme,
          reducedMotion: 'reduce',
        });
        // the same key the theme toggle writes, read before the app's first paint
        await page.addInitScript(
          (t) => localStorage.setItem('theme', t),
          theme
        );
        await page.clock.setFixedTime(NOW);
        await base(page);
        for (const install of shot.stubs) await install(page);
        await page.goto(shot.route);
        await shot
          .ready(page)
          .first()
          .waitFor({ state: 'visible', timeout: 15_000 });
        await shot.actions?.(page);
        await page.addStyleTag({
          content:
            '*,*::before,*::after{animation:none!important;transition:none!important;caret-color:transparent!important}',
        });
        await page.evaluate(() => document.fonts.ready);
        await page.waitForLoadState('networkidle');
        await expect(page.locator('html')).toHaveClass(
          theme === 'dark' ? /dark/ : /^(?!.*dark)/
        );
        const options = { animations: 'disabled', caret: 'hide' } as const;
        const image = shot.region
          ? await shot.region(page).screenshot(options)
          : await page.screenshot({
              ...options,
              fullPage: shot.fullPage ?? false,
            });

        const missed = unmocked.get(page) ?? [];
        if (missed.length > 0) {
          console.warn(
            `[${shot.name}] API calls without a stub: ${[...new Set(missed)].join(', ')}`
          );
        }
        if (CHECK) {
          if (!existsSync(file)) {
            test.info().annotations.push({
              type: 'stale',
              description: `${shot.name}.${theme}: missing`,
            });
            console.log(`STALE ${shot.name}.${theme}.png (missing)`);
            return;
          }
          const share = await diffShare(page, readFileSync(file), image);
          if (share > TOLERANCE) {
            console.log(
              `STALE ${shot.name}.${theme}.png (${(share * 100).toFixed(2)}% of pixels differ)`
            );
            test.info().annotations.push({
              type: 'stale',
              description: `${shot.name}.${theme}`,
            });
          } else {
            console.log(`ok    ${shot.name}.${theme}.png`);
          }
          return;
        }
        mkdirSync(OUT_DIR, { recursive: true });
        writeFileSync(file, image);
        console.log(`wrote ${shot.name}.${theme}.png`);
      } catch (error) {
        throw new Error(
          `Shot "${shot.name}" (${theme}) failed: ${(error as Error).message}`,
          { cause: error }
        );
      }
    });
  }
}
