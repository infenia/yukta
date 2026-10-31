// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ReactNode } from 'react';

/**
 * A small Markdown view for the READMEs of the examples: headings, paragraphs, lists, block quotes,
 * tables, fenced code, and `code`, **bold** and [links](https://…) in a line. It builds React
 * elements and never HTML, so text such as `<script>` shows as text; only http(s) links are links.
 */
const INLINE = /(`[^`]+`|\*\*[^*]+\*\*|\[[^\]]+\]\([^)\s]+\))/;

function inline(text: string): ReactNode[] {
  return text.split(INLINE).map((part, index) => {
    if (part.startsWith('`') && part.length > 1) {
      return (
        <code key={index} className="text-code-sm bg-muted rounded px-1">
          {part.slice(1, -1)}
        </code>
      );
    }
    if (part.startsWith('**') && part.length > 3) {
      return <strong key={index}>{part.slice(2, -2)}</strong>;
    }
    const link = /^\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)$/.exec(part);
    return link ? (
      <a
        key={index}
        href={link[2]}
        target="_blank"
        rel="noopener noreferrer"
        className="text-primary"
      >
        {link[1]}
      </a>
    ) : (
      part
    );
  });
}

const cells = (row: string) =>
  row
    .trim()
    .replace(/^\||\|$/g, '')
    .split('|')
    .map((cell) => cell.trim());

export function Markdown({ source }: { source: string }) {
  const lines = source.split('\n');
  const blocks: ReactNode[] = [];
  let i = 0;
  const key = () => blocks.length;
  while (i < lines.length) {
    const line = lines[i];
    if (line.startsWith('```')) {
      const code: string[] = [];
      i += 1;
      while (i < lines.length && !lines[i].startsWith('```'))
        code.push(lines[i++]);
      i += 1;
      blocks.push(
        <pre
          key={key()}
          className="text-code-sm bg-muted overflow-auto rounded-lg p-3"
        >
          {code.join('\n')}
        </pre>
      );
    } else if (/^#{1,6} /.test(line)) {
      blocks.push(
        <h3 key={key()} className="text-label-md text-on-surface mt-2">
          {inline(line.replace(/^#+ /, ''))}
        </h3>
      );
      i += 1;
    } else if (
      line.startsWith('|') &&
      /^\|?[\s:|-]+\|?$/.test(lines[i + 1] ?? '')
    ) {
      const head = cells(line);
      i += 2;
      const rows: string[][] = [];
      while (i < lines.length && lines[i].startsWith('|'))
        rows.push(cells(lines[i++]));
      blocks.push(
        <table key={key()} className="text-body-sm w-full border-collapse">
          <thead>
            <tr>
              {head.map((cell, c) => (
                <th key={c} className="border-border border-b p-1 text-left">
                  {inline(cell)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, r) => (
              <tr key={r}>
                {row.map((cell, c) => (
                  <td key={c} className="border-border border-b p-1 align-top">
                    {inline(cell)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      );
    } else if (/^\s*([-*]|\d+\.) /.test(line)) {
      const ordered = /^\s*\d+\. /.test(line);
      const items: string[] = [];
      while (i < lines.length && /^\s*([-*]|\d+\.) /.test(lines[i])) {
        items.push(lines[i++].replace(/^\s*([-*]|\d+\.) /, ''));
      }
      const List = ordered ? 'ol' : 'ul';
      blocks.push(
        <List
          key={key()}
          className={`${ordered ? 'list-decimal' : 'list-disc'} ml-5`}
        >
          {items.map((item, n) => (
            <li key={n}>{inline(item)}</li>
          ))}
        </List>
      );
    } else if (line.startsWith('>')) {
      const quote: string[] = [];
      while (i < lines.length && lines[i].startsWith('>')) {
        quote.push(lines[i++].replace(/^>\s?/, ''));
      }
      blocks.push(
        <blockquote
          key={key()}
          className="border-border text-on-surface-variant border-l-2 pl-3"
        >
          {inline(quote.join(' '))}
        </blockquote>
      );
    } else if (line.trim() === '') {
      i += 1;
    } else {
      const paragraph: string[] = [];
      while (
        i < lines.length &&
        lines[i].trim() !== '' &&
        !/^(```|#{1,6} |>|\||\s*([-*]|\d+\.) )/.test(lines[i])
      ) {
        paragraph.push(lines[i++]);
      }
      if (paragraph.length === 0) {
        // a line that starts like a block but is not one: show it as text
        paragraph.push(lines[i++]);
      }
      blocks.push(<p key={key()}>{inline(paragraph.join(' '))}</p>);
    }
  }
  return <div className="text-body-md flex flex-col gap-2">{blocks}</div>;
}
