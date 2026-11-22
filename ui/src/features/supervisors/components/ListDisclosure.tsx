// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect, useState, type RefObject } from 'react';

/**
 * Phone behaviour of a master-detail page. Once something is chosen the list folds away so the
 * detail is on screen, with a button to open it again; choosing an item closes the list and moves
 * focus to the detail's heading. There is one list in the page: CSS (`max-md:hidden`) hides it, so
 * nothing is rendered twice.
 *
 * The page owns `detailRef` and puts it on the detail element; the hook only reads it in an effect.
 * A detail that loads before it has a heading passes `ready` as false until it has one.
 */
export function useListDisclosure(
  selection: string | null,
  detailRef: RefObject<HTMLDivElement | null>,
  ready = true
) {
  const [open, setOpen] = useState(false);
  // The selection a person picked from: once the selection changes, the new detail takes focus.
  const [pending, setPending] = useState<{ from: string | null } | null>(null);
  useEffect(() => {
    if (pending === null || pending.from === selection || !ready) return;
    const heading = detailRef.current?.querySelector('h2');
    if (!heading) return;
    heading.setAttribute('tabindex', '-1');
    heading.focus();
    heading.scrollIntoView?.({ block: 'start' });
    setPending(null);
  }, [pending, selection, detailRef, ready]);
  return {
    open,
    toggle: () => setOpen((now) => !now),
    /** Called when a person picks an item: folds the list and moves focus to the detail. */
    onPick: () => {
      setPending({ from: selection });
      setOpen(false);
    },
    /** Hidden on a phone while a choice is made and the list is folded. */
    listClass: selection && !open ? 'max-md:hidden' : undefined,
  };
}

/** The button of a folded list; only phones show it. It sits outside the list's own `nav`. */
export function ListToggle({
  label,
  open,
  controls,
  onToggle,
}: {
  label: string;
  open: boolean;
  controls: string;
  onToggle: () => void;
}) {
  return (
    <button
      type="button"
      aria-expanded={open}
      aria-controls={controls}
      onClick={onToggle}
      className="border-border bg-card text-label-md shadow-card flex items-center justify-between gap-2 rounded-lg border px-3 py-2 md:hidden"
    >
      {label}
      <span aria-hidden="true" className="material-symbols-outlined text-lg">
        {open ? 'expand_less' : 'expand_more'}
      </span>
    </button>
  );
}
