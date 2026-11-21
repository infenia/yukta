// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { ReferencePicker } from './ReferencePicker';
import type { StepReference } from '../lib/testPins';
import { Select } from '@/components/ui/select';
import { useContext, useEffect, useId, useRef, useState } from 'react';
import {
  inputFieldClassName,
  textareaFieldClassName,
} from '@/components/ui/native-select';
import type { ConfigField } from '@/features/plugins/types';
import {
  JsonValidityContext,
  defaultHint,
  humanize,
  isMissing,
} from './ConfigForm.helpers';

type Values = Record<string, unknown>;

/** The values without the unset ones, so a plugin's or the engine's default applies. */
const compact = (values: Values): Values =>
  Object.fromEntries(Object.entries(values).filter(([, v]) => v !== undefined));

const INPUT = inputFieldClassName;
const TEXTAREA = `${textareaFieldClassName} text-code-sm`;

/** LIST and MAP values are edited as JSON; the draft text is kept until it parses. */
export function JsonField({
  id,
  value,
  onChange,
}: {
  id: string;
  value: unknown;
  onChange: (value: unknown) => void;
}) {
  const [text, setText] = useState(
    value === undefined ? '' : JSON.stringify(value, null, 2)
  );
  const [error, setError] = useState(false);
  const report = useContext(JsonValidityContext);
  const key = useId();
  useEffect(() => {
    report(key, error);
  }, [report, key, error]);
  useEffect(() => () => report(key, false), [report, key]);

  return (
    <>
      <textarea
        id={id}
        rows={3}
        spellCheck={false}
        value={text}
        aria-invalid={error}
        className={TEXTAREA}
        onChange={(event) => {
          setText(event.target.value);
          if (!event.target.value.trim()) {
            setError(false);
            onChange(undefined);
            return;
          }
          try {
            onChange(JSON.parse(event.target.value));
            setError(false);
          } catch {
            setError(true);
          }
        }}
      />
      {error && <span className="text-body-sm text-error">Not valid JSON</span>}
    </>
  );
}

const isStrings = (value: unknown): value is string[] =>
  Array.isArray(value) && value.every((item) => typeof item === 'string');

/** A list of strings as removable chips plus an add box; "Edit as JSON" for anything else. */
function ListField({
  id,
  value,
  onChange,
}: {
  id: string;
  value: unknown;
  onChange: (value: unknown) => void;
}) {
  const [asJson, setAsJson] = useState(
    value !== undefined && !isStrings(value)
  );
  const [draft, setDraft] = useState('');
  if (asJson) {
    return <JsonField id={id} value={value} onChange={onChange} />;
  }
  const items = isStrings(value) ? value : [];
  const add = () => {
    if (draft.trim()) onChange([...items, draft.trim()]);
    setDraft('');
  };
  return (
    <div className="flex flex-col gap-2">
      {items.length > 0 && (
        <ul className="flex flex-wrap gap-1">
          {items.map((item, index) => (
            <li
              key={index}
              className="bg-muted text-code-sm flex items-center gap-1 rounded-full border px-2 py-0.5"
            >
              {item}
              <button
                type="button"
                aria-label={`Remove ${item}`}
                onClick={() => {
                  const next = items.filter((_, i) => i !== index);
                  onChange(next.length > 0 ? next : undefined);
                }}
              >
                ×
              </button>
            </li>
          ))}
        </ul>
      )}
      <input
        id={id}
        type="text"
        className={INPUT}
        placeholder="Add an item, then press Enter"
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        onBlur={add}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault();
            add();
          }
        }}
      />
      <button
        type="button"
        className="text-body-sm text-on-surface-variant self-start underline"
        onClick={() => setAsJson(true)}
      >
        Edit as JSON
      </button>
    </div>
  );
}

const isObject = (value: unknown): value is Values =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

const isStringMap = (value: unknown): value is Record<string, string> =>
  isObject(value) && Object.values(value).every((v) => typeof v === 'string');

