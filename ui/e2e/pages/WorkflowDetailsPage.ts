// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Locator, Page } from '@playwright/test';

/** A workflow and its runs at /sessions/:id/workflow/:id. */
export class WorkflowDetailsPage {
  readonly root: Locator;
  readonly logs: Locator;
  readonly progress: Locator;
  readonly taskSequence: Locator;
  readonly executionSummary: Locator;
  readonly runHistory: Locator;

  constructor(private readonly page: Page) {
    this.root = page.getByTestId('workflow-details-page');
    this.logs = page.getByTestId('logs-body');
    this.progress = page.getByTestId('progress-panel');
    this.taskSequence = page.getByTestId('task-sequence');
    this.executionSummary = page.getByTestId('execution-summary');
    this.runHistory = page.getByRole('button', { name: 'Run history' });
  }

  async goto(sessionId: string, workflowId: string): Promise<void> {
    await this.page.goto(
      `/sessions/${encodeURIComponent(sessionId)}/workflow/${encodeURIComponent(workflowId)}`
    );
  }

  /** A node of the graph, by its id with spaces as underscores as the page names it. */
  node(nodeId: string): Locator {
    return this.page.getByTestId(`workflow-node-${nodeId}`);
  }
}
