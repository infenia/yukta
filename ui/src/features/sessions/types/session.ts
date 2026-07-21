// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

export interface SessionListItem {
  sessionId: string;
  name: string;
  description: string;
  initiator: string;
  tags: string[];
  projectPath: string;
  workflowCount: number;
}

export interface SessionListItems {
  sessions: SessionListItem[];
}
