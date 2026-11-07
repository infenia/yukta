// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { ApiError } from '@/lib/apiClient';

/**
 * The field errors a refused save gives about declared supervisors
 * (`supervisors[0].supervisor`, `workflows.w.supervisors[1].nodes`), as lines for the picker.
 */
export function supervisorProblems(error: unknown): string[] {
  if (!(error instanceof ApiError)) return [];
  return (error.fieldErrors ?? [])
    .filter((f) => f.field.includes('supervisors['))
    .map((f) => `${f.field}: ${f.message}`);
}
