// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ReactNode } from 'react';
import { PageHeader } from '@/components/layout/PageHeader';

interface SessionsHeaderProps {
  actions?: ReactNode;
}

export function SessionsHeader({ actions }: SessionsHeaderProps) {
  return (
    <div className="px-4 pt-6 sm:px-6">
      <PageHeader
        actions={actions}
        title="Sessions"
        description="A session holds a project's workflows and their runs."
      />
    </div>
  );
}
