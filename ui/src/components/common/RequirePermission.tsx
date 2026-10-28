// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { cloneElement, type MouseEvent, type ReactElement } from 'react';
import { REQUIRED_ROLE, useMe, type Permission } from '@/lib/identity';

interface Gated {
  disabled?: boolean;
  title?: string;
  onClick?: (event: MouseEvent) => void;
}

/**
 * Disables its child, with a "Needs OPERATOR" tooltip, when the caller's key may not do what the
 * child does. Disabled, not hidden, so people learn the feature exists. While the identity is
 * unknown nothing is disabled.
 */
export function RequirePermission({
  perm,
  children,
}: {
  perm: Permission;
  children: ReactElement<Gated>;
}) {
  const me = useMe();
  if (!me || me.permissions[perm]) return children;
  return cloneElement(children, {
    disabled: true,
    title: `Needs ${REQUIRED_ROLE[perm]}`,
    'aria-disabled': true,
    // a link has no disabled state: stop the navigation
    onClick: (event: MouseEvent) => event.preventDefault(),
  } as Gated);
}
