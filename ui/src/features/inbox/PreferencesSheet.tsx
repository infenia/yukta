// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Select } from '@/components/ui/select';
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetTitle,
} from '@/components/ui/sheet';
import {
  CATEGORIES,
  getPreferences,
  savePreferences,
  type NotificationLevel,
  type NotificationPreferences,
} from './api';
import { useDevicePrefs } from './devicePrefs';
import {
  desktopSupported,
  requestDesktopPermission,
} from './useDesktopNotifications';

const LABEL: Record<NotificationLevel, string> = {
  OFF: 'Off',
  INBOX: 'Inbox',
  DESKTOP: 'Inbox and desktop',
};

/** What the permission means for the person, or null when nothing needs saying. */
function permissionNote(): string | null {
  if (!desktopSupported()) {
    return 'This browser cannot show desktop notifications.';
  }
  return Notification.permission === 'denied'
    ? 'Desktop notifications are blocked for this site. Allow them in the browser settings, then switch this on again.'
    : null;
}

function Form({
  initial,
  sessions,
  onSaved,
}: {
  initial: NotificationPreferences;
  sessions: string[];
  onSaved: () => void;
}) {
  const queryClient = useQueryClient();
  const [levels, setLevels] = useState(initial.levels);
  const [muted, setMuted] = useState(new Set(initial.mutedSessions));
  const [device, setDevice] = useDevicePrefs();
  const [note, setNote] = useState(permissionNote);
  const save = useMutation({
    mutationFn: () =>
      savePreferences({ levels, mutedSessions: [...muted].sort() }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: ['notification-preferences'],
      });
      onSaved();
    },
  });

  // permission is asked here, from the click on the switch, and never again once it is refused
  const toggleDesktop = async (on: boolean) => {
    if (!on) {
      setDevice({ desktop: false });
      return;
    }
    const permission = await requestDesktopPermission();
    setDevice({ desktop: permission === 'granted' });
    setNote(permissionNote());
  };

  const mutable = [...new Set([...sessions, ...muted])].sort();
  return (
    <div className="flex flex-col gap-4">
      <fieldset className="flex flex-col gap-2">
        <legend className="text-label-md">What reaches me</legend>
        {CATEGORIES.map((category) => (
          <label
            key={category}
            className="text-body-md flex items-center justify-between gap-3"
          >
            {category}
            <Select
              ariaLabel={category}
              value={levels[category] ?? 'INBOX'}
              onValueChange={(level) =>
                setLevels({
                  ...levels,
                  [category]: level as NotificationLevel,
                })
              }
              options={(['OFF', 'INBOX', 'DESKTOP'] as const)
                .filter((l) => category !== 'input' || l !== 'OFF')
                .map((l) => ({ value: l, label: LABEL[l] }))}
            />
          </label>
        ))}
      </fieldset>
      <fieldset className="flex flex-col gap-1">
        <legend className="text-label-md">Muted sessions</legend>
        {mutable.length === 0 && (
          <p className="text-body-sm text-on-surface-variant">
            No sessions yet.
          </p>
        )}
        {mutable.map((s) => (
          <label key={s} className="text-body-md flex items-center gap-2">
            <input
              type="checkbox"
              checked={muted.has(s)}
              onChange={(e) => {
                const next = new Set(muted);
                if (e.target.checked) next.add(s);
                else next.delete(s);
                setMuted(next);
              }}
            />
            {s}
          </label>
        ))}
      </fieldset>
      <fieldset className="flex flex-col gap-1">
        <legend className="text-label-md">This device</legend>
        <label className="text-body-md flex items-center gap-2">
          <input
            type="checkbox"
            checked={device.desktop}
            onChange={(e) => void toggleDesktop(e.target.checked)}
          />
          Show desktop notifications
        </label>
        <label className="text-body-md flex items-center gap-2">
          <input
            type="checkbox"
            checked={device.details}
            onChange={(e) => setDevice({ details: e.target.checked })}
          />
          Show the title and the workflow (off: “Yukta needs your input”)
        </label>
        {note && (
          <p role="status" className="text-body-sm text-on-surface-variant">
            {note}
          </p>
        )}
      </fieldset>
      {save.isError && (
        <p role="alert" className="text-body-sm text-destructive">
          {save.error.message}
        </p>
      )}
      <div className="flex gap-2">
        <Button disabled={save.isPending} onClick={() => save.mutate()}>
          Save
        </Button>
        <SheetClose render={<Button variant="ghost" />}>Close</SheetClose>
      </div>
    </div>
  );
}

/** What reaches the person, per category and session, and the two switches of this browser. */
export function PreferencesSheet({ sessions }: { sessions: string[] }) {
  const [open, setOpen] = useState(false);
  const { data, isError } = useQuery({
    queryKey: ['notification-preferences'],
    queryFn: getPreferences,
    enabled: open,
  });
  return (
    <>
      <Button variant="outline" onClick={() => setOpen(true)}>
        Preferences
      </Button>
      <Sheet open={open} onOpenChange={setOpen}>
        <SheetContent>
          <SheetTitle>Notification preferences</SheetTitle>
          {isError && (
            <p role="alert" className="text-body-sm text-destructive">
              The preferences could not be read.
            </p>
          )}
          {data && (
            <Form
              initial={data}
              sessions={sessions}
              onSaved={() => setOpen(false)}
            />
          )}
        </SheetContent>
      </Sheet>
    </>
  );
}
