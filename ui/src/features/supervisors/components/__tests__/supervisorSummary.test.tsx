// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { SupervisorSummary } from '../SupervisorSummary';
import type { AttachmentView } from '../../types';

const attachment = (overrides: Partial<AttachmentView>): AttachmentView => ({
  id: 'a',
  supervisorId: 'guard',
  scope: 'global',
  enabled: true,
  source: 'MANUAL',
  originSessionId: null,
  originWorkflowId: null,
  ...overrides,
});

describe('SupervisorSummary', () => {
  it('says where it watches nothing yet', () => {
    render(
      <SupervisorSummary
        supervisor={{ settings: {}, dryRun: false }}
        attachments={[]}
        typeDefaults={null}
      />
    );
    expect(
      screen.getByText('Nowhere yet: attach it to a scope to start.')
    ).toBeInTheDocument();
  });

  it('marks paused attachments among the scopes it watches', () => {
    render(
      <SupervisorSummary
        supervisor={{ settings: {}, dryRun: false }}
        attachments={[
          attachment({ scope: 'global' }),
          attachment({ id: 'b', scope: 'session:s1', enabled: false }),
        ]}
        typeDefaults={null}
      />
    );
    expect(screen.getByText(/\(paused\)/)).toBeInTheDocument();
    expect(
      screen.getByText('Live: its commands are sent.')
    ).toBeInTheDocument();
  });

  it('names every command when none is listed, and the type defaults when given', () => {
    const { unmount } = render(
      <SupervisorSummary
        supervisor={{ settings: {}, dryRun: false }}
        attachments={[]}
        typeDefaults={null}
      />
    );
    expect(screen.getByText('Any command')).toBeInTheDocument();
    unmount();

    render(
      <SupervisorSummary
        supervisor={{ settings: {}, dryRun: false }}
        attachments={[]}
        typeDefaults={['PauseWorkflowCommand', 'StopRunCommand']}
      />
    );
    expect(screen.getByText('pause workflow, stop run')).toBeInTheDocument();
  });

  it('says when it may send no command', () => {
    render(
      <SupervisorSummary
        supervisor={{ settings: { allowedCommands: [] }, dryRun: false }}
        attachments={[]}
        typeDefaults={null}
      />
    );
    expect(screen.getByText('No command')).toBeInTheDocument();
  });

  it('describes what it may answer, and that it cannot answer by default', () => {
    const { unmount } = render(
      <SupervisorSummary
        supervisor={{
          settings: { canAnswer: true, maxRisk: 'high' },
          dryRun: false,
        }}
        attachments={[]}
        typeDefaults={null}
      />
    );
    expect(screen.getByText(/May answer up to HIGH risk/)).toBeInTheDocument();
    unmount();

    render(
      <SupervisorSummary
        supervisor={{ settings: {}, dryRun: false }}
        attachments={[]}
        typeDefaults={null}
      />
    );
    expect(
      screen.getByText('Cannot answer; it can only hand a request up.')
    ).toBeInTheDocument();
  });

  it('says a dry run decides and records but sends nothing', () => {
    render(
      <SupervisorSummary
        supervisor={{ settings: {}, dryRun: true }}
        attachments={[]}
        typeDefaults={null}
      />
    );
    expect(
      screen.getByText('Dry run: it decides and records, but sends nothing.')
    ).toBeInTheDocument();
  });
});
