// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useContext, type RefObject } from 'react';
import { UNSAFE_DataRouterContext, useBlocker } from 'react-router';
import { Modal } from '@/components/common/Modal';
import { Button } from '@/components/ui/button';

/**
 * Asks before the app navigates away from unsaved edits (a header link, Back). A reload or a
 * closed tab is the browser's own prompt. Needs a data router (the app has one); without one
 * there is nothing to ask.
 */
export function LeaveGuard({
  dirty,
  leaving,
}: {
  dirty: boolean;
  /** Set to true by the page itself right before it navigates after a save. */
  leaving: RefObject<boolean>;
}) {
  return useContext(UNSAFE_DataRouterContext) ? (
    <Guard dirty={dirty} leaving={leaving} />
  ) : null;
}

function Guard({
  dirty,
  leaving,
}: {
  dirty: boolean;
  leaving: RefObject<boolean>;
}) {
  const blocker = useBlocker(() => dirty && !leaving.current);
  if (blocker.state !== 'blocked') return null;
  return (
    <Modal title="Leave without saving?" onClose={() => blocker.reset()}>
      <p className="mb-4 text-body-md text-on-surface-variant">
        Your changes to this workflow are not saved and will be lost.
      </p>
      <div className="flex justify-end gap-2">
        <Button variant="ghost" onClick={() => blocker.reset()}>
          Stay
        </Button>
        <Button onClick={() => blocker.proceed()}>Leave</Button>
      </div>
    </Modal>
  );
}
