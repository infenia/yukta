// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { useNavigate } from 'react-router';
import { useQueries, useQuery } from '@tanstack/react-query';
import { StateMessage } from '@/components/common/StateMessage';
import { getOwnership, getSupervisionSummary } from '../api/supervision';
import {
  useAttachments,
  useSupervisors,
  useSupervisorTypes,
} from '../hooks/useSupervisorTypes';
import { decisionMaker } from '../lib/decisionMakers';
import { supervisionMapAll } from '../lib/supervisionMapAll';
import { SupervisionCanvas } from './SupervisionCanvas';

/**
 * Every supervisor on one canvas: who decides for it, who it reports to, the workflows it owns and
 * the workflows nobody owns. A workflow opens on a click to show its nodes and their owners; a
 * supervisor opens its page.
 */
export function SupervisionOverview() {
  const navigate = useNavigate();
  const supervisors = useSupervisors();
  const attachments = useAttachments();
  const types = useSupervisorTypes();
  const summary = useQuery({
    queryKey: ['supervision', 'summary'],
    queryFn: getSupervisionSummary,
  });
  const [open, setOpen] = useState<string[]>([]);
  // ponytail: one ownership read per opened workflow; a bulk endpoint would let every workflow open
  const ownership = useQueries({
    queries: open.map((key) => {
      const [session, workflow] = [
        key.slice(0, key.indexOf('/')),
        key.slice(key.indexOf('/') + 1),
      ];
      return {
        queryKey: ['supervision', 'ownership', session, workflow],
        queryFn: () => getOwnership(session, workflow),
      };
    }),
  });
  if (supervisors.isPending || attachments.isPending || summary.isPending) {
    return <StateMessage tone="loading" title="Loading the map…" />;
  }
  if (!supervisors.data || !attachments.data || !summary.data) {
    return (
      <StateMessage
        tone="error"
        title="Failed to load the map."
        onRetry={() => {
          void supervisors.refetch();
          void attachments.refetch();
          void summary.refetch();
        }}
      />
    );
  }
  const opened: Record<string, Record<string, string>> = {};
  open.forEach((key, i) => {
    const nodes = ownership[i]?.data?.nodes;
    if (nodes) opened[key] = nodes;
  });
  const graph = supervisionMapAll({
    supervisors: supervisors.data,
    maker: (type) => decisionMaker(type, types.data),
    attachments: attachments.data,
    unowned: summary.data.unownedWorkflows,
    opened,
  });
  if (graph.nodes.length === 0) {
    return (
      <StateMessage icon="shield_person" title="Nothing to map yet">
        Supervisors and the workflows they own appear here.
      </StateMessage>
    );
  }
  return (
    <SupervisionCanvas
      label="Supervision overview"
      nodes={graph.nodes}
      edges={graph.edges}
      className="h-[calc(100vh-14rem)] min-h-72"
      onNodeClick={(node) => {
        if (node.supervisor) {
          void navigate(
            `/supervisors?supervisor=${encodeURIComponent(node.supervisor)}`
          );
        } else if (node.toggle) {
          const key = node.toggle;
          setOpen((now) =>
            now.includes(key) ? now.filter((k) => k !== key) : [...now, key]
          );
        }
      }}
    />
  );
}
