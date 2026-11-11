// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useContext } from 'react';
import { Tip } from '@/components/ui/tooltip';
import { ownerLabel } from '@/features/supervisors/lib/ownership';
import { cn } from '@/lib/utils';
import { NodeOwnerOpener } from '../../hooks/useNodeSupervisors';

/**
 * Who controls a node: a supervisor's shield, or a person for the default owner. Where a drawer
 * can show it, a click opens the node's Supervisor tab; elsewhere it is only a label.
 */
export function NodeOwner({
  nodeId,
  owner,
  className,
}: {
  nodeId: string;
  owner: string;
  className?: string;
}) {
  const open = useContext(NodeOwnerOpener);
  const label = `Controlled by: ${ownerLabel(owner)}`;
  const icon = (
    <span aria-hidden="true" className="material-symbols-outlined text-base">
      {owner === 'default' ? 'person' : 'shield_person'}
    </span>
  );
  const base =
    'text-on-surface-variant inline-flex items-center justify-center rounded-full';
  return (
    <Tip label={label}>
      {open ? (
        <button
          type="button"
          aria-label={label}
          // the canvas opens the node's drawer on a node click; this opens its Supervisor tab
          onClick={(event) => {
            event.stopPropagation();
            open(nodeId);
          }}
          className={cn(
            base,
            'nodrag hover:bg-muted hover:text-primary focus-visible:ring-ring transition-colors focus-visible:ring-2 focus-visible:outline-none',
            className
          )}
        >
          {icon}
        </button>
      ) : (
        <span role="img" aria-label={label} className={cn(base, className)}>
          {icon}
        </span>
      )}
    </Tip>
  );
}
