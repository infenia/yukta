// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { renderNodeHtml } from '../sanitizeHtml';

describe('renderNodeHtml', () => {
  it('substitutes every nodeId placeholder', () => {
    const result = renderNodeHtml('<div>{{nodeId}} - {{nodeId}}</div>', 'vectorize-batch');
    expect(result).toBe('<div>vectorize-batch - vectorize-batch</div>');
  });

  it('keeps safe markup and classes', () => {
    const html = '<div class="flex items-center"><span class="font-bold">Branch</span></div>';
    expect(renderNodeHtml(html, 'n1')).toContain('class="flex items-center"');
  });

  it('keeps inline svg icons', () => {
    const html = '<svg viewBox="0 0 24 24"><path d="M8 7h8"></path></svg>';
    expect(renderNodeHtml(html, 'n1')).toContain('<svg');
  });

  it('strips script tags', () => {
    const result = renderNodeHtml('<div>ok</div><script>alert(1)</script>', 'n1');
    expect(result).not.toContain('script');
    expect(result).toContain('<div>ok</div>');
  });

  it('strips event handler attributes', () => {
    const result = renderNodeHtml('<div onclick="alert(1)" onerror="x()">ok</div>', 'n1');
    expect(result).not.toContain('onclick');
    expect(result).not.toContain('onerror');
  });

  it('strips iframes, forms and inputs', () => {
    const result = renderNodeHtml(
      '<iframe src="https://evil"></iframe><form><input value="x"/></form><p>keep</p>',
      'n1'
    );
    expect(result).not.toContain('iframe');
    expect(result).not.toContain('form');
    expect(result).not.toContain('input');
    expect(result).toContain('<p>keep</p>');
  });

  it('sanitizes malicious content injected through the nodeId', () => {
    const result = renderNodeHtml('<div>{{nodeId}}</div>', '<img src=x onerror=alert(1)>');
    expect(result).not.toContain('onerror');
  });
});
