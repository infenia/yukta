// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/*
 * A concept diagram as a light/dark pair with a caption that says what to notice.
 * The SVGs are generated (docs/images/diagrams/generate.py), never edited by hand.
 * Props: name (file stem in /images/diagrams, without .light.svg), alt (the point of the picture),
 *        caption (what to notice). Everything lives inside the component (Mintlify snippet scope).
 */
export const Diagram = ({ name, alt, caption }) => (
  <figure className="my-6">
    <img
      className="yk-diagram block dark:hidden"
      src={`/images/diagrams/${name}.light.svg`}
      alt={alt}
      loading="lazy"
    />
    <img
      className="yk-diagram hidden dark:block"
      src={`/images/diagrams/${name}.dark.svg`}
      alt={alt}
      loading="lazy"
    />
    {caption ? (
      <figcaption className="mt-2 text-sm text-zinc-600 dark:text-zinc-400">{caption}</figcaption>
    ) : null}
  </figure>
);
