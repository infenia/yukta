// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Button } from '@/components/ui/button';
import { Modal } from '@/components/common/Modal';
import { InlineError } from '@/components/common/InlineError';
import { StateMessage } from '@/components/common/StateMessage';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { getTemplate, listTemplates } from '../api';
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
          <li
            key={type}
            className="bg-muted text-on-surface-variant rounded px-2 py-0.5 text-xs"
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
        {templates.data?.map(card)}
      </ul>
      {preview && (
        <Modal title={preview.title} onClose={() => setPreview(null)}>
          {/* plain text: the README is never parsed as HTML */}
          <pre className="text-body-sm max-h-96 overflow-auto whitespace-pre-wrap">
            {preview.readme}
          </pre>
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
