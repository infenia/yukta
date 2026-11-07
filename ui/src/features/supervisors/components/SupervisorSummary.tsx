// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { scopeLabel } from '../lib/scope';
import { capabilities, commandLabel } from '../lib/capabilities';
import type { AttachmentView, SupervisorView } from '../types';

/** What a supervisor watches and may do, in words; the raw settings are one click away. */
export function SupervisorSummary({
  supervisor,
  attachments,
  typeDefaults,
}: {
  supervisor: Pick<SupervisorView, 'settings' | 'dryRun'>;
  attachments: AttachmentView[];
  typeDefaults: string[] | null;
}) {
  const can = capabilities(supervisor, typeDefaults);
  const rows: [string, string][] = [
    [
      'Watches',
      attachments.length === 0
        ? 'Nowhere yet: attach it to a scope to start.'
        : attachments
            .map((a) => scopeLabel(a.scope) + (a.enabled ? '' : ' (paused)'))
            .join('; '),
    ],
    [
      'May send',
      can.commands === null
        ? 'Any command'
        : can.commands.length === 0
          ? 'No command'
          : can.commands.map(commandLabel).join(', '),
    ],
    [
      'Input requests',
      can.canAnswer
        ? `May answer up to ${can.maxRisk} risk; a HIGH-risk request is always answered by a person.`
        : 'Cannot answer; it can only hand a request up.',
    ],
    [
      'Mode',
      can.dryRun
        ? 'Dry run: it decides and records, but sends nothing.'
        : 'Live: its commands are sent.',
    ],
  ];
  return (
    <dl className="border-border bg-card grid gap-x-6 gap-y-3 rounded-xl border p-4 sm:grid-cols-[8rem_1fr]">
      {rows.map(([term, text]) => (
        <div key={term} className="contents">
          <dt className="text-label-md text-on-surface-variant">{term}</dt>
          <dd className="text-body-md text-on-surface">{text}</dd>
        </div>
      ))}
    </dl>
  );
}
