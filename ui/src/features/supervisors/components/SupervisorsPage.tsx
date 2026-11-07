// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useSearchParams } from 'react-router';
import { PageHeader } from '@/components/layout/PageHeader';
import { CatalogTab } from './CatalogTab';
import { CoverageTab } from './CoverageTab';
import { HowItWorks } from './HowItWorks';
import { AttachmentsTab } from './AttachmentsTab';
import { DecisionsTable } from './DecisionsTable';
import { SupervisorsTab } from './SupervisorsTab';

export const TABS = [
  'coverage',
  'catalog',
  'supervisors',
  'attachments',
  'decisions',
] as const;
export type Tab = (typeof TABS)[number];

/** What each tab is called, and the one sentence that says what it shows. */
const TAB_INFO: Record<Tab, { label: string; hint: string }> = {
  coverage: {
    label: 'Coverage',
    hint: 'Each row is a place a run can happen. A filled chip is attached right there; a dashed chip is inherited from a wider row above and watches here too.',
  },
  catalog: {
    label: 'Library',
    hint: 'The kinds of supervisor you can create.',
  },
  supervisors: {
    label: 'Supervisors',
    hint: 'The supervisors you have set up.',
  },
  attachments: {
    label: 'Attachments',
    hint: 'Each place a supervisor is switched on.',
  },
  decisions: {
    label: 'Activity',
    hint: 'What supervisors did with each command.',
  },
};

/** Supervisors: the catalog of types, the configured supervisors, their attachments and decisions. */
export function SupervisorsPage() {
  const [params, setParams] = useSearchParams();
  const requested = params.get('tab');
  const tab: Tab = TABS.find((t) => t === requested) ?? 'coverage';
  const show = (next: Tab) => {
    const updated = new URLSearchParams(params);
    if (next === 'coverage') updated.delete('tab');
    else updated.set('tab', next);
    setParams(updated, { replace: true });
  };
  return (
    <div className="flex flex-1 flex-col gap-4 px-4 py-6 sm:px-6">
      <PageHeader
        backdrop
        title="Supervisors"
        description="Automatic operators that watch runs and step in: pick a type, configure it, attach it to a scope."
        actions={
          <div role="tablist" className="border-border flex gap-4 border-b">
            {TABS.map((name) => (
              <button
                key={name}
                id={`supervisors-tab-${name}`}
                type="button"
                role="tab"
                aria-selected={tab === name}
                aria-controls="supervisors-tabpanel"
                onClick={() => show(name)}
                className={`text-label-md -mb-px h-9 border-b-2 px-0.5 ${
                  tab === name
                    ? 'border-primary text-on-surface'
                    : 'text-on-surface-variant hover:text-on-surface border-transparent'
                }`}
              >
                {TAB_INFO[name].label}
              </button>
            ))}
          </div>
        }
      />
      <HowItWorks />
      <p className="text-body-md text-on-surface-variant">
        {TAB_INFO[tab].hint}
      </p>
      <div
        id="supervisors-tabpanel"
        role="tabpanel"
        aria-labelledby={`supervisors-tab-${tab}`}
      >
        {tab === 'coverage' && <CoverageTab />}
        {tab === 'catalog' && <CatalogTab />}
        {tab === 'supervisors' && <SupervisorsTab />}
        {tab === 'attachments' && <AttachmentsTab />}
        {tab === 'decisions' && <DecisionsTable />}
      </div>
    </div>
  );
}
