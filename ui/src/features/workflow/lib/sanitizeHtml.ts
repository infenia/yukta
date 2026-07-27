// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import DOMPurify from 'dompurify';

/**
 * Substitute node placeholders and sanitize backend-provided node HTML.
 *
 * Substitution happens before sanitization so injected values are sanitized too.
 */
export function renderNodeHtml(html: string, nodeId: string): string {
  const substituted = html.split('{{nodeId}}').join(nodeId);
  return DOMPurify.sanitize(substituted, {
    USE_PROFILES: { html: true, svg: true },
    FORBID_TAGS: ['script', 'style', 'iframe', 'form', 'input', 'button'],
    FORBID_ATTR: ['onerror', 'onclick', 'onload'],
  });
}
