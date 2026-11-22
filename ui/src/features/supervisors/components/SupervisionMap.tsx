// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useNavigate } from 'react-router';
import { useOwnedNodes } from '../hooks/useOwnedNodes';
import { useSupervisorTypes } from '../hooks/useSupervisorTypes';
import { decisionMaker } from '../lib/decisionMakers';
import { supervisionGraph } from '../lib/supervisionGraph';
import type { SupervisorView } from '../types';
import { SupervisionCanvas } from './SupervisionCanvas';

/**
 * Who decides, who this supervisor reports to and who reports to it, the workflows it owns and the
 * nodes in them. A workflow, node or supervisor opens on a click; "+N more" opens the Owns tab.
 */
export function SupervisionMap({
  supervisor,
  all,
  onSelect,
  onMore,
}: {
  supervisor: SupervisorView;
  all: SupervisorView[];
  onSelect: (id: string) => void;
  onMore: () => void;
}) {
  const navigate = useNavigate();
  const types = useSupervisorTypes();
  const { targets, ownedNodes } = useOwnedNodes(supervisor.id);
  const maker = decisionMaker(supervisor.type, types.data);
  // dagre on a handful of boxes is cheap, so it runs on every render
  const graph = supervisionGraph({
    supervisor,
    all,
    maker: {
      name: maker.name,
      detail: supervisor.type,
      icon: maker.icon,
      color: maker.color,
    },
    targets,
    ownedNodes,
  });
  const onClick = (node: (typeof graph.nodes)[number]) => {
    if (node.supervisor) onSelect(node.supervisor);
    else if (node.href) void navigate(node.href);
    else if (node.kind === 'more') onMore();
  };
  return (
    <SupervisionCanvas
      label="Supervision map"
      nodes={graph.nodes}
      edges={graph.edges}
      onNodeClick={onClick}
      className="h-96"
    />
  );
}
