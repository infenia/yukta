// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ChatModel } from '../../types';
import type { Settings } from '../settings';
import { anthropicModel } from './anthropic';
import { nanoModel, type NanoState } from './nano';
import { openAiModel } from './openai';

/**
 * The model the settings describe, or null while something is missing: the key and model name
 * for a provider, a finished download for the built-in model.
 */
export function buildModel(
  settings: Settings,
  key: string | null,
  nano: NanoState = 'unsupported'
): ChatModel | null {
  if (settings.provider === 'nano') {
    return nano === 'available' ? nanoModel() : null;
  }
  if (!key || !settings.model) return null;
  return settings.provider === 'openai'
    ? openAiModel({
        key,
        model: settings.model,
        baseUrl: settings.baseUrl || undefined,
      })
    : anthropicModel({ key, model: settings.model });
}
