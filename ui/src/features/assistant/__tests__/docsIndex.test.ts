// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import {
  buildDocsIndex,
  frontmatter,
  passages,
  plain,
  SITE,
} from '../../../../scripts/docsIndex.mjs';

const page = (title: string, body: string) =>
  `---\ntitle: "${title}"\ndescription: "d"\n---\n${body}`;
const paragraph = (word: string, n = 400) => `${word} `.repeat(n).trim();

describe('frontmatter', () => {
  it('reads the title, quoted or not, and returns the page without the header', () => {
    expect(frontmatter('---\ntitle: "Install Yukta"\nx: 1\n---\nBody')).toEqual(
      {
        title: 'Install Yukta',
        body: 'Body',
      }
    );
    expect(frontmatter('---\r\ntitle: Plain\r\n---\r\nBody').title).toBe(
      'Plain'
    );
  });

  it('copes with a header without a title and with no header', () => {
    expect(frontmatter('---\nx: 1\n---\nBody')).toEqual({
      title: '',
      body: 'Body',
    });
    expect(frontmatter('Just text')).toEqual({ title: '', body: 'Just text' });
  });
});

describe('plain', () => {
  it('drops imports, exports, comments and tags but keeps what is inside the tags', () => {
    const out = plain(
      [
        'import { A } from "/snippets/a.jsx";',
        'export const x = 1;',
        '',
        '<ReaderState',
        '  learn="a thing"',
        '/>',
        '<Note>Mind **this**.</Note>',
        '{/* a comment',
        'over two lines */}',
        'After.',
      ].join('\n')
    );

    expect(out).toBe('Mind **this**.\n\nAfter.');
  });

  it('keeps code as it is, fenced or inline, even where it looks like a tag', () => {
    const out = plain(
      'Run `yukta start <id>` then:\n\n```bash\nimport x\n<Tag attr>\n```\n\nDone.'
    );

    expect(out).toContain('`yukta start <id>`');
    expect(out).toContain('```bash\nimport x\n<Tag attr>\n```');
  });
});

describe('passages', () => {
  it('splits a page at its ## headings and links each to its section', () => {
    const out = passages(
      page(
        'Install',
        'Intro paragraph that is long enough.\n\n## Docker\nRun it with Docker, like so.\n\n## Per-user & more!\nSomething about users here.'
      ),
      'get-started/install.mdx'
    );

    expect(out.map(({ h, u }) => [h, u])).toEqual([
      ['', `${SITE}get-started/install`],
      ['Docker', `${SITE}get-started/install#docker`],
      ['Per-user & more!', `${SITE}get-started/install#per-user-more`],
    ]);
    expect(out[1]).toEqual({
      t: 'Install',
      u: `${SITE}get-started/install#docker`,
      h: 'Docker',
      x: 'Run it with Docker, like so.',
    });
  });

  it('links the index page to the folder, and drops passages that are only a few words', () => {
    expect(
      passages(page('Home', 'Welcome to the documentation.'), 'index.mdx')[0].u
    ).toBe(SITE);
    expect(passages(page('G', 'x'), 'guides/index.mdx')).toEqual([]);
    expect(passages(page('Short', '## A\nok'), 'a.mdx')).toEqual([]);
  });

  it('keeps the short paragraphs of a section together', () => {
    const out = passages(
      page(
        'Short',
        '## S\nThe first short paragraph.\n\nThe second short paragraph.'
      ),
      'short.mdx'
    );

    expect(out.map((p) => p.x)).toEqual([
      'The first short paragraph.\n\nThe second short paragraph.',
    ]);
  });

  it('cuts a long section at paragraphs', () => {
    const out = passages(
      page(
        'Long',
        `## S\n${paragraph('a')}\n\n${paragraph('b')}\n\n${paragraph('c')}`
      ),
      'long.mdx'
    );

    expect(out.length).toBeGreaterThan(1);
    expect(out.map((p) => p.x).join('\n\n')).toContain(paragraph('c'));
    expect(out.every((p) => p.h === 'S')).toBe(true);
  });
});

describe('buildDocsIndex', () => {
  const made: string[] = [];
  afterEach(() => {
    for (const dir of made.splice(0))
      fs.rmSync(dir, { recursive: true, force: true });
  });
  const docs = (files: Record<string, string>) => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'yukta-docs-'));
    made.push(root);
    for (const [name, text] of Object.entries(files)) {
      fs.mkdirSync(path.dirname(path.join(root, name)), { recursive: true });
      fs.writeFileSync(path.join(root, name), text);
    }
    return root;
  };

  it('reads every page in order, skipping the folders the assistant has no use for', () => {
    const body = 'A paragraph that is long enough to keep.';
    const root = docs({
      'b.mdx': page('B', body),
      'a.mdx': page('A', body),
      'guides/g.mdx': page('G', body),
      'api/x.mdx': page('Api', body),
      'snippets/s.mdx': page('Snip', body),
      'internal-style/i.mdx': page('Style', body),
      'contributing/c.mdx': page('Contribute', body),
      'images/i.mdx': page('Img', body),
      'docs.json': '{}',
    });

    expect(buildDocsIndex(root).map((p) => p.t)).toEqual(['A', 'B', 'G']);
  });

  it('is empty when there is no docs folder', () => {
    expect(
      buildDocsIndex(path.join(os.tmpdir(), 'yukta-no-such-docs'))
    ).toEqual([]);
  });
});
