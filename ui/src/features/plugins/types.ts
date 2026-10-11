// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { PluginCategory, UiDesign } from '@/features/workflow/types/workflow';

export interface PluginSummary {
  type: string;
  category: PluginCategory;
}

export type ConfigFieldType =
  | 'STRING'
  | 'NUMBER'
  | 'BOOLEAN'
  | 'ENUM'
  | 'LIST'
  | 'MAP'
  | 'EXPRESSION'
  | 'SECRET';

export interface ConfigField {
  name: string;
  type: ConfigFieldType;
  required: boolean;
  defaultValue: unknown;
  options: string[];
  description: string;
}

export interface PluginDetails extends PluginSummary {
  description: string | null;
  usagePattern: string | null;
  uiDesign: UiDesign | null;
  outputPorts: string[];
  /** Empty when the plugin declares no schema; callers fall back to a JSON editor. */
  configSchema?: ConfigField[];
}
