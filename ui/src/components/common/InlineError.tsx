// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** Compact error line for failed actions. */
export function InlineError({ message }: { message: string | null }) {
  return message ? (
    <p role="alert" className="text-sm text-error">
      {message}
    </p>
  ) : null;
}
