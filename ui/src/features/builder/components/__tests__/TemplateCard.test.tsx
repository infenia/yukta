// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '@/test/utils/testUtils';
import { getTemplate } from '../../api';
import type { TemplateSummary } from '../../types';
import { TemplateCard } from '../TemplateCard';

vi.mock('../../api', () => ({ getTemplate: vi.fn() }));

const summary = (pluginTypes: string[]): TemplateSummary => ({
  id: 't',
  title: 'A template',
  summary: 'What it does',
  pluginTypes,
});

const detail = (nodes: { nodeId: string; type: string }[]) => ({
  ...summary([]),
  readme: '',
  session: {
    workflows: {
      main: { workflowId: 'main', description: '', nodes, edges: [] },
    },
  },
});

const card = (template: TemplateSummary | null, off: string[] = []) =>
  renderWithProviders(
    <ul>
      <TemplateCard
        template={template}
        categories={{}}
        off={new Set(off)}
        onUse={() => undefined}
        onPreview={template ? () => undefined : undefined}
      />
    </ul>
  );

describe('TemplateCard', () => {
  beforeEach(() => {
    vi.mocked(getTemplate).mockReset();
  });

  it('is the blank session without a template, with no preview button', () => {
    card(null);
    expect(screen.getByText('Blank session')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Start blank' })).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Preview' })).toBeNull();
    expect(screen.getByText('2 nodes')).toBeVisible();
  });

  it('shows its category, and says what it means in a tooltip', async () => {
    const user = userEvent.setup();
    card({
      ...summary([]),
      categoryLabel: 'Free tools',
      categoryDescription: 'Needs git or bun installed where the server runs.',
    });
    await user.hover(screen.getByText('Free tools'));
    expect(
      await screen.findByText(
        'Needs git or bun installed where the server runs.'
      )
    ).toBeVisible();
  });

  it('shows four plugin chips and counts the rest', async () => {
    vi.mocked(getTemplate).mockResolvedValue(
      detail([{ nodeId: 'a', type: 'MAPPER' }]) as never
    );
    card(
      summary(['API_TRIGGER', 'BRANCH', 'MAPPER', 'VALIDATE', 'FILE_WRITE'])
    );
    expect(screen.getByText('+1 more')).toBeVisible();
    expect(await screen.findByText('1 nodes')).toBeVisible();
  });

  it('has no "more" chip for a short list, and no node count for an empty workflow', async () => {
    vi.mocked(getTemplate).mockResolvedValue(detail([]) as never);
    card(summary(['MAPPER']));
    expect(screen.queryByText(/more/)).toBeNull();
    await vi.waitFor(() => expect(getTemplate).toHaveBeenCalled());
    expect(screen.queryByText(/nodes/)).toBeNull();
  });

  it('shows no chips for a template without plugin types', () => {
    vi.mocked(getTemplate).mockReturnValue(new Promise(() => undefined));
    card(summary([]));
    expect(screen.queryByLabelText('Plugin types')).toBeNull();
  });

  it('runs the preview while hovered or focused', async () => {
    const user = userEvent.setup();
    card(null);
    await user.hover(screen.getByText('Blank session'));
    expect(screen.getByText('Running')).toBeVisible();
    await user.unhover(screen.getByText('Blank session'));
    expect(screen.queryByText('Running')).toBeNull();
    const button = screen.getByRole('button', { name: 'Start blank' });
    act(() => button.focus());
    expect(screen.getByText('Running')).toBeVisible();
    act(() => button.blur());
    expect(screen.queryByText('Running')).toBeNull();
  });

  it('warns on the card about plugins the server has off, and strikes their chips', async () => {
    vi.mocked(getTemplate).mockReturnValue(new Promise(() => undefined));
    card(summary(['API_TRIGGER', 'FILE_WRITE']), ['FILE_WRITE']);
    expect(screen.getByRole('status')).toHaveTextContent(
      'Needs FILE_WRITE enabled'
    );
    expect(screen.getByRole('status')).toHaveTextContent(
      'YUKTA_PLUGINS_ENABLED=FILE_WRITE'
    );
    expect(screen.getByText('File Write').closest('li')).toHaveClass(
      'line-through'
    );
    expect(screen.getByText('Api Trigger').closest('li')).not.toHaveClass(
      'line-through'
    );
  });
});
