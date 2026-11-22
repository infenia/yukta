// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { flowLayout } from '../../lib/flowLayout';
import { TemplateFlow } from '../TemplateFlow';

const flow = flowLayout(
  [
    { nodeId: 'a', type: 'API_TRIGGER', color: 'sky' },
    { nodeId: 'b', type: 'MAPPER', color: 'not-a-color' },
    { nodeId: 'c', type: 'CONSOLE_TERMINAL' },
  ],
  [
    { source: 'a', target: 'b' },
    { source: 'b', target: 'c' },
  ]
);

/** The chip of each node, in node order. */
const chips = () =>
  [
    ...screen.getByTestId('template-flow').querySelectorAll('span.absolute'),
  ] as HTMLElement[];

describe('TemplateFlow colors', () => {
  it('draws a node in the palette color it declares', () => {
    render(<TemplateFlow flow={flow} categories={{}} playing={false} />);
    expect(chips()[0].style.borderColor).toBe('var(--pal-sky)');
    expect(chips()[0].style.backgroundColor).toContain('var(--pal-sky)');
  });

  it('keeps the default look for a node with no color or an unknown one', () => {
    render(<TemplateFlow flow={flow} categories={{}} playing={false} />);
    expect(chips()[1].style.borderColor).toBe('');
    expect(chips()[2].style.borderColor).toBe('');
  });
});
