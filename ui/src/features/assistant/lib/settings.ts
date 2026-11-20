// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Provider } from './proxy';

/** Where the model comes from: this device's built-in model, or a provider with the person's key. */
export type ProviderChoice = Provider | 'nano';

/** What the person chose; kept in this browser, never sent to Yukta except as the relay's headers. */
export interface Settings {
  /** The floating button is hidden (the command palette brings it back). */
  hidden: boolean;
  provider: ProviderChoice;
  model: string;
  /** The OpenAI-compatible endpoint; empty means api.openai.com. */
  baseUrl: string;
  /** Keep the key across tabs and restarts; otherwise it ends with the tab. */
  remember: boolean;
}

export const DEFAULT_SETTINGS: Settings = {
  hidden: false,
  provider: 'anthropic',
  model: 'claude-sonnet-5-5',
  baseUrl: '',
  remember: false,
};

const SETTINGS_KEY = 'yukta.assistant';
const KEY_KEY = 'yukta.assistant.key';

/** Storage can be missing or throw (private window, blocked site data): carry on without it. */
function safe<T>(fn: () => T, fallback: T): T {
  try {
    return fn();
  } catch {
    return fallback;
  }
}

/** A browser with the built-in model starts on it; the rest start on a provider. */
const firstChoice = (): Settings => ({
  ...DEFAULT_SETTINGS,
  provider: 'LanguageModel' in globalThis ? 'nano' : DEFAULT_SETTINGS.provider,
});

export const readSettings = (): Settings =>
  safe(
    () => ({
      ...firstChoice(),
      ...JSON.parse(localStorage.getItem(SETTINGS_KEY) ?? '{}'),
    }),
    firstChoice()
  );

export const writeSettings = (settings: Settings): void =>
  safe(
    () => localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings)),
    undefined
  );

/** The provider key: this tab's, else the one kept on this device. */
export const readKey = (): string | null =>
  safe(
    () => sessionStorage.getItem(KEY_KEY) ?? localStorage.getItem(KEY_KEY),
    null
  );

export const writeKey = (key: string, remember: boolean): void =>
  safe(() => {
    sessionStorage.removeItem(KEY_KEY);
    localStorage.removeItem(KEY_KEY);
    (remember ? localStorage : sessionStorage).setItem(KEY_KEY, key);
  }, undefined);
