// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { PluginCategory, UiDesign } from '@/features/workflow/types/workflow';

export interface PluginSummary {
  type: string;
  category: PluginCategory;
}

export interface PluginDetails extends PluginSummary {
  description: string | null;
  usagePattern: string | null;
  uiDesign: UiDesign | null;
  outputPorts: string[];
}
