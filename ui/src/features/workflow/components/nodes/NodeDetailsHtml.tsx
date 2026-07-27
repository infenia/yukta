// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMemo } from 'react';
import { renderNodeHtml } from '../../lib/sanitizeHtml';
import type { UiDesign } from '../../types/workflow';

/**
 * The node placeholder's details slot: renders backend-provided HTML (string or markup)
 * after placeholder substitution and sanitization.
 */
export function NodeDetailsHtml({ uiDesign, nodeId }: { uiDesign: UiDesign; nodeId: string }) {
  const html = useMemo(() => renderNodeHtml(uiDesign.html, nodeId), [uiDesign.html, nodeId]);
  return (
    <div
      data-testid="node-details-html"
      className="overflow-hidden"
      style={{ height: uiDesign.height }}
      // Sanitized with DOMPurify in renderNodeHtml before injection
      dangerouslySetInnerHTML={{ __html: html }}
    />
  );
}
