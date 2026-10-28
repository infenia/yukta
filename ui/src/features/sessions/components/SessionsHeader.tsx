// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { ReactNode } from 'react';
import { PageHeader } from '@/components/layout/PageHeader';

interface SessionsHeaderProps {
  sessionCount: number;
  actions?: ReactNode;
}

export function SessionsHeader({ sessionCount, actions }: SessionsHeaderProps) {
  return (
    <div className="px-4 pt-6 sm:px-6">
      <PageHeader
        actions={actions}
        title="Sessions"
        description={
          <>
            Manage and monitor active data orchestration environments.{' '}
            <span className="font-semibold text-primary">
              {sessionCount} session{sessionCount !== 1 ? 's' : ''}
            </span>{' '}
            found.
          </>
        }
      />
    </div>
  );
}
