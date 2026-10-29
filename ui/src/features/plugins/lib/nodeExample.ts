// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { DraftNode } from '@/features/builder/types';
import type { ConfigField, ConfigFieldType, PluginDetails } from '../types';

/** What a required field without a default starts as, so the example parses and shows the shape. */
const EMPTY: Record<ConfigFieldType, unknown> = {
  STRING: '',
  NUMBER: 0,
  BOOLEAN: false,
  ENUM: '',
  LIST: [],
  MAP: {},
  EXPRESSION: '',
  SECRET: '',
};

const initial = (field: ConfigField) =>
  field.defaultValue ??
  (field.type === 'ENUM' && field.options.length > 0
    ? field.options[0]
    : EMPTY[field.type]);

/** A workflow node of this plugin: its required fields and every field that has a default. */
export function nodeExample(plugin: PluginDetails): DraftNode {
  const fields = (plugin.configSchema ?? []).filter(
    (field) => field.required || field.defaultValue != null
  );
  return {
    nodeId: plugin.type.toLowerCase(),
    type: plugin.type,
    config: Object.fromEntries(
      fields.map((field) => [field.name, initial(field)])
    ),
  };
}
