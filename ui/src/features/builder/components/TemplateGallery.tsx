// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Button } from '@/components/ui/button';
import { Modal } from '@/components/common/Modal';
import { InlineError } from '@/components/common/InlineError';
import { StateMessage } from '@/components/common/StateMessage';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Markdown } from '@/components/common/Markdown';
import { usePlugins } from '@/features/plugins/hooks/usePlugins';
import { WorkflowCanvas } from '@/features/workflow/components/WorkflowCanvas';
import {
  categoryAccent,
  categoryTint,
} from '@/features/workflow/lib/categoryAccent';
import { getTemplate, listTemplates } from '../api';
import { toPreviewGraph } from './WorkflowEditorPage.helpers';
import type { TemplateDetail, TemplateSummary } from '../types';

/** Step 1 of a new session: a blank one, or one of the bundled examples. */
export function TemplateGallery({
  onPick,
}: {
  /** Called with no template for a blank session. */
  onPick: (template?: TemplateDetail) => void;
}) {
  const templates = useQuery({
    queryKey: ['templates'],
    queryFn: listTemplates,
  });
  const plugins = usePlugins();
  // the plugin category by type colours the chips and the nodes of the preview
  const categories = useMemo(
    () =>
      Object.fromEntries((plugins.data ?? []).map((p) => [p.type, p.category])),
    [plugins.data]
  );
  const [error, setError] = useState<string | null>(null);
  const [preview, setPreview] = useState<TemplateDetail | null>(null);

  // A template is fetched whole (README and workflows) when picked or previewed
  const open = async (id: string, then: (template: TemplateDetail) => void) => {
    setError(null);
    try {
      then(await getTemplate(id));
    } catch (e) {
      setError((e as Error).message);
    }
  };

  if (templates.isPending) {
    return <StateMessage tone="loading" title="Loading templates…" />;
  }

  const card = (t: TemplateSummary) => (
    <li
      key={t.id}
      className="flex flex-col gap-2 rounded-xl border border-border bg-card p-4"
    >
      <h3 className="text-label-md text-on-surface">{t.title}</h3>
      <p className="text-body-sm text-on-surface-variant">{t.summary}</p>
      <ul className="flex flex-wrap gap-1" aria-label="Plugin types">
        {t.pluginTypes.map((type) => (
          <li
            key={type}
            className="rounded px-2 py-0.5 text-xs"
            style={{
              background: categoryTint(categories[type] ?? null),
              color: categoryAccent(categories[type] ?? null),
            }}
          >
            {type}
          </li>
        ))}
      </ul>
      <div className="mt-auto flex gap-2 pt-2">
        <Button size="sm" onClick={() => open(t.id, onPick)}>
          Use {t.title}
        </Button>
        <Button
          size="sm"
          variant="ghost"
          onClick={() => open(t.id, setPreview)}
        >
          Preview
        </Button>
      </div>
    </li>
  );

  return (
    <>
      <InlineError message={error} />
      {templates.isError && (
        <InlineError message="Templates could not be loaded; you can still start blank." />
      )}
      <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <li className="flex flex-col gap-2 rounded-xl border border-border bg-card p-4">
          <h3 className="text-label-md text-on-surface">Blank session</h3>
          <p className="text-body-sm text-on-surface-variant">
            A trigger wired to a console terminal. Build the rest yourself.
          </p>
          <div className="mt-auto pt-2">
            <Button size="sm" onClick={() => onPick()}>
              Start blank
            </Button>
          </div>
        </li>
        {templates.data?.map(card)}
      </ul>
      {preview && (
        <Modal wide title={preview.title} onClose={() => setPreview(null)}>
          <TemplateCanvas template={preview} categories={categories} />
          <Markdown source={preview.readme} />
          <div className="mt-4 flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setPreview(null)}>
              Close
            </Button>
            <Button
              onClick={() => {
                onPick(preview);
                setPreview(null);
              }}
            >
              Use this template
            </Button>
          </div>
        </Modal>
      )}
    </>
  );
}

const NO_TASKS = {};

/** The template's workflow on the read-only canvas; a switch when it has several. */
function TemplateCanvas({
  template,
  categories,
}: {
  template: TemplateDetail;
  categories: Record<string, string>;
}) {
  const workflows = Object.values(template.session.workflows);
  const [chosen, setChosen] = useState(workflows[0]?.workflowId ?? '');
  const workflow = workflows.find((w) => w.workflowId === chosen);
  const graph = useMemo(
    () => (workflow ? toPreviewGraph(workflow, categories, {}) : null),
    [workflow, categories]
  );
  if (!graph) return null;
  return (
    <div className="mb-4 flex flex-col gap-2">
      {workflows.length > 1 && (
        <div role="tablist" aria-label="Workflows" className="flex gap-1">
          {workflows.map((w) => (
            <button
              key={w.workflowId}
              type="button"
              role="tab"
              aria-selected={w.workflowId === chosen}
              onClick={() => setChosen(w.workflowId)}
              className={`rounded-lg px-3 py-1 text-label-md ${
                w.workflowId === chosen
                  ? 'bg-muted text-on-surface'
                  : 'text-on-surface-variant hover:bg-muted'
              }`}
            >
              {w.workflowId}
            </button>
          ))}
        </div>
      )}
      <div
        data-testid="template-canvas"
        className="h-64 overflow-hidden rounded-xl border border-border"
      >
        <WorkflowCanvas key={chosen} graph={graph} tasksByNode={NO_TASKS} />
      </div>
    </div>
  );
}