/** A map of strings as rows of key and value; "Edit as JSON" for anything else. */
function MapField({
  id,
  value,
  onChange,
}: {
  id: string;
  value: unknown;
  onChange: (value: unknown) => void;
}) {
  const [asJson, setAsJson] = useState(
    value !== undefined && !isStringMap(value)
  );
  const [rows, setRows] = useState<[string, string][]>(
    isStringMap(value) ? Object.entries(value) : []
  );
  if (asJson) {
    return <JsonField id={id} value={value} onChange={onChange} />;
  }
  // A row without a key is kept on screen but not saved
  const commit = (next: [string, string][]) => {
    setRows(next);
    const named = next.filter(([key]) => key.trim() !== '');
    onChange(named.length > 0 ? Object.fromEntries(named) : undefined);
  };
  return (
    <div className="flex flex-col gap-2">
      {rows.map(([key, val], index) => (
        <div key={index} className="flex items-center gap-2">
          <input
            id={index === 0 ? id : undefined}
            aria-label={`Key ${index + 1}`}
            className={INPUT}
            placeholder="Key"
            value={key}
            onChange={(event) =>
              commit(
                rows.map((row, i) =>
                  i === index ? [event.target.value, row[1]] : row
                )
              )
            }
          />
          <input
            aria-label={`Value ${index + 1}`}
            className={INPUT}
            placeholder="Value"
            value={val}
            onChange={(event) =>
              commit(
                rows.map((row, i) =>
                  i === index ? [row[0], event.target.value] : row
                )
              )
            }
          />
          <button
            type="button"
            aria-label={`Remove row ${index + 1}`}
            onClick={() => commit(rows.filter((_, i) => i !== index))}
          >
            ×
          </button>
        </div>
      ))}
      <div className="flex gap-3">
        <button
          type="button"
          className="text-label-md text-primary hover:underline"
          onClick={() => setRows([...rows, ['', '']])}
        >
          Add a row
        </button>
        <button
          type="button"
          className="text-body-sm text-on-surface-variant underline"
          onClick={() => setAsJson(true)}
        >
          Edit as JSON
        </button>
      </div>
    </div>
  );
}

/** A map whose keys are known: one input per key; keys it does not show are kept as they are. */
function GroupField({
  id,
  fields,
  value,
  onChange,
}: {
  id: string;
  fields: ConfigField[];
  value: unknown;
  onChange: (value: unknown) => void;
}) {
  const current = isObject(value) ? value : {};
  return (
    <div className="border-border flex flex-col gap-2 rounded-lg border p-3">
      {fields.map((sub) => (
        <div key={sub.name} className="flex flex-col gap-1">
          <label
            htmlFor={`${id}-${sub.name}`}
            className="text-label-sm text-on-surface"
          >
            {humanize(sub.name)}
          </label>
          <FieldInput
            id={`${id}-${sub.name}`}
            field={sub}
            value={current[sub.name]}
            onChange={(next) => {
              const merged = compact({ ...current, [sub.name]: next });
              onChange(Object.keys(merged).length > 0 ? merged : undefined);
            }}
          />
          {sub.description && (
            <span className="text-body-sm text-on-surface-variant">
              {sub.description}
            </span>
          )}
        </div>
      ))}
    </div>
  );
}

/** An expression box; the picker inserts `payload.<field>` where the caret is. */
function ExpressionField({
  id,
  value,
  onChange,
  references,
}: {
  id: string;
  value: unknown;
  onChange: (value: unknown) => void;
  references?: StepReference[];
}) {
  const box = useRef<HTMLTextAreaElement>(null);
  const text = typeof value === 'string' ? value : '';
  const insert = (expression: string) => {
    const { selectionStart: start, selectionEnd: end } =
      box.current as HTMLTextAreaElement;
    onChange(`${text.slice(0, start)}${expression}${text.slice(end)}`);
  };
  return (
    <>
      <textarea
        ref={box}
        id={id}
        rows={2}
        spellCheck={false}
        className={TEXTAREA}
        value={text}
        onChange={(event) => onChange(event.target.value || undefined)}
      />
      {references && references.length > 0 && (
        <ReferencePicker references={references} onPick={insert} />
      )}
      <span className="text-body-sm text-on-surface-variant">
        An expression on the message, such as{' '}
        <code className="text-code-sm">payload.x</code> or{' '}
        <code className="text-code-sm">metadata.x</code>.
      </span>
    </>
  );
}

