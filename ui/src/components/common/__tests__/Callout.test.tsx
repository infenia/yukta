// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Callout, type CalloutKind } from '../Callout';
import { InlineError } from '../InlineError';

describe('Callout', () => {
  it.each<[CalloutKind, string, string]>([
    ['info', 'info', 'status'],
    ['success', 'check_circle', 'status'],
    ['warning', 'warning', 'status'],
    ['danger', 'error', 'alert'],
  ])('renders the %s kind with its icon and role', (kind, icon, role) => {
    render(<Callout kind={kind}>Body</Callout>);
    const box = screen.getByRole(role);
    expect(box).toHaveAttribute('data-kind', kind);
    expect(box).toHaveTextContent(icon);
    expect(box).toHaveTextContent('Body');
    expect(box).toHaveClass('px-4', 'py-3');
  });

  it('defaults to info and shows an optional title, compactly', () => {
    render(
      <Callout title="Heads up" compact className="mt-2">
        Note
      </Callout>
    );
    const box = screen.getByRole('status');
    expect(box).toHaveAttribute('data-kind', 'info');
    expect(screen.getByText('Heads up')).toHaveClass('text-label-md');
    expect(box).toHaveClass('px-3', 'py-2', 'mt-2');
  });

  it('can be a title alone', () => {
    render(<Callout title="Only a title" />);
    expect(screen.getByRole('status')).toHaveTextContent('Only a title');
  });
});

describe('InlineError', () => {
  it('renders nothing without a message', () => {
    const { container } = render(<InlineError message={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders a compact danger callout', () => {
    render(<InlineError message="Boom" />);
    const alert = screen.getByRole('alert');
    expect(alert).toHaveAttribute('data-kind', 'danger');
    expect(alert).toHaveTextContent('Boom');
  });
});
