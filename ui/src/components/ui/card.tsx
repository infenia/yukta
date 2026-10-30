// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import * as React from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/utils';

/*
 * The raised surface of the docs theme: a card on a 1px hairline with a soft shadow. The
 * interactive variant (clickable cards) gains a blue-tinted border, a deeper shadow and a
 * 1px lift on hover; reduced motion drops the lift (global guard in index.css).
 */
const cardVariants = cva(
  'group/card flex flex-col rounded-xl border border-border bg-card text-card-foreground shadow-card',
  {
    variants: {
      interactive: {
        true: 'cursor-pointer transition-standard hover:-translate-y-px hover:border-[color-mix(in_oklch,var(--primary)_45%,var(--border))] hover:shadow-raised focus-visible:-translate-y-px',
        false: '',
      },
    },
    defaultVariants: {
      interactive: false,
    },
  }
);

function Card({
  className,
  interactive,
  ...props
}: React.ComponentProps<'div'> & VariantProps<typeof cardVariants>) {
  return (
    <div
      data-slot="card"
      className={cn(cardVariants({ interactive }), className)}
      {...props}
    />
  );
}

function CardHeader({ className, ...props }: React.ComponentProps<'div'>) {
  return (
    <div
      data-slot="card-header"
      className={cn('flex items-center gap-2 px-4 pt-4', className)}
      {...props}
    />
  );
}

function CardTitle({ className, ...props }: React.ComponentProps<'h3'>) {
  return (
    <h3
      data-slot="card-title"
      className={cn('text-overline', className)}
      {...props}
    />
  );
}

function CardContent({ className, ...props }: React.ComponentProps<'div'>) {
  return (
    <div
      data-slot="card-content"
      className={cn('px-4 pt-2 pb-4', className)}
      {...props}
    />
  );
}

export { Card, CardHeader, CardTitle, CardContent, cardVariants };
