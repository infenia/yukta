// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

'use client';

import { Combobox as ComboboxPrimitive } from '@base-ui/react/combobox';
import { Select as SelectPrimitive } from '@base-ui/react/select';
import { cn } from '@/lib/utils';
import { Tip } from '@/components/ui/tooltip';

export interface SelectOption {
  value: string;
  label: string;
  /** listed under this heading; ungrouped options come first */
  group?: string;
}

interface SelectProps {
  value: string;
  onValueChange: (value: string) => void;
  options: readonly SelectOption[];
  /** Omit when a `<label htmlFor={id}>` names it. */
  ariaLabel?: string;
  /** For a `<label htmlFor>`. */
  id?: string;
  placeholder?: string;
  disabled?: boolean;
  /** A search field in the popup: use it for long or dynamic lists. */
  searchable?: boolean;
  className?: string;
}

const triggerClass =
  'bg-input/50 text-on-surface text-body-md transition-standard hover:bg-input/80 focus-visible:border-ring focus-visible:ring-ring/30 data-popup-open:border-ring data-popup-open:ring-ring/30 flex h-8 min-w-0 cursor-pointer items-center justify-between gap-2 rounded-2xl border border-transparent pr-2 pl-3 outline-none focus-visible:ring-3 data-popup-open:ring-3 data-disabled:cursor-not-allowed data-disabled:opacity-50';
const popupClass =
  'bg-popover text-popover-foreground ring-foreground/5 dark:ring-foreground/10 data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95 flex max-h-(--available-height) min-w-(--anchor-width) origin-(--transform-origin) flex-col overflow-hidden rounded-2xl shadow-lg ring-1 duration-100 outline-none';
const listClass = 'min-h-0 flex-1 overflow-y-auto p-1';
const itemClass =
  'text-body-md transition-standard data-highlighted:bg-muted flex cursor-pointer items-center gap-2 rounded-xl py-1.5 pr-3 pl-2 outline-none select-none [&[data-selected]]:font-medium';
const groupLabelClass =
  'text-muted-foreground px-2 pt-2 pb-1 text-xs font-semibold tracking-wide uppercase';
const checkClass =
  'material-symbols-outlined text-primary invisible w-4 shrink-0 text-base [&[data-selected]]:visible';
const chevronClass =
  'material-symbols-outlined text-on-surface-variant text-base transition-transform data-popup-open:rotate-180';

/** Ungrouped options first, then one entry per group in first-seen order. */
function grouped(options: readonly SelectOption[]) {
  const names = [
    ...new Set(options.flatMap((o) => (o.group ? [o.group] : []))),
  ];
  return {
    plain: options.filter((o) => !o.group),
    groups: names.map((name) => ({
      name,
      items: options.filter((o) => o.group === name),
    })),
  };
}

/**
 * A themed dropdown: the Input look on the trigger, a rounded popup with a check on the chosen
 * item, the same in every browser and in light and dark. A short fixed list is a plain select;
 * `searchable` adds a filter field for long or dynamic lists (Base UI Select and Combobox, the
 * shadcn components).
 */
export function Select(props: SelectProps) {
  return props.searchable ? (
    <SearchableSelect {...props} />
  ) : (
    <PlainSelect {...props} />
  );
}

function PlainSelect({
  value,
  onValueChange,
  options,
  ariaLabel,
  id,
  placeholder,
  disabled,
  className,
}: SelectProps) {
  const { plain, groups } = grouped(options);
  const item = (o: SelectOption) => (
    <SelectPrimitive.Item
      key={o.value}
      value={o.value}
      data-value={o.value}
      className={itemClass}
    >
      <SelectPrimitive.ItemIndicator
        className={checkClass}
        keepMounted
        aria-hidden="true"
      >
        check
      </SelectPrimitive.ItemIndicator>
      <SelectPrimitive.ItemText>{o.label}</SelectPrimitive.ItemText>
    </SelectPrimitive.Item>
  );
  return (
    <SelectPrimitive.Root
      value={value}
      onValueChange={(next) => onValueChange(next ?? '')}
      items={options as SelectOption[]}
      disabled={disabled}
    >
      <SelectPrimitive.Trigger
        id={id}
        aria-label={ariaLabel}
        data-slot="select-trigger"
        data-value={value}
        className={cn(triggerClass, className)}
      >
        <SelectPrimitive.Value className="truncate" placeholder={placeholder} />
        <SelectPrimitive.Icon aria-hidden="true" className={chevronClass}>
          expand_more
        </SelectPrimitive.Icon>
      </SelectPrimitive.Trigger>
      <SelectPrimitive.Portal>
        <SelectPrimitive.Positioner
          sideOffset={6}
          alignItemWithTrigger={false}
          className="isolate z-70 outline-none"
        >
          <SelectPrimitive.Popup
            data-slot="select-content"
            className={popupClass}
          >
            <SelectPrimitive.List className={listClass}>
              {plain.map(item)}
              {groups.map((g) => (
                <SelectPrimitive.Group key={g.name}>
                  <SelectPrimitive.GroupLabel className={groupLabelClass}>
                    {g.name}
                  </SelectPrimitive.GroupLabel>
                  {g.items.map(item)}
                </SelectPrimitive.Group>
              ))}
            </SelectPrimitive.List>
          </SelectPrimitive.Popup>
        </SelectPrimitive.Positioner>
      </SelectPrimitive.Portal>
    </SelectPrimitive.Root>
  );
}

