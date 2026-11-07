// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { Card } from '@/components/ui/card';
import { getOpenInputs } from '@/features/workflow/api/inputs';
import { relativeTime } from './WorkersPanel.helpers';

/** How many inputs wait on the server and for how long. */
export function InputsTile() {
  const waiting = useQuery({
    queryKey: ['inputs', 'open'],
    queryFn: () => getOpenInputs(),
    refetchInterval: 10_000,
  });
  const items = waiting.data ?? [];
  const oldest = items.map((i) => i.createdAt).sort()[0];
  return (
    <div aria-label="Inputs">
      <Card className="p-4">
        <p className="text-overline text-on-surface-variant">Inputs</p>
        <p className="text-headline-md">{waiting.data ? items.length : '–'}</p>
        <p className="text-body-sm text-on-surface-variant">
          {oldest
            ? `oldest ${relativeTime(oldest).replace(/^in /, '')}`
            : 'none waiting'}
        </p>
      </Card>
    </div>
  );
}
