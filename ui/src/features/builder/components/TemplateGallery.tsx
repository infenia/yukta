// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Button } from '@/components/ui/button';
import { Modal } from '@/components/common/Modal';
import { InlineError } from '@/components/common/InlineError';
import { StateMessage } from '@/components/common/StateMessage';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { usePlugins } from '@/features/plugins/hooks/usePlugins';
import { getTemplate, listTemplates } from '../api';
import { flowLayout } from '../lib/flowLayout';
import { TemplateFlow } from './TemplateFlow';
import type { TemplateDetail, TemplateSummary } from '../types';
import { NeedsPlugins } from './NeedsPlugins';
import { TemplateCard } from './TemplateCard';

const GRID = 'grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4';

/** `2-free-deps` reads "Free deps"; a template without a folder is "Other". */
const categoryLabel = (category: string) => {
  const words = category.replace(/^\d+-/, '').replace(/-/g, ' ');
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : 'Other';
};

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
  const queryClient = useQueryClient();
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
  // the switched-off types the bundled templates actually use, for the one notice on top
  const needed = useMemo(
    () =>
      [...new Set((templates.data ?? []).flatMap((t) => t.pluginTypes))]
        .filter((type) => off.has(type))
        .sort(),
    [templates.data, off]
  );
  // the examples folders in number order (10 after 2), a template without one last
  const groups = useMemo(() => {
    const by = new Map<string, TemplateSummary[]>();
    for (const t of templates.data ?? []) {
      by.set(t.category ?? '', [...(by.get(t.category ?? '') ?? []), t]);
    }
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
      // always fresh; it also refreshes the cache the cards draw their preview from
      then(
        await queryClient.fetchQuery({
          queryKey: ['template', id],
          queryFn: () => getTemplate(id),
        })
      );
    } catch (e) {
      setError((e as Error).message);
    }
  };

  if (templates.isPending) {
    return <StateMessage tone="loading" title="Loading templates…" />;
  }

  const card = (t: TemplateSummary) => (
    <TemplateCard
      key={t.id}
      template={t}
      categories={categories}
      off={off}
      onUse={() => open(t.id, onPick)}
      onPreview={() => open(t.id, setPreview)}
    />
  );

  const blank = (
    <TemplateCard
      template={null}
      categories={categories}
      off={off}
      onUse={() => onPick()}
    />
  );

  return (
    <div className="flex flex-col gap-6">
      <InlineError message={error} />
      {templates.isError && (
        <InlineError message="Templates could not be loaded; you can still start blank." />
      )}
      <NeedsPlugins
        types={needed}
        title={`${needed.length === 1 ? 'A plugin' : 'Some plugins'} the templates use ${needed.length === 1 ? 'is' : 'are'} switched off`}
      />
      {/* the blank start leads the first group, or the one grid when there are no folders */}
      {groups.length === 0 || groups[0][0] === '' ? (
        <ul className={GRID}>
          {blank}
          {groups[0]?.[1].map(card)}
        </ul>
      ) : (
        groups.map(([category, list], index) => (
          <section key={category} aria-label={categoryLabel(category)}>
            <h2 className="text-overline text-on-surface-variant mb-3">
              {categoryLabel(category)}
            </h2>
            <ul className={GRID}>
              {index === 0 && blank}
              {list.map(card)}
            </ul>
          </section>
        ))
      )}
      {preview && (
        <Modal wide title={preview.title} onClose={() => setPreview(null)}>
          <p className="text-body-sm text-on-surface-variant mb-4">
            {preview.summary}
          </p>
          <NeedsPlugins
            types={preview.pluginTypes.filter((type) => off.has(type))}
            className="mb-4"
          />
          <TemplateCanvas
            template={preview}
            categories={categories}
            off={off}
          />
          <div className="flex justify-end gap-2">
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
    </div>
  );
}

/** The template's workflow as the animated flow, large and labelled; a switch when it has several. */
function TemplateCanvas({
  template,
  categories,
  off,
}: {
  template: TemplateDetail;
  categories: Record<string, string>;
  off: ReadonlySet<string>;
}) {
  const workflows = Object.values(template.session.workflows);
  const [chosen, setChosen] = useState(workflows[0]?.workflowId ?? '');
  const workflow = workflows.find((w) => w.workflowId === chosen);
  const flow = useMemo(
    () => workflow && flowLayout(workflow.nodes, workflow.edges),
    [workflow]
  );
  if (!flow) return null;
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
        className="border-border bg-muted/40 h-72 rounded-xl border px-10 pt-8 pb-12"
      >
        <TemplateFlow
          flow={flow}
          categories={categories}
          off={off}
          playing
          labels
        />
      </div>
    </div>
  );
}