function SearchableSelect({
  value,
  onValueChange,
  options,
  ariaLabel,
  id,
  placeholder,
  disabled,
  className,
}: SelectProps) {
  const { plain, groups } = grouped(options);
  // Base UI filters only items rendered through a function: ungrouped options are a nameless first section
  const sections = [
    ...(plain.length ? [{ value: '', items: plain }] : []),
    ...groups.map((g) => ({ value: g.name, items: g.items })),
  ];
  const selected = options.find((o) => o.value === value) ?? null;
  const item = (o: SelectOption) => (
    <ComboboxPrimitive.Item
      key={o.value}
      value={o}
      data-value={o.value}
      className={itemClass}
    >
      <ComboboxPrimitive.ItemIndicator
        className={checkClass}
        keepMounted
        aria-hidden="true"
      >
        check
      </ComboboxPrimitive.ItemIndicator>
      {o.label}
    </ComboboxPrimitive.Item>
  );
  return (
    <ComboboxPrimitive.Root
      items={sections}
      value={selected}
      onValueChange={(next: SelectOption | null) =>
        onValueChange(next?.value ?? '')
      }
      isItemEqualToValue={(a: SelectOption, b: SelectOption) =>
        a.value === b.value
      }
      disabled={disabled}
    >
      <ComboboxPrimitive.Trigger
        id={id}
        aria-label={ariaLabel}
        data-slot="select-trigger"
        data-value={value}
        className={cn(triggerClass, className)}
      >
        <span className={cn('truncate', !selected && 'text-muted-foreground')}>
          {selected?.label ?? placeholder ?? ''}
        </span>
        <ComboboxPrimitive.Icon aria-hidden="true" className={chevronClass}>
          expand_more
        </ComboboxPrimitive.Icon>
      </ComboboxPrimitive.Trigger>
      <ComboboxPrimitive.Portal>
        <ComboboxPrimitive.Positioner
          sideOffset={6}
          className="isolate z-70 outline-none"
        >
          <ComboboxPrimitive.Popup
            data-slot="select-content"
            className={popupClass}
          >
            <div className="border-border flex items-center gap-2 border-b px-3">
              <span
                aria-hidden="true"
                className="material-symbols-outlined text-on-surface-variant text-base"
              >
                search
              </span>
              <ComboboxPrimitive.Input
                aria-label={`Search ${ariaLabel ?? 'options'}`}
                placeholder="Search…"
                className="text-body-md placeholder:text-muted-foreground h-9 w-full bg-transparent outline-none"
              />
            </div>
            <ComboboxPrimitive.Empty className="text-body-sm text-muted-foreground p-3 empty:hidden">
              No matches
            </ComboboxPrimitive.Empty>
            <ComboboxPrimitive.List className={listClass}>
              {(section: (typeof sections)[number]) => (
                <ComboboxPrimitive.Group
                  key={section.value}
                  items={section.items}
                >
                  {section.value && (
                    <ComboboxPrimitive.GroupLabel className={groupLabelClass}>
                      {section.value}
                    </ComboboxPrimitive.GroupLabel>
                  )}
                  <ComboboxPrimitive.Collection>
                    {item}
                  </ComboboxPrimitive.Collection>
                </ComboboxPrimitive.Group>
              )}
            </ComboboxPrimitive.List>
          </ComboboxPrimitive.Popup>
        </ComboboxPrimitive.Positioner>
      </ComboboxPrimitive.Portal>
    </ComboboxPrimitive.Root>
  );
}

