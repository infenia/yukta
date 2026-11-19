// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Fragment, type ReactNode } from 'react';
import { CodeBlock } from '@/components/common/CodeBlock';
import {
  Table as UiTable,
  TableBody,
  TableCell,
  TableFrame,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import {
  BUILD_FLAGS,
  CLI,
  DOCKER_FACTS,
  DOCKER_RUN,
  MATRIX,
  TUNING,
  type Table,
} from '../lib/packaging';

/** Text with `code` spans: the catalog data marks code with backticks. */
const inline = (text: string): ReactNode =>
  text.split('`').map((part, i) =>
    i % 2 ? (
      <code key={i} className="text-code-sm">
        {part}
      </code>
    ) : (
      <Fragment key={i}>{part}</Fragment>
    )
  );

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="space-y-2">
      <h2 className="text-headline-md">{title}</h2>
      {children}
    </section>
  );
}

function DataTable({ table }: { table: Table }) {
  return (
    <Section title={table.title}>
      <p className="text-body-md text-on-surface-variant max-w-prose">
        {inline(table.intro)}
      </p>
      <TableFrame>
        <UiTable>
          <TableHeader>
            <TableRow>
              {table.columns.map((c, i) => (
                <TableHead key={i}>{c}</TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {table.rows.map((row) => (
              <TableRow key={row[0]}>
                {row.map((cell, i) => (
                  <TableCell
                    key={i}
                    className={i === 0 ? 'text-code-sm font-medium' : undefined}
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

/** What changes with how Yukta is built and run: JVM, native, Docker, build flags, tuning, the CLI. */
export function PackagingGuide() {
  return (
    <div className="space-y-6">
      <DataTable table={MATRIX} />
      <DataTable table={BUILD_FLAGS} />
      <Section title="Running the Docker image">
        <ul className="text-body-md list-disc space-y-1 pl-5">
          {DOCKER_FACTS.map((fact) => (
            <li key={fact}>{inline(fact)}</li>
          ))}
        </ul>
        <CodeBlock code={DOCKER_RUN} label="docker run example" />
      </Section>
      <DataTable table={TUNING} />
      <DataTable table={CLI} />
    </div>
  );
}
