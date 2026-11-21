// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

export const SITE: string;

/** One searchable passage of the documentation. */
export interface Passage {
  /** The title of the page. */
  t: string;
  /** The link to the page, with the section. */
  u: string;
  /** The section heading, empty before the first one. */
  h: string;
  /** The text. */
  x: string;
}

export function frontmatter(source: string): { title: string; body: string };
export function plain(body: string): string;
export function passages(source: string, relative: string): Passage[];
export function buildDocsIndex(root: string): Passage[];
