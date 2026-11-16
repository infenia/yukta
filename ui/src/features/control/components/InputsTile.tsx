// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link } from 'react-router';
import { Card } from '@/components/ui/card';
import { useNotifications } from '@/features/inbox/NotificationsContext';
import { relativeTime } from './WorkersPanel.helpers';

/** How many questions wait for me and for how long; the inbox has them. */
export function InputsTile() {
  const { actions, loaded } = useNotifications();
  const oldest = actions.map((i) => i.createdAt).sort()[0];
  return (
    <div aria-label="Inputs">
      <Card className="p-4">
        <p className="text-overline text-on-surface-variant">Inputs</p>
        <p className="text-headline-md">{loaded ? actions.length : '–'}</p>
        <p className="text-body-sm text-on-surface-variant">
          {oldest
            ? `oldest ${relativeTime(oldest).replace(/^in /, '')}`
            : 'none waiting'}
        </p>
        <Link to="/inbox" className="text-body-sm text-primary">
          Open inbox
        </Link>
      </Card>
    </div>
  );
}
