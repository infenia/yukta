// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Button } from '@/components/ui/button';
import { Modal } from '@/components/common/Modal';
import { Tip } from '@/components/ui/tooltip';
import { Callout } from '@/components/common/Callout';
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
  // the types the server has switched off: a template that uses one fails when it runs
  const off = useMemo(
    () =>
      new Set(
        (plugins.data ?? [])
          .filter((p) => p.enabled === false)
          .map((p) => p.type)
      ),
    [plugins.data]
  );
  // the examples folders, in their number order, each with its templates
  const groups = useMemo(() => {
    const by = new Map<string, TemplateSummary[]>();
    for (const t of templates.data ?? []) {
      by.set(t.category ?? '', [...(by.get(t.category ?? '') ?? []), t]);
    }
    // a template without a folder goes last
    const rank = (c: string) => (c ? 0 : 1);
    return [...by].sort(
      ([a], [b]) =>
        rank(a) - rank(b) || a.localeCompare(b, undefined, { numeric: true })
    );
  }, [templates.data]);
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
      className="border-border bg-card flex flex-col gap-2 rounded-xl border p-4"
    >
      <h3 className="text-label-md text-on-surface">{t.title}</h3>
      <p className="text-body-sm text-on-surface-variant">{t.summary}</p>
      <ul className="flex flex-wrap gap-1" aria-label="Plugin types">
        {t.pluginTypes.map((type) => (
          <Tip
            key={type}
            label={off.has(type) ? 'Not enabled on this server' : null}
          >
            <li
              className={`rounded px-2 py-0.5 text-xs ${off.has(type) ? 'line-through opacity-70' : ''}`}
              style={{
                background: categoryTint(categories[type] ?? null),
                color: categoryAccent(categories[type] ?? null),
              }}
            >
              {type}
            </li>
          </Tip>
        ))}
      </ul>
      <NeedsPlugins types={t.pluginTypes.filter((type) => off.has(type))} />
      <div className="mt-auto flex flex-wrap gap-2 pt-2">
        <Button size="sm" onClick={() => open(t.id, onPick)}>
          Use template
        </Button>
        <Button
          size="sm"
          variant="outline"
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
      <ul className={GRID}>
        <li className="border-border bg-card flex flex-col gap-2 rounded-xl border p-4">
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
      </ul>
      {groups.map(([category, list]) => (
        <section key={category} aria-label={categoryLabel(category)}>
          <h2 className="text-overline text-on-surface-variant mb-2">
            {categoryLabel(category)}
          </h2>
          <ul className={GRID}>{list.map(card)}</ul>
        </section>
      ))}
      {preview && (
        <Modal wide title={preview.title} onClose={() => setPreview(null)}>
          <TemplateCanvas template={preview} categories={categories} />
          <NeedsPlugins
            types={preview.pluginTypes.filter((type) => off.has(type))}
            className="mb-4"
          />
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

const GRID = 'grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4';

/** `2-free-deps` reads "Free deps"; a template without a folder is "Other". */
const categoryLabel = (category: string) => {
  const words = category.replace(/^\d+-/, '').replace(/-/g, ' ');
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : 'Other';
};

/** Which plugins the server has switched off, and the setting that switches them on. */
function NeedsPlugins({
  types,
  className,
}: {
  types: string[];
  className?: string;
}) {
  if (types.length === 0) return null;
  return (
    <Callout
      kind="warning"
      compact
      className={className}
      title={`Needs ${types.join(', ')} enabled`}
    >
      <span className="text-body-sm">
        Switched off on this server, so a run fails until you set{' '}
        <code className="text-code-sm select-all">
          YUKTA_PLUGINS_ENABLED={types.join(',')}
        </code>{' '}
        (or <code className="text-code-sm">yukta.plugins.enabled</code>) and
        restart. You can still use the template and edit it.
      </span>
    </Callout>
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
              className={`text-label-md rounded-lg px-3 py-1 ${
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
        className="border-border h-64 overflow-hidden rounded-xl border"
      >
        <WorkflowCanvas key={chosen} graph={graph} tasksByNode={NO_TASKS} />
      </div>
    </div>
  );
}
