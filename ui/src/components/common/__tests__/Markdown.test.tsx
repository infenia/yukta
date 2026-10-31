// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { Markdown } from '../Markdown';

const SOURCE = `# 03 — CI gate

**Use case:** a gate with \`echo\` and [docs](https://example.com/x).

\`\`\`bash
curl -X POST localhost:9858
\`\`\`

- first
- second

1. one
2. two

> a quoted
> note

| Field | Meaning |
|---|---|
| \`command\` | what runs |
| plain | cell |
`;

describe('Markdown', () => {
  it('renders headings, text, code, lists, quotes and tables', () => {
    const { container } = render(<Markdown source={SOURCE} />);

    expect(
      screen.getByRole('heading', { name: '03 — CI gate' })
    ).toBeInTheDocument();
    expect(screen.getByText('Use case:').tagName).toBe('STRONG');
    expect(screen.getAllByText('echo')[0].tagName).toBe('CODE');
    expect(screen.getByRole('link', { name: 'docs' })).toHaveAttribute(
      'href',
      'https://example.com/x'
    );
    expect(container.querySelector('pre')).toHaveTextContent(
      'curl -X POST localhost:9858'
    );
    expect(screen.getAllByRole('list')[0]).toHaveTextContent('first');
    expect(
      within(screen.getAllByRole('list')[1]).getAllByRole('listitem')
    ).toHaveLength(2);
    expect(container.querySelector('ol')).not.toBeNull();
    expect(container.querySelector('blockquote')).toHaveTextContent(
      'a quoted note'
    );
    expect(screen.getByRole('table')).toBeInTheDocument();
    expect(
      screen.getByRole('columnheader', { name: 'Meaning' })
    ).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'command' })).toBeInTheDocument();
  });

  it('shows markup as text and only links http(s) addresses', () => {
    const { container } = render(
      <Markdown
        source={
          '<script>alert(1)</script> <b>x</b>\n\n[bad](javascript:alert(1)) and a stray ` tick'
        }
      />
    );

    expect(container.querySelector('script')).toBeNull();
    expect(container.querySelector('b')).toBeNull();
    expect(container).toHaveTextContent('<script>alert(1)</script> <b>x</b>');
    expect(screen.queryByRole('link')).toBeNull();
    expect(container).toHaveTextContent('[bad](javascript:alert(1))');
  });

  it('copes with an unclosed fence, a lone pipe and an empty source', () => {
    const open = render(<Markdown source={'```\nno end'} />);
    expect(open.container.querySelector('pre')).toHaveTextContent('no end');
    open.unmount();

    const pipe = render(<Markdown source={'| not a table'} />);
    expect(pipe.container).toHaveTextContent('| not a table');
    pipe.unmount();

    const empty = render(<Markdown source="" />);
    expect(empty.container.querySelectorAll('p')).toHaveLength(0);
  });
});
