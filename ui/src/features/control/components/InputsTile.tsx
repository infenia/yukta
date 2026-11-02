// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { Card } from '@/components/ui/card';
import { getOpenInputs } from '@/features/overview/api';
import { relativeTime } from './WorkersPanel.helpers';

/** How many inputs wait on the server and for how long; the list is on the Overview page. */
export function InputsTile() {
  const waiting = useQuery({
    queryKey: ['overview', 'inputs'],
    queryFn: () => getOpenInputs(),
    refetchInterval: 10_000,
  });
  const items = waiting.data ?? [];
  const oldest = items.map((i) => i.createdAt).sort()[0];
  return (
    <Link to="/#ov-needs-attention-panel" aria-label="Inputs">
      <Card interactive className="p-4">
        <p className="text-overline text-on-surface-variant">Inputs</p>
        <p className="text-headline-md">{waiting.data ? items.length : '–'}</p>
        <p className="text-body-sm text-on-surface-variant">
          {oldest
            ? `oldest ${relativeTime(oldest).replace(/^in /, '')}`
            : 'none waiting'}
        </p>
      </Card>
    </Link>
  );
}
