// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import type { ReactNode } from 'react';

/**
 * Render children into a layout slot (e.g. the app header action cluster) by element id.
 *
 * Pages use this to place route-specific controls into the global chrome without
 * coupling the chrome to any particular route.
 */
export default function HeaderPortal({
  targetId,
  children,
}: {
  targetId: string;
  children: ReactNode;
}) {
  const [target, setTarget] = useState<HTMLElement | null>(null);

  useEffect(() => {
    // The target element only exists after the layout commits, so it must be read in an
    // effect; the update settles in the same commit and cannot cascade.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setTarget(document.getElementById(targetId));
  }, [targetId]);

  if (!target) {
    return null;
  }
  return createPortal(children, target);
}
