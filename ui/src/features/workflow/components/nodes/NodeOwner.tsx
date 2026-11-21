// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useContext } from 'react';
import { Tip } from '@/components/ui/tooltip';
import { ownerLabel } from '@/features/supervisors/lib/ownership';
import { cn } from '@/lib/utils';
import { NodeOwnerOpener } from '../../hooks/useNodeSupervisors';

/**
 * Who controls a node: a supervisor's shield; nothing for direct control. Where a drawer
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
  // direct control is the norm: only a supervisor is worth an icon
  if (owner === 'default') return null;
  const label = `Controlled by: ${ownerLabel(owner)}`;
  const icon = (
    <span aria-hidden="true" className="material-symbols-outlined text-base">
      shield_person
    </span>
  );
  const base =
    'text-primary bg-primary/10 inline-flex items-center justify-center rounded-full';
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
            'nodrag hover:bg-primary/20 focus-visible:ring-ring transition-colors focus-visible:ring-2 focus-visible:outline-none',
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
