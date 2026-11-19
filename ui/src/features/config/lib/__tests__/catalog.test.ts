// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { AREAS, CATALOG, envName } from '../catalog';
import { howToSet } from '../howToSet';

const option = (key: string) => {
  const found = CATALOG.find((o) => o.key === key);
  if (!found) throw new Error(`no catalog option ${key}`);
  return found;
};

describe('catalog', () => {
  it('has unique keys and only known areas', () => {
    const keys = CATALOG.map((o) => o.key);
    expect(new Set(keys).size).toBe(keys.length);
    for (const o of CATALOG) expect(AREAS).toContain(o.area);
  });

  it('covers every area', () => {
    for (const a of AREAS) expect(CATALOG.some((o) => o.area === a)).toBe(true);
  });

  it('derives the environment variable by relaxed binding unless one is named', () => {
    expect(envName(option('yukta.session.store-type'))).toBe(
      'YUKTA_SESSION_STORE_TYPE'
    );
    expect(envName(option('server.port'))).toBe('SERVER_PORT');
  });
});

describe('howToSet', () => {
  it('shows environment, properties, argument and Docker for a normal option', () => {
    const codes = howToSet(option('yukta.plugins.enabled')).map((s) => s.code);
    expect(codes).toEqual([
      'YUKTA_PLUGINS_ENABLED=<value>',
      'yukta.plugins.enabled=<value>',
      '--yukta.plugins.enabled=<value>',
      'docker run -e YUKTA_PLUGINS_ENABLED=<value> ghcr.io/infenia/yukta',
    ]);
  });

  it('shows only -D forms for a system-property option', () => {
    const codes = howToSet(option('yukta.rate-limit-per-minute')).map(
      (s) => s.code
    );
    expect(codes).toEqual([
      'java -Dyukta.rate-limit-per-minute=<value> -jar yukta.jar',
      'yukta -Dyukta.rate-limit-per-minute=<value>',
      'docker run ghcr.io/infenia/yukta -Dyukta.rate-limit-per-minute=<value>',
    ]);
  });
});