function FieldInput({
  id,
  field,
  value,
  onChange,
  references,
}: {
  id: string;
  references?: StepReference[];
  field: ConfigField;
  value: unknown;
  onChange: (value: unknown) => void;
}) {
  switch (field.type) {
    case 'BOOLEAN':
      return (
        <input
          id={id}
          type="checkbox"
          checked={
            value === undefined ? field.defaultValue === true : value === true
          }
          onChange={(event) => onChange(event.target.checked)}
        />
      );
    case 'ENUM':
      return (
        <Select
          id={id}
          searchable={field.options.length > 8}
          value={typeof value === 'string' ? value : ''}
          onValueChange={(next) => onChange(next || undefined)}
          options={[
            {
              value: '',
              label: field.defaultValue
                ? `Default (${field.defaultValue})`
                : '—',
            },
            ...field.options.map((option) => ({
              value: option,
              label: option,
            })),
          ]}
        />
      );
    case 'NUMBER':
      return (
        <input
          id={id}
          type="number"
          className={INPUT}
          placeholder={
            field.defaultValue == null ? undefined : String(field.defaultValue)
          }
          value={typeof value === 'number' ? value : ''}
          onChange={(event) =>
            onChange(
              event.target.value === '' ? undefined : Number(event.target.value)
            )
          }
        />
      );
    case 'LIST':
      return <ListField id={id} value={value} onChange={onChange} />;
    case 'MAP':
      return field.fields ? (
        <GroupField
          id={id}
          fields={field.fields}
          value={value}
          onChange={onChange}
        />
      ) : (
        <MapField id={id} value={value} onChange={onChange} />
      );
    case 'EXPRESSION':
      return (
        <ExpressionField
          id={id}
          value={value}
          onChange={onChange}
          references={references}
        />
      );
    default:
      return (
        <input
          id={id}
          type="text"
          className={INPUT}
          placeholder={field.type === 'SECRET' ? 'decrypted:<key>' : undefined}
          value={typeof value === 'string' ? value : ''}
          onChange={(event) => onChange(event.target.value || undefined)}
        />
      );
  }
}

/** Renders a plugin's declared config fields; unset fields are removed so defaults apply. */
export function ConfigForm({
  schema,
  values,
  onChange,
  references,
}: {
  /** The steps before this one: expression fields offer their output. */
  references?: StepReference[];
  schema: ConfigField[];
  values: Values;
  onChange: (values: Values) => void;
}) {
  // Scoped per form: NodeInspector renders two, and field names can repeat across them.
  const scope = useId();
  // A required field is flagged once the user has been in it, not on a form just opened
  const [touched, setTouched] = useState<ReadonlySet<string>>(new Set());
  const set = (name: string, value: unknown) => {
    // An unset field is dropped so the plugin default applies.
    onChange(compact({ ...values, [name]: value }));
  };

  return (
    <div className="flex flex-col gap-3">
      {schema.map((field) => (
        <div
          key={field.name}
          className="flex flex-col gap-1"
          onBlur={() =>
            setTouched((current) => new Set(current).add(field.name))
          }
        >
          <div className="flex items-baseline gap-2">
            <label
              htmlFor={`${scope}-${field.name}`}
              className="text-label-sm text-on-surface font-semibold"
            >
              {humanize(field.name)}
              {field.required && <span className="text-error"> *</span>}
            </label>
            {/* the key as the docs and the JSON spell it, when the label is not it */}
            {humanize(field.name).toLowerCase() !==
              field.name.toLowerCase() && (
              <code className="text-code-sm text-muted-foreground">
                {field.name}
              </code>
            )}
          </div>
          <FieldInput
            id={`${scope}-${field.name}`}
            field={field}
            value={values[field.name]}
            onChange={(value) => set(field.name, value)}
            references={references}
          />
          {touched.has(field.name) && isMissing(field, values[field.name]) && (
            <span className="text-body-sm text-error">
              {field.name} is required.
            </span>
          )}
          {field.description && (
            <span className="text-body-sm text-on-surface-variant">
              {field.description}
            </span>
          )}
          {/* a select already says its default in its first option */}
          {field.type !== 'ENUM' && defaultHint(field) && (
            <span className="text-body-sm text-muted-foreground">
              {defaultHint(field)}
            </span>
          )}
        </div>
      ))}
    </div>
  );
}
