// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { cn } from '@/lib/utils';

/**
 * A Material icon centred in a box. The icon class sets `display: inline-block` outside the
 * Tailwind layers, so it cannot be the flex container itself: the box wraps it.
 */
export function IconTile({
  icon,
  className,
  iconClassName = 'text-lg',
}: {
  icon: string;
  /** The box: size, shape and tint. */
  className?: string;
  iconClassName?: string;
}) {
  return (
    <span
      aria-hidden="true"
      className={cn('flex shrink-0 items-center justify-center', className)}
    >
      <span className={cn('material-symbols-outlined', iconClassName)}>
        {icon}
      </span>
    </span>
  );
}
