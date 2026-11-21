// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { screen, within } from '@testing-library/react';
import type { UserEvent } from '@testing-library/user-event';

/**
 * Opens a themed Select (its trigger) and chooses an option by value, or by label when no option
 * has that value: the replacement for `user.selectOptions` on a native select.
 */
export async function choose(
  user: Pick<UserEvent, 'click'>,
  trigger: HTMLElement,
  option: string
) {
  await user.click(trigger);
  const list = await screen.findByRole('listbox');
  const byValue = list.querySelector<HTMLElement>(
    `[role="option"][data-value="${CSS.escape(option)}"]`
  );
  await user.click(
    byValue ?? within(list).getByRole('option', { name: option })
  );
}
