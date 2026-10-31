// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

'use client';

import { Dialog } from '@base-ui/react/dialog';
import { cn } from '@/lib/utils';

function Sheet({ ...props }: Dialog.Root.Props) {
  return <Dialog.Root data-slot="sheet" {...props} />;
}

/**
 * A panel on the right edge, or the bottom edge on a phone. The dialog traps focus, closes on
 * Escape and is named by its SheetTitle; `finalFocus` says where focus goes back to.
 */
function SheetContent({
  className,
  children,
  finalFocus,
  backdrop = true,
  ...props
}: Dialog.Popup.Props & {
  /** False for a non-modal sheet (`Sheet modal={false}`): the page behind stays usable. */
  backdrop?: boolean;
}) {
  return (
    <Dialog.Portal>
      {backdrop && (
        <Dialog.Backdrop className="bg-overlay/50 fixed inset-0 z-60" />
      )}
      <Dialog.Popup
        data-slot="sheet-content"
        finalFocus={finalFocus}
        className={cn(
          'border-border bg-card shadow-raised fixed z-60 flex flex-col gap-3 overflow-y-auto border p-4 outline-none',
          'inset-x-0 bottom-0 max-h-[85dvh] rounded-t-2xl sm:inset-x-auto sm:top-(--spacing-header) sm:right-0 sm:bottom-0 sm:max-h-none sm:w-105 sm:rounded-none sm:border-y-0 sm:border-r-0',
          className
        )}
        {...props}
      >
        {children}
      </Dialog.Popup>
    </Dialog.Portal>
  );
}

function SheetTitle({ className, ...props }: Dialog.Title.Props) {
  return (
    <Dialog.Title
      className={cn('text-headline-md text-on-surface', className)}
      {...props}
    />
  );
}

function SheetClose({ ...props }: Dialog.Close.Props) {
  return <Dialog.Close data-slot="sheet-close" {...props} />;
}

export { Sheet, SheetContent, SheetTitle, SheetClose };
