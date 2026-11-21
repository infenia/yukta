// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Reads the Mintlify pages in ../docs into small passages the assistant can search: the
// `assistant-docs.json` the UI build emits (and `vite` serves in development). Pages the
// assistant has no use for (the API reference, contributor and style guides, snippets) are left out.
import fs from 'node:fs';
import path from 'node:path';

/** Where the pages are published; a passage links to its page here. */
export const SITE = 'https://yukta.infenia.com/';

/** Folders that are not read, wherever they are. */
const SKIP = new Set([
  'api',
  'internal-style',
  'contributing',
  'snippets',
  'images',
]);

/** A passage is cut at a paragraph once it passes this many characters. */
const MAX = 1500;

/** The title in the frontmatter, and the page without it. */
export function frontmatter(source) {
  const match = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?/.exec(source);
  const head = match ? match[1] : '';
  const title = /^title:\s*"?(.*?)"?\s*$/m.exec(head);
  return {
    title: title ? title[1] : '',
    body: match ? source.slice(match[0].length) : source,
  };
}

/** Removes tags until none is left: one pass can leave one behind (`<<b>b>` becomes `<b>`). */
function untag(text) {
  let previous;
  let current = text;
  do {
    previous = current;
    current = current.replace(/<\/?[A-Za-z][^>]*>/g, '');
  } while (current !== previous);
  return current;
}

/** The page as plain Markdown: no imports, comments or JSX tags (their text stays); code, fenced or inline, stays. */
export function plain(body) {
  return body
    .split(/(```[\s\S]*?```|`[^`\n]*`)/g)
    .map((part, index) =>
      index % 2 === 1
        ? part
        : untag(
            part
              .replace(/^(?:import|export) .*$/gm, '')
              .replace(/\{\/\*[\s\S]*?\*\/\}/g, '')
          )
    )
    .join('')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

const slug = (heading) =>
  heading
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '');

/** Cuts a section into passages of about MAX characters at its paragraph breaks. */
function pack(text) {
  const out = [];
  let current = '';
  for (const paragraph of text.split(/\n\n/)) {
    if (current && current.length + paragraph.length > MAX) {
      out.push(current);
      current = paragraph;
    } else {
      current = current ? `${current}\n\n${paragraph}` : paragraph;
    }
  }
  out.push(current);
  return out;
}

/** The passages of one page: `t` page title, `u` link, `h` section heading, `x` text. */
export function passages(source, relative) {
  const { title, body } = frontmatter(source);
  const page = relative.replace(/\.mdx$/, '').replace(/(^|\/)index$/, '');
  const sections = [];
  let section = { heading: '', lines: [] };
  for (const line of plain(body).split('\n')) {
    const heading = /^## +(.+?)\s*$/.exec(line);
    if (heading) {
      sections.push(section);
      section = { heading: heading[1], lines: [] };
    } else {
      section.lines.push(line);
    }
  }
  sections.push(section);
  return sections.flatMap(({ heading, lines }) =>
    pack(lines.join('\n').trim())
      .filter((text) => text.length >= 20)
      .map((x) => ({
        t: title,
        u: `${SITE}${page}${heading ? `#${slug(heading)}` : ''}`,
        h: heading,
        x,
      }))
  );
}

/** Every passage of every page under `root`; none when there is no such folder. */
export function buildDocsIndex(root) {
  if (!fs.existsSync(root)) return [];
  const out = [];
  const walk = (dir) => {
    for (const entry of fs
      .readdirSync(dir, { withFileTypes: true })
      .sort((a, b) => a.name.localeCompare(b.name))) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        if (!SKIP.has(entry.name)) walk(full);
      } else if (entry.name.endsWith('.mdx')) {
        out.push(
          ...passages(
            fs.readFileSync(full, 'utf8'),
            path.relative(root, full).split(path.sep).join('/')
          )
        );
      }
    }
  };
  walk(root);
  return out;
}
