// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

export type EditionName = 'COMMUNITY' | 'PLUS' | 'ENTERPRISE';

export interface EditionInfo {
  edition: EditionName;
  license: string;
  features: string[];
  source: string | null;
}
