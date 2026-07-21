// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

interface SessionsHeaderProps {
  sessionCount: number;
}

export function SessionsHeader({ sessionCount }: SessionsHeaderProps) {
  return (
    <div className="flex flex-col gap-1 px-6 py-4">
      <h1 className="text-3xl font-light text-on-surface">Sessions</h1>
      <p className="text-body-md text-on-surface-variant">
        Manage and monitor active data orchestration environments.{' '}
        <span className="font-semibold text-primary">
          {sessionCount} session{sessionCount !== 1 ? 's' : ''}
        </span>{' '}
        found.
      </p>
    </div>
  );
}
