// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Callout } from '@/components/common/Callout';

/** The setting that switches the given plugin types on, as an operator types it. */
export const enableSetting = (types: string[]) =>
  `YUKTA_PLUGINS_ENABLED=${types.join(',')}`;

/**
 * Says which plugins the server has switched off and the setting that turns them on, so the
 * problem is known before a session is created and run. Nothing is shown when none are off.
 */
export function NeedsPlugins({
  types,
  title,
  brief = false,
  className,
}: {
  types: string[];
  /** Replaces the default heading, for a notice that covers several templates. */
  title?: string;
  /** A short form for a card, where the full explanation would repeat on every one. */
  brief?: boolean;
  className?: string;
}) {
  if (types.length === 0) return null;
  return (
    <Callout
      kind="warning"
      compact
      className={className}
      title={title ?? `Needs ${types.join(', ')} enabled`}
    >
      <span className="text-body-sm">
        {brief ? (
          <>
            Switched off. Set{' '}
            <code className="text-code-sm select-all">
              {enableSetting(types)}
            </code>{' '}
            and restart.
          </>
        ) : (
          <>
            Switched off on this server, so a run fails until you set{' '}
            <code className="text-code-sm select-all">
              {enableSetting(types)}
            </code>{' '}
            (or <code className="text-code-sm">yukta.plugins.enabled</code>, or{' '}
            <code className="text-code-sm">all</code>) and restart. You can
            still create the session and edit it.
          </>
        )}
      </span>
    </Callout>
  );
}
