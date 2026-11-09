// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { cloneElement, type MouseEvent, type ReactElement } from 'react';
import { RequirePermission } from '@/components/common/RequirePermission';
import type { Permission } from '@/lib/identity';
import type { ControlTarget } from '../api/supervision';
import { useSupervision } from '../hooks/useSupervisorTypes';

interface Gated {
  disabled?: boolean;
  title?: string;
  onClick?: (event: MouseEvent) => void;
}

/** The words of a refusal when the server gives no reason. */
export const controlledBy = (via: string) =>
  via === 'default'
    ? 'No supervisor can do this here'
    : `Controlled by ${via}. People cannot do this directly.`;

/**
 * Disables its child when the key's role may not do it (as `RequirePermission`), or when the
 * supervisor that owns the target does not let people send this command (`GET /api/supervision`).
 * The reason is the tooltip and the accessible description. A server without that endpoint, or
 * one still loading, leaves only the role check.
 */
export function RequireControl({
  perm,
  command,
  target,
  children,
}: {
  perm: Permission;
  /** The capability name: start, pause, resume, stop, restart, priority, recover, pauseNode, … */
  command: string;
  target: ControlTarget;
  children: ReactElement<Gated>;
}) {
  const { data } = useSupervision(target);
  const capability = data?.[command];
  if (capability && !capability.allowed) {
    const reason = capability.reason ?? controlledBy(capability.via);
    return cloneElement(children, {
      disabled: true,
      title: reason,
      'aria-disabled': true,
      'aria-description': reason,
      onClick: (event: MouseEvent) => event.preventDefault(),
    } as Gated);
  }
  return <RequirePermission perm={perm}>{children}</RequirePermission>;
}