interface MultiSelectProps {
  values: readonly string[];
  onValuesChange: (values: string[]) => void;
  options: readonly SelectOption[];
  /** Shown while nothing is chosen, for example "Any status". */
  placeholder: string;
  ariaLabel?: string;
  disabled?: boolean;
  className?: string;
}

/** What the trigger says: one name, a few names, or a count when they would not fit. */
function summary(labels: string[], placeholder: string): string {
  if (labels.length === 0) return placeholder;
  if (labels.length === 1) return labels[0];
  return labels.join(', ').length <= 22
    ? labels.join(', ')
    : `${labels.length} selected`;
}

/**
 * A themed dropdown that keeps several items chosen: the popup stays open while you pick, each
 * chosen item carries a check, "Select all" and "Clear" sit under the list, and the trigger names
 * what is chosen (a count when long, the full list in a tooltip) with a button to clear it.
 */
export function MultiSelect({
  values,
  onValuesChange,
  options,
  placeholder,
  ariaLabel,
  disabled,
  className,
}: MultiSelectProps) {
  const labels = values.map(
    (value) => options.find((o) => o.value === value)?.label ?? value
  );
  const all = options.map((o) => o.value);
  return (
    <div className="relative inline-flex">
      <SelectPrimitive.Root
        multiple
        value={[...values]}
        onValueChange={(next: string[]) => onValuesChange(next)}
        items={options as SelectOption[]}
        disabled={disabled}
      >
        {/* always a tooltip, so the trigger is never re-created: the choice, or the filter's name */}
        <Tip label={labels.join(', ') || ariaLabel || placeholder}>
          <SelectPrimitive.Trigger
            aria-label={ariaLabel}
            data-slot="select-trigger"
            data-value={values.join(',')}
            className={cn(triggerClass, values.length && 'pr-14', className)}
          >
            <span className="truncate">{summary(labels, placeholder)}</span>
            <SelectPrimitive.Icon aria-hidden="true" className={chevronClass}>
              expand_more
            </SelectPrimitive.Icon>
          </SelectPrimitive.Trigger>
        </Tip>
        <SelectPrimitive.Portal>
          <SelectPrimitive.Positioner
            sideOffset={6}
            alignItemWithTrigger={false}
            className="isolate z-70 outline-none"
          >
            <SelectPrimitive.Popup
              data-slot="select-content"
              className={popupClass}
            >
              <SelectPrimitive.List className={listClass}>
                {options.map((o) => (
                  <SelectPrimitive.Item
                    key={o.value}
                    value={o.value}
                    data-value={o.value}
                    className={itemClass}
                  >
                    <SelectPrimitive.ItemIndicator
                      className={checkClass}
                      keepMounted
                      aria-hidden="true"
                    >
                      check
                    </SelectPrimitive.ItemIndicator>
                    <SelectPrimitive.ItemText>
                      {o.label}
                    </SelectPrimitive.ItemText>
                  </SelectPrimitive.Item>
                ))}
              </SelectPrimitive.List>
              <div className="border-border text-label-md flex items-center justify-between border-t px-2 py-1.5">
                <button
                  type="button"
                  disabled={values.length === all.length}
                  onClick={() => onValuesChange(all)}
                  className="text-primary hover:bg-muted cursor-pointer rounded-lg px-2 py-1 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  Select all
                </button>
                <button
                  type="button"
                  disabled={values.length === 0}
                  onClick={() => onValuesChange([])}
                  className="text-on-surface-variant hover:bg-muted cursor-pointer rounded-lg px-2 py-1 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  Clear
                </button>
              </div>
            </SelectPrimitive.Popup>
          </SelectPrimitive.Positioner>
        </SelectPrimitive.Portal>
      </SelectPrimitive.Root>
      {values.length > 0 && !disabled && (
        <button
          type="button"
          aria-label={`Clear ${ariaLabel ?? 'selection'}`}
          onClick={() => onValuesChange([])}
          className="text-on-surface-variant hover:bg-muted hover:text-on-surface absolute top-1/2 right-7 flex size-5 -translate-y-1/2 cursor-pointer items-center justify-center rounded-full"
        >
          <span
            aria-hidden="true"
            className="material-symbols-outlined text-base"
          >
            close
          </span>
        </button>
      )}
    </div>
  );
}
