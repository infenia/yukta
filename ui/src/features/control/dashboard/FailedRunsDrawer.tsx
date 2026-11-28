// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link } from 'react-router';
import { Button } from '@/components/ui/button';
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetTitle,
} from '@/components/ui/sheet';

/**
 * The runs that failed in the last 24 hours, newest first, each opening the search for that run.
 * The server names up to 20 failed runs per minute, so a very bad minute lists its first 20.
 */
export function FailedRunsDrawer({
  runs,
  onClose,
}: {
  runs: { time: number; id: string }[];
  onClose: () => void;
}) {
  return (
    <Sheet open onOpenChange={() => onClose()}>
      <SheetContent>
        <SheetTitle>Failed runs</SheetTitle>
        <p className="text-body-sm text-on-surface-variant">
          Runs that failed or timed out in the last 24 hours, newest first.
          Select one to find it in Runs.
        </p>
        <ul className="flex flex-col gap-1">
          {runs.map((run) => (
            <li
              key={run.id}
              className="text-body-sm flex items-baseline justify-between gap-2"
            >
              <Link
                className="min-w-0 truncate font-mono underline"
                to={`/runs?text=${encodeURIComponent(run.id)}`}
              >
                {run.id}
              </Link>
              <span className="num text-on-surface-variant shrink-0">
                {new Date(run.time).toLocaleTimeString([], {
                  hour: '2-digit',
                  minute: '2-digit',
                })}
              </span>
            </li>
          ))}
        </ul>
        <SheetClose render={<Button variant="ghost" />}>Close</SheetClose>
      </SheetContent>
    </Sheet>
  );
}
