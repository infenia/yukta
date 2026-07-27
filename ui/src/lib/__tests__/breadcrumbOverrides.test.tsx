// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import {
  BreadcrumbOverridesProvider,
  useBreadcrumbOverride,
  useBreadcrumbOverrides,
} from '../breadcrumbOverrides';

function OverrideSetter({ segment, label }: { segment?: string; label?: string }) {
  useBreadcrumbOverride(segment, label);
  return null;
}

function OverrideReader() {
  const overrides = useBreadcrumbOverrides();
  return <span data-testid="overrides">{JSON.stringify(overrides)}</span>;
}

describe('breadcrumbOverrides', () => {
  it('exposes overrides set by mounted components', () => {
    render(
      <BreadcrumbOverridesProvider>
        <OverrideSetter segment="wf-982-xk-11" label="Supply_Chain_Optimizer_V2" />
        <OverrideReader />
      </BreadcrumbOverridesProvider>
    );

    expect(screen.getByTestId('overrides')).toHaveTextContent(
      '{"wf-982-xk-11":"Supply_Chain_Optimizer_V2"}'
    );
  });

  it('clears the override when the setter unmounts', () => {
    const { rerender } = render(
      <BreadcrumbOverridesProvider>
        <OverrideSetter segment="wf1" label="Name" />
        <OverrideReader />
      </BreadcrumbOverridesProvider>
    );

    rerender(
      <BreadcrumbOverridesProvider>
        <OverrideReader />
      </BreadcrumbOverridesProvider>
    );

    expect(screen.getByTestId('overrides')).toHaveTextContent('{}');
  });

  it('deduplicates identical overrides and tolerates double clears', () => {
    const { rerender } = render(
      <BreadcrumbOverridesProvider>
        <OverrideSetter segment="wf1" label="Name" />
        <OverrideSetter segment="wf1" label="Name" />
        <OverrideReader />
      </BreadcrumbOverridesProvider>
    );

    expect(screen.getByTestId('overrides')).toHaveTextContent('{"wf1":"Name"}');

    rerender(
      <BreadcrumbOverridesProvider>
        <OverrideReader />
      </BreadcrumbOverridesProvider>
    );

    expect(screen.getByTestId('overrides')).toHaveTextContent('{}');
  });

  it('ignores missing segment or label', () => {
    render(
      <BreadcrumbOverridesProvider>
        <OverrideSetter segment={undefined} label="X" />
        <OverrideSetter segment="seg" label={undefined} />
        <OverrideReader />
      </BreadcrumbOverridesProvider>
    );

    expect(screen.getByTestId('overrides')).toHaveTextContent('{}');
  });

  it('defaults to empty overrides without a provider', () => {
    render(<OverrideReader />);
    expect(screen.getByTestId('overrides')).toHaveTextContent('{}');
  });

  it('no-ops setting and clearing without a provider', () => {
    render(<OverrideSetter segment="s" label="L" />);
  });
});
