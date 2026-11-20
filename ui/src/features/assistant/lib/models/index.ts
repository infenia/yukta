// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ChatModel } from '../../types';
import type { Settings } from '../settings';
import { anthropicModel } from './anthropic';
import { openAiModel } from './openai';

/** The model the settings and key describe, or null while one of them is missing. */
export function buildModel(
  settings: Settings,
  key: string | null
): ChatModel | null {
  if (!key || !settings.model) return null;
  return settings.provider === 'openai'
    ? openAiModel({
        key,
        model: settings.model,
        baseUrl: settings.baseUrl || undefined,
      })
    : anthropicModel({ key, model: settings.model });
}
