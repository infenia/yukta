// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** Text matcher for a <pre> whose content matches, for code that is split into colored spans. */
export const preText =
  (pattern: RegExp) => (_: string, element: Element | null) =>
    element?.tagName === 'PRE' && pattern.test(element.textContent ?? '');
