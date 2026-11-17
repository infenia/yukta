// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Locator, Page } from '@playwright/test';

/** The workflow editor at /sessions/:id/workflow/:id/edit. */
export class WorkflowEditorPage {
  readonly root: Locator;
  readonly canvas: Locator;
  readonly nodesAndEdges: Locator;
  readonly inspector: Locator;

  constructor(private readonly page: Page) {
    this.root = page.getByTestId('workflow-editor');
    this.canvas = page.getByTestId('editor-canvas');
    this.nodesAndEdges = page.getByRole('complementary', {
      name: 'Nodes and edges',
    });
    this.inspector = page.getByRole('complementary', {
      name: 'Node inspector',
    });
  }

  async goto(sessionId: string, workflowId: string): Promise<void> {
    await this.page.goto(
      `/sessions/${encodeURIComponent(sessionId)}/workflow/${encodeURIComponent(workflowId)}/edit`
    );
  }

  node(nodeId: string): Locator {
    return this.page.getByTestId(`workflow-node-${nodeId}`);
  }
}
