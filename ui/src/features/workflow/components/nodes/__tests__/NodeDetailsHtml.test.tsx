// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { NodeDetailsHtml } from '../NodeDetailsHtml';

describe('NodeDetailsHtml', () => {
  it('renders sanitized backend HTML with the nodeId substituted', () => {
    render(
      <NodeDetailsHtml
        uiDesign={{ html: '<div class="metrics">{{nodeId}}</div>', width: 200, height: 40 }}
        nodeId="vectorize-batch"
      />
    );

    const container = screen.getByTestId('node-details-html');
    expect(container).toHaveTextContent('vectorize-batch');
    expect(container.querySelector('.metrics')).not.toBeNull();
    expect(container).toHaveStyle({ height: '40px' });
  });

  it('strips scripts from backend HTML', () => {
    render(
      <NodeDetailsHtml
        uiDesign={{ html: '<span>ok</span><script>window.x=1</script>', width: 100, height: 20 }}
        nodeId="n1"
      />
    );

    const container = screen.getByTestId('node-details-html');
    expect(container.querySelector('script')).toBeNull();
    expect(container).toHaveTextContent('ok');
  });
});
