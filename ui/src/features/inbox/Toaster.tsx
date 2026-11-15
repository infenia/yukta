// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEffect } from 'react';
import { Link } from 'react-router';
import { InputAnswerForm } from '@/features/workflow/components/InputAnswerForm';
import { useNotifications, type Toast } from './NotificationsContext';
import { plain, SEVERITY_DOT } from './severity';

/** How long an alert stays; a question stays until it is dismissed or answered. */
export const INFO_TOAST_MS = 8000;
const MAX_VISIBLE = 3;

function ToastItem({ toast }: { toast: Toast }) {
  const { dismissToast } = useNotifications();
  const n = toast.notification;
  const action = n.kind === 'ACTION';
  const input = n.input;
  const ends = !action || n.state === 'RESOLVED';
  useEffect(() => {
    if (!ends) return undefined;
    const timer = setTimeout(() => dismissToast(toast.id), INFO_TOAST_MS);
    return () => clearTimeout(timer);
  }, [ends, dismissToast, toast.id]);
  return (
    <div
      role={n.severity === 'ERROR' ? 'alert' : 'status'}
      data-testid="toast"
      className="border-border bg-card text-card-foreground flex flex-col gap-2 rounded-xl border p-3 shadow-lg"
    >
      <div className="flex items-start gap-2">
        <span
          aria-hidden="true"
          className={`mt-1.5 size-2 flex-none rounded-full ${SEVERITY_DOT[n.severity]}`}
        />
        <div className="flex-1">
          {input && (
            <p className="text-label-sm text-on-surface-variant">
              {input.risk.toLowerCase()} risk
            </p>
          )}
          <p className="text-body-md font-medium">{plain(n.title)}</p>
          {n.state === 'RESOLVED' && n.resolution && (
            <p className="text-body-sm text-on-surface-variant">
              {plain(n.resolution)}
            </p>
          )}
        </div>
        <button
          type="button"
          aria-label="Dismiss"
          onClick={() => dismissToast(toast.id)}
          className="text-on-surface-variant hover:text-on-surface"
        >
          <span
            aria-hidden="true"
            className="material-symbols-outlined text-lg"
          >
            close
          </span>
        </button>
      </div>
      {n.state === 'ACTIVE' && input?.kind === 'APPROVAL' && (
        <InputAnswerForm
          request={input}
          onAnswered={() => dismissToast(toast.id)}
        />
      )}
      <Link
        to={`/inbox?item=${n.id}`}
        onClick={() => dismissToast(toast.id)}
        className="text-body-sm text-primary"
      >
        View
      </Link>
    </div>
  );
}

/**
 * What just arrived, bottom right: at most three, newest on top. Each is a live region, polite for
 * most and assertive for an error, so a screen reader announces it without taking the focus.
 */
export function Toaster() {
  const { toasts } = useNotifications();
  if (toasts.length === 0) return null;
  return (
    <section
      aria-label="New notifications"
      className="fixed right-4 bottom-4 z-60 flex w-[min(24rem,calc(100vw-2rem))] flex-col gap-2 max-sm:bottom-20"
    >
      {toasts.slice(0, MAX_VISIBLE).map((toast) => (
        <ToastItem key={toast.id} toast={toast} />
      ))}
    </section>
  );
}
