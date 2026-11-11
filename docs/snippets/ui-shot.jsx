// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/*
 * A screenshot of the web UI, as a light/dark pair. The images are generated, never edited by hand:
 * `yk tool -C ui pnpm docs:screenshots` writes /images/ui/<name>.light.png and <name>.dark.png from
 * ui/e2e/docs-screenshots/shots.ts. Pages refer to a shot only by `name`, so when the UI changes you
 * regenerate the images and no page changes.
 * Props: name (shot id), alt (what the picture shows), caption (what to notice).
 */
export const UiShot = ({ name, alt, caption }) => (
  <figure className="my-6">
    <img
      className="block dark:hidden rounded-xl border border-zinc-950/10"
      src={`/images/ui/${name}.light.png`}
      alt={alt}
      loading="lazy"
    />
    <img
      className="hidden dark:block rounded-xl border border-white/10"
      src={`/images/ui/${name}.dark.png`}
      alt={alt}
      loading="lazy"
    />
    {caption ? (
      <figcaption className="mt-2 text-sm text-zinc-600 dark:text-zinc-400">{caption}</figcaption>
    ) : null}
  </figure>
);
