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
            A session holds a project&apos;s workflows and their runs.{' '}
            <span className="text-primary font-semibold">
              {sessionCount} session{sessionCount !== 1 ? 's' : ''}
            </span>
            .
          </>
        }
      />
    </div>
  );
}
