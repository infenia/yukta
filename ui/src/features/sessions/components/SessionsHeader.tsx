// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { PageHeader } from '@/components/layout/PageHeader';

interface SessionsHeaderProps {
  sessionCount: number;
}

export function SessionsHeader({ sessionCount }: SessionsHeaderProps) {
  return (
    <div className="px-6 pt-4">
      <PageHeader
        title="Sessions"
        description={
          <>
            Manage and monitor active data orchestration environments.{' '}
            <span className="text-primary font-semibold">
              {sessionCount} session{sessionCount !== 1 ? 's' : ''}
            </span>{' '}
            found.
          </>
        }
      />
    </div>
  );
}
