// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { StateMessage } from '@/components/common/StateMessage';
import { NativeSelect } from '@/components/ui/native-select';
import { useAttachments } from '../hooks/useSupervisorTypes';
import { AttachmentsTable } from './AttachmentsTable';

/** Every attachment, filtered by source and by the kind of scope. */
export function AttachmentsTab() {
  const [source, setSource] = useState('');
  const [kind, setKind] = useState('');
  const { data, isPending, error } = useAttachments({
    source: source || undefined,
  });
  if (isPending) return <StateMessage tone="loading" title="Loading…" />;
  if (error || !data) {
    return (
      <StateMessage tone="error" title="Failed to load the attachments." />
    );
  }
  const shown = kind
    ? data.filter((a) =>
        kind === 'global'
          ? a.scope === 'global'
          : a.scope.startsWith(`${kind}:`)
      )
    : data;
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap gap-4">
        <label className="text-label-md flex items-center gap-2">
          Source
          <NativeSelect
            aria-label="Source"
            value={source}
            onChange={(e) => setSource(e.target.value)}
          >
            <option value="">All</option>
            <option value="MANUAL">Manual</option>
            <option value="DEFINITION">Definition</option>
            <option value="SYSTEM">System</option>
          </NativeSelect>
        </label>
        <label className="text-label-md flex items-center gap-2">
          Scope
          <NativeSelect
            aria-label="Scope"
            value={kind}
            onChange={(e) => setKind(e.target.value)}
          >
            <option value="">All</option>
            <option value="global">Global</option>
            <option value="session">Session</option>
            <option value="workflow">Workflow or nodes</option>
          </NativeSelect>
        </label>
      </div>
      <AttachmentsTable attachments={shown} showSupervisor />
    </div>
  );
}
