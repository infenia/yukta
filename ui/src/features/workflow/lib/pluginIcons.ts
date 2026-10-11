// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { PluginCategory } from '../types/workflow';

/** Material Symbols name per plugin type; add new plugins here and run `pnpm run icons`. */
const PLUGIN_ICONS: Record<string, string> = {
  API_TRIGGER: 'api',
  SCHEDULE_TRIGGER: 'schedule',
  CONSTANT_SOURCE: 'data_object',
  BRANCH: 'call_split',
  SPLITTER: 'content_cut',
  AGGREGATE: 'merge',
  RECIPIENT_LIST: 'groups',
  FILTER: 'filter_alt',
  'CONTENT-FILTER': 'filter_alt',
  VALIDATE: 'verified',
  MAPPER: 'swap_horiz',
  DELAY: 'timer',
  LOOP_PREDICATE: 'repeat',
  LOOP_STREAM: 'repeat_on',
  KV_STORE: 'database',
  SESSION_MEMORY: 'memory',
  HTTP_REQUEST: 'http',
  LLM_CALL: 'smart_toy',
  PROCESS_EXECUTOR: 'terminal',
  SQL_QUERY: 'table_chart',
  FILE_READ: 'file_open',
  FILE_WRITE: 'save_as',
  CONSOLE_TERMINAL: 'terminal',
  NOTIFY_TERMINAL: 'notifications',
};

const CATEGORY_ICONS: Record<PluginCategory, string> = {
  TRIGGER: 'input',
  PROCESSOR: 'settings',
  TERMINAL: 'flag',
};

/** Icon for a plugin type, falling back to its category, then a generic plugin glyph. */
export function pluginIcon(type: string, category: PluginCategory | null): string {
  return PLUGIN_ICONS[type] ?? (category ? CATEGORY_ICONS[category] : 'extension');
}

/** "CONSTANT_SOURCE" -> "Constant Source". */
export function pluginLabel(type: string): string {
  return type
    .split(/[_-]/)
    .map((w) => w.charAt(0) + w.slice(1).toLowerCase())
    .join(' ');
}
