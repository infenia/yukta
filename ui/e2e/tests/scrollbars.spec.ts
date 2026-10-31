// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { test, expect } from '../fixtures';

// Headless screenshots hide scrollbars, so the style is checked through the computed style of the
// WebKit pseudo-elements (Chromium and Safari); Firefox gets the standard properties instead.
test.describe('Scrollbars', () => {
  test.skip(({ browserName }) => browserName !== 'chromium');

  for (const scheme of ['light', 'dark'] as const) {
    test(`are thin, with a themed thumb, in ${scheme} mode`, async ({
      page,
    }) => {
      await page.emulateMedia({ colorScheme: scheme });
      await page.goto('/sessions');
      const style = await page.evaluate(() => {
        const read = (pseudo: string) => {
          const css = getComputedStyle(document.body, pseudo);
          return { width: css.width, background: css.backgroundColor };
        };
        return {
          bar: read('::-webkit-scrollbar'),
          track: read('::-webkit-scrollbar-track'),
          thumb: read('::-webkit-scrollbar-thumb'),
        };
      });

      expect(style.bar.width).toBe('8px');
      expect(style.track.background).toBe('rgba(0, 0, 0, 0)');
      // a colour from the theme, not the browser's grey
      expect(style.thumb.background).not.toBe('rgba(0, 0, 0, 0)');
    });
  }

  test('the thumb differs between light and dark', async ({ page }) => {
    const thumb = async (scheme: 'light' | 'dark') => {
      await page.emulateMedia({ colorScheme: scheme });
      await page.goto('/sessions');
      return page.evaluate(
        () =>
          getComputedStyle(document.body, '::-webkit-scrollbar-thumb')
            .backgroundColor
      );
    };
    expect(await thumb('light')).not.toBe(await thumb('dark'));
  });
});
