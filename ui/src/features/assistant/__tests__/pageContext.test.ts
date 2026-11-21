// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import { pageContext, systemPrompt } from '../lib/pageContext';

describe('pageContext', () => {
  it('names the workflow on its run page, and the run when one is picked', () => {
    const plain = pageContext('/sessions/s1/workflow/w1', '');
    expect(plain.label).toBe('Workflow w1');
    expect(plain.hint).toBe(
      'The person is looking at workflow w1 of session s1.'
    );

    const run = pageContext('/sessions/s1/workflow/w1/', '?exec=0123456789');
    expect(run.label).toBe('Run 01234567');
    expect(run.hint).toContain('run 0123456789');
    expect(run.suggestions).toContain('Why did the latest run fail?');
  });

  it('knows the workflow editor', () => {
    const editor = pageContext('/sessions/s1/workflow/w1/edit', '');

    expect(editor.label).toBe('Editing w1');
    expect(editor.suggestions).toContain('Explain this workflow');
  });

  it('names a session, but not the pages under /sessions that are not one', () => {
    expect(pageContext('/sessions/s1', '').label).toBe('Session s1');
    expect(pageContext('/sessions/s1/edit', '').label).toBe('Session s1');
    expect(pageContext('/sessions/new', '').label).toBe('');
    expect(pageContext('/sessions/import', '').label).toBe('');
  });

  it('falls back to general suggestions elsewhere', () => {
    const general = pageContext('/control', '');

    expect(general.label).toBe('');
    expect(general.suggestions).toContain('What is running right now?');
  });
});

describe('systemPrompt', () => {
  it('says what Yukta is and where the person is', () => {
    const prompt = systemPrompt(pageContext('/sessions/s1', ''));

    expect(prompt).toContain('workflow orchestration server');
    expect(prompt).toContain('session s1');
  });
});
