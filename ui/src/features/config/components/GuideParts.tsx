// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Fragment, type ReactNode } from 'react';
import { CodeBlock } from '@/components/common/CodeBlock';
import { cn } from '@/lib/utils';
import {
  Table as UiTable,
  TableBody,
  TableCell,
  TableFrame,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import type { Table } from '../lib/packaging';

/** Text with `code` spans: the catalog data marks code with backticks. */
export const inline = (text: string): ReactNode =>
  text.split('`').map((part, i) =>
    i % 2 ? (
      <code key={i} className="text-code-sm bg-muted rounded px-1.5 py-0.5">
        {part}
      </code>
    ) : (
      <Fragment key={i}>{part}</Fragment>
    )
  );

/** A paragraph at a comfortable reading width. Text with backticks shows code chips. */
export function Prose({ children }: { children: string }) {
  return (
    <p className="text-body-md text-on-surface-variant leading-relaxed">
      {inline(children)}
    </p>
  );
}

export function Bullets({ items }: { items: readonly string[] }) {
  return (
    <ul className="text-body-md list-disc space-y-2 pl-5 leading-relaxed">
      {items.map((item) => (
        <li key={item}>{inline(item)}</li>
      ))}
    </ul>
  );
}

/** One thing to do: a title, what it means, and a command or file to copy. */
export function StepCard({
  title,
  text,
  code,
  label,
}: {
  title: string;
  text: string;
  code: string;
  label: string;
}) {
  return (
    <div className="border-border bg-card shadow-card space-y-3 rounded-xl border p-5">
      <h3 className="text-label-lg">{title}</h3>
      <Prose>{text}</Prose>
      <CodeBlock code={code} label={label} />
    </div>
  );
}

export function Section({
  title,
  children,
}: {
  title: string;
  children: ReactNode;
}) {
  return (
    <section className="space-y-3">
      <h2 className="text-headline-md">{title}</h2>
      {children}
    </section>
  );
}

export function DataTable({
  table,
  mono = true,
}: {
  table: Table;
  /** The first column holds names of things to type (code font); false for plain labels. */
  mono?: boolean;
}) {
  return (
    <Section title={table.title}>
      <Prose>{table.intro}</Prose>
      <TableFrame>
        <UiTable>
          <TableHeader>
            <TableRow>
              {table.columns.map((c, i) => (
                <TableHead key={i} className="px-5">
                  {c}
                </TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {table.rows.map((row) => (
              <TableRow key={row[0]}>
                {row.map((cell, i) => (
                  <TableCell
                    key={i}
                    className={cn(
                      'px-5 py-3.5 align-top wrap-anywhere whitespace-normal',
                      i === 0 &&
                        (mono ? 'text-code-sm font-medium' : 'font-medium')
                    )}
                  >
                    {i === 0 ? cell : inline(cell)}
                  </TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </UiTable>
      </TableFrame>
    </Section>
  );
}
