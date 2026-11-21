// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { cn } from '@/lib/utils';

/*
 * Native form control classes styled like the shadcn Input, so textareas and plain inputs
 * outside the primitives match it (dropdowns use `Select`): soft filled surface, transparent hairline that turns to the
 * ring colour on focus, red when aria-invalid.
 */
const fieldClassName =
  'bg-input/50 text-on-surface placeholder:text-muted-foreground text-body-md transition-standard w-full min-w-0 rounded-2xl border border-transparent px-2.5 outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/30 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 disabled:cursor-not-allowed disabled:opacity-50';

/** Single-line fields (input, select): the Input height. */
const inputFieldClassName = cn(fieldClassName, 'h-8 py-1');

/** Multi-line fields (textarea): a smaller radius so the corners suit several lines. */
const textareaFieldClassName = cn(fieldClassName, 'rounded-xl py-1.5');

export { inputFieldClassName, textareaFieldClassName };
