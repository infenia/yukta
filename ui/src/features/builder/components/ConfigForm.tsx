// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import {
  NativeSelect,
  inputFieldClassName,
  textareaFieldClassName,
} from '@/components/ui/native-select';
import type { ConfigField } from '@/features/plugins/types';

type Values = Record<string, unknown>;

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

function FieldInput({
  field,
  value,
  onChange,
}: {
  field: ConfigField;
  value: unknown;
  onChange: (value: unknown) => void;
}) {
  const id = `cfg-${field.name}`;
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
        <NativeSelect
          id={id}
          value={typeof value === 'string' ? value : ''}
          onChange={(event) => onChange(event.target.value || undefined)}
        >
          <option value="">
            {field.defaultValue ? `Default (${field.defaultValue})` : '—'}
          </option>
          {field.options.map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </NativeSelect>
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
    case 'MAP':
      return <JsonField id={id} value={value} onChange={onChange} />;
    case 'EXPRESSION':
      return (
        <textarea
          id={id}
          rows={2}
          spellCheck={false}
          className={TEXTAREA}
          value={typeof value === 'string' ? value : ''}
          onChange={(event) => onChange(event.target.value || undefined)}
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
}: {
  schema: ConfigField[];
  values: Values;
  onChange: (values: Values) => void;
}) {
  const set = (name: string, value: unknown) => {
    // An unset field is dropped so the plugin default applies.
    onChange(
      Object.fromEntries(
        Object.entries({ ...values, [name]: value }).filter(
          ([, v]) => v !== undefined
        )
      )
    );
  };

  return (
    <div className="flex flex-col gap-3">
      {schema.map((field) => (
        <div key={field.name} className="flex flex-col gap-1">
          <label
            htmlFor={`cfg-${field.name}`}
            className="text-label-sm text-on-surface font-semibold"
          >
            {field.name}
            {field.required && <span className="text-error"> *</span>}
          </label>
          <FieldInput
            field={field}
            value={values[field.name]}
            onChange={(value) => set(field.name, value)}
          />
          {field.description && (
            <span className="text-body-sm text-on-surface-variant">
              {field.description}
            </span>
          )}
        </div>
      ))}
    </div>
  );
}
