// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useSearchParams } from 'react-router';
import { PageHeader } from '@/components/layout/PageHeader';
import { DecisionMakersTab } from './DecisionMakersTab';
import { ConflictsBanner } from './ConflictsBanner';
import { DecisionsTable } from './DecisionsTable';
import { SupervisorsBrowser } from './SupervisorsBrowser';

export const TABS = ['supervisors', 'decision-makers', 'activity'] as const;
export type Tab = (typeof TABS)[number];

const LABEL: Record<Tab, string> = {
  supervisors: 'Supervisors',
  'decision-makers': 'Decision makers',
  activity: 'Activity',
};

/** Supervisors: who watches each workflow, the decision makers they use, and what they did. */
export function SupervisorsPage() {
  const [params, setParams] = useSearchParams();
  const requested = params.get('tab');
  const tab: Tab = TABS.find((t) => t === requested) ?? 'supervisors';
  const show = (next: Tab) => {
    const updated = new URLSearchParams(params);
    if (next === 'supervisors') updated.delete('tab');
    else updated.set('tab', next);
    setParams(updated, { replace: true });
  };
  return (
    <div className="flex flex-1 flex-col gap-4 px-4 py-6 sm:px-6">
      <PageHeader
        backdrop
        title="Supervisors"
        description="Who watches your workflows, what each may do, and who they answer to. Usually set up by an AI agent over MCP, the CLI or the API."
        actions={
          <div
            role="tablist"
            className="border-border flex max-w-full gap-4 overflow-x-auto border-b"
          >
            {TABS.map((name) => (
              <button
                key={name}
                id={`supervisors-tab-${name}`}
                type="button"
                role="tab"
                aria-selected={tab === name}
                aria-controls="supervisors-tabpanel"
                onClick={() => show(name)}
                className={`text-label-md -mb-px h-9 shrink-0 border-b-2 px-0.5 ${
                  tab === name
                    ? 'border-primary text-on-surface'
                    : 'text-on-surface-variant hover:text-on-surface border-transparent'
                }`}
              >
                {LABEL[name]}
              </button>
            ))}
          </div>
        }
      />
      <ConflictsBanner />
      <div
        id="supervisors-tabpanel"
        role="tabpanel"
        aria-labelledby={`supervisors-tab-${tab}`}
      >
        {tab === 'supervisors' && <SupervisorsBrowser />}
        {tab === 'decision-makers' && <DecisionMakersTab />}
        {tab === 'activity' && <DecisionsTable />}
      </div>
    </div>
  );
}
