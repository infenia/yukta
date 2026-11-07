// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useSearchParams } from 'react-router';
import { PageHeader } from '@/components/layout/PageHeader';
import { CatalogTab } from './CatalogTab';
import { AttachmentsTab } from './AttachmentsTab';
import { DecisionsTable } from './DecisionsTable';
import { SupervisorsTab } from './SupervisorsTab';

export const TABS = [
  'catalog',
  'supervisors',
  'attachments',
  'decisions',
] as const;
export type Tab = (typeof TABS)[number];

/** Supervisors: the catalog of types, the configured supervisors, their attachments and decisions. */
export function SupervisorsPage() {
  const [params, setParams] = useSearchParams();
  const requested = params.get('tab');
  const tab: Tab = TABS.find((t) => t === requested) ?? 'catalog';
  const show = (next: Tab) => {
    const updated = new URLSearchParams(params);
    if (next === 'catalog') updated.delete('tab');
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
                className={`text-label-md -mb-px h-9 border-b-2 px-0.5 capitalize ${
                  tab === name
                    ? 'border-primary text-on-surface'
                    : 'text-on-surface-variant hover:text-on-surface border-transparent'
                }`}
              >
                {name}
              </button>
            ))}
          </div>
        }
      />
      <div
        id="supervisors-tabpanel"
        role="tabpanel"
        aria-labelledby={`supervisors-tab-${tab}`}
      >
        {tab === 'catalog' && <CatalogTab />}
        {tab === 'supervisors' && <SupervisorsTab />}
        {tab === 'attachments' && <AttachmentsTab />}
        {tab === 'decisions' && <DecisionsTable />}
      </div>
    </div>
  );
}
