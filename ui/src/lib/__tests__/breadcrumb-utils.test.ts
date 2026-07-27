// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { parseBreadcrumbsFromPath } from '../breadcrumb-utils';

describe('parseBreadcrumbsFromPath', () => {
  it('should return Sessions for root path', () => {
    const breadcrumbs = parseBreadcrumbsFromPath('/');
    expect(breadcrumbs).toHaveLength(1);
    expect(breadcrumbs[0]).toEqual({
      label: 'Sessions',
      href: undefined,
      isCurrent: true,
    });
  });

  it('should return Sessions for empty path', () => {
    const breadcrumbs = parseBreadcrumbsFromPath('');
    expect(breadcrumbs).toHaveLength(1);
    expect(breadcrumbs[0]).toEqual({
      label: 'Sessions',
      href: undefined,
      isCurrent: true,
    });
  });

  it('should handle single segment route with label mapping', () => {
    const breadcrumbs = parseBreadcrumbsFromPath('/sessions');
    expect(breadcrumbs).toHaveLength(1);
    expect(breadcrumbs[0]).toEqual({
      label: 'Sessions',
      href: undefined,
      isCurrent: true,
    });
  });

  it('should handle unmapped segments by capitalizing first letter', () => {
    const breadcrumbs = parseBreadcrumbsFromPath('/history');
    expect(breadcrumbs).toHaveLength(1);
    expect(breadcrumbs[0]).toEqual({
      label: 'History',
      href: undefined,
      isCurrent: true,
    });
  });

  it('should handle /sessions/123 with numeric ID', () => {
    const breadcrumbs = parseBreadcrumbsFromPath('/sessions/123');
    expect(breadcrumbs).toHaveLength(1);
    expect(breadcrumbs[0]).toEqual({
      label: 'Sessions',
      href: undefined, // Current item has no href
      isCurrent: true,
    });
  });

  it('should handle /sessions/123/workflows with numeric ID and mapped segment', () => {
    const breadcrumbs = parseBreadcrumbsFromPath('/sessions/123/workflows');
    expect(breadcrumbs).toHaveLength(2);
    expect(breadcrumbs[0]).toEqual({
      label: 'Sessions',
      href: '/sessions',
      isCurrent: false,
    });
    expect(breadcrumbs[1]).toEqual({
      label: 'Workflows',
      href: undefined,
      isCurrent: true,
    });
  });

  it('should handle /sessions/123/workflows/456 with multiple numeric IDs', () => {
    const breadcrumbs = parseBreadcrumbsFromPath('/sessions/123/workflows/456');
    expect(breadcrumbs).toHaveLength(2);
    expect(breadcrumbs[0]).toEqual({
      label: 'Sessions',
      href: '/sessions',
      isCurrent: false,
    });
    expect(breadcrumbs[1]).toEqual({
      label: 'Workflows',
      href: undefined,
      isCurrent: true,
    });
  });

  it('should handle complex nested paths', () => {
    const breadcrumbs = parseBreadcrumbsFromPath('/sessions/42/workflows');
    expect(breadcrumbs).toHaveLength(2);
    expect(breadcrumbs[0].label).toBe('Sessions');
    expect(breadcrumbs[0].isCurrent).toBe(false);
    expect(breadcrumbs[0].href).toBe('/sessions');
    expect(breadcrumbs[1].label).toBe('Workflows');
    expect(breadcrumbs[1].isCurrent).toBe(true);
    expect(breadcrumbs[1].href).toBeUndefined();
  });

  it('should always set isCurrent=true for last segment', () => {
    const paths = [
      '/sessions',
      '/sessions/123',
      '/sessions/123/workflows',
      '/sessions/123/workflows/456',
    ];

    paths.forEach((path) => {
      const breadcrumbs = parseBreadcrumbsFromPath(path);
      const lastBreadcrumb = breadcrumbs[breadcrumbs.length - 1];
      expect(lastBreadcrumb.isCurrent).toBe(true);
    });
  });

  it('should only set href for non-current items', () => {
    const breadcrumbs = parseBreadcrumbsFromPath('/sessions/123/workflows/456');
    breadcrumbs.forEach((item) => {
      if (item.isCurrent) {
        expect(item.href).toBeUndefined();
      } else {
        expect(item.href).toBeDefined();
      }
    });
  });

  it('should handle trailing slashes', () => {
    const breadcrumbs1 = parseBreadcrumbsFromPath('/sessions/123/');
    const breadcrumbs2 = parseBreadcrumbsFromPath('/sessions/123');
    expect(breadcrumbs1).toEqual(breadcrumbs2);
  });

  it('should handle uppercase letters in segments', () => {
    const breadcrumbs = parseBreadcrumbsFromPath('/Sessions');
    expect(breadcrumbs).toHaveLength(1);
    expect(breadcrumbs[0].label).toBe('Sessions'); // Mapped from ROUTE_LABEL_MAP
  });

  it('should handle custom unmapped segments', () => {
    const breadcrumbs = parseBreadcrumbsFromPath('/reports');
    expect(breadcrumbs).toHaveLength(1);
    expect(breadcrumbs[0]).toEqual({
      label: 'Reports',
      href: undefined,
      isCurrent: true,
    });
  });

  it('should handle deep nesting with multiple labels', () => {
    const breadcrumbs = parseBreadcrumbsFromPath(
      '/sessions/101/workflows/202/history'
    );
    expect(breadcrumbs).toHaveLength(3);
    expect(breadcrumbs[0]).toEqual({
      label: 'Sessions',
      href: '/sessions',
      isCurrent: false,
    });
    expect(breadcrumbs[1]).toEqual({
      label: 'Workflows',
      href: '/sessions/101/workflows',
      isCurrent: false,
    });
    expect(breadcrumbs[2]).toEqual({
      label: 'History',
      href: undefined,
      isCurrent: true,
    });
  });

  it('should accumulate paths correctly', () => {
    const breadcrumbs = parseBreadcrumbsFromPath('/sessions/123/workflows/456');
    expect(breadcrumbs[0].href).toBe('/sessions');
    expect(breadcrumbs[1].href).toBeUndefined(); // Current item has no href
  });

  it('should always ensure Sessions is first if present', () => {
    const breadcrumbs = parseBreadcrumbsFromPath('/workflows/123');
    expect(breadcrumbs[0].label).toBe('Sessions');
    expect(breadcrumbs[0].href).toBe('/sessions');
    expect(breadcrumbs[0].isCurrent).toBe(false);
  });

  it('should handle mixed alphanumeric segments', () => {
    const breadcrumbs = parseBreadcrumbsFromPath('/sessions/123/item');
    expect(breadcrumbs).toHaveLength(2);
    expect(breadcrumbs[1].label).toBe('Item');
    expect(breadcrumbs[1].isCurrent).toBe(true);
  });

  it('should handle numeric segment as first element without prepending Sessions', () => {
    const breadcrumbs = parseBreadcrumbsFromPath('/123');
    expect(breadcrumbs).toHaveLength(0);
  });

  it('should fold the literal workflow segment into its neighbours', () => {
    const breadcrumbs = parseBreadcrumbsFromPath(
      '/sessions/SESSION_A92_XP_2024/workflow/wf-982-xk-11'
    );

    expect(breadcrumbs.map((item) => item.label)).toEqual([
      'Sessions',
      'SESSION_A92_XP_2024',
      'Wf-982-xk-11',
    ]);
    expect(breadcrumbs[2].isCurrent).toBe(true);
  });

  it('should apply label overrides to raw segments', () => {
    const breadcrumbs = parseBreadcrumbsFromPath(
      '/sessions/SESSION_A92_XP_2024/workflow/wf-982-xk-11',
      { 'wf-982-xk-11': 'Supply_Chain_Optimizer_V2' }
    );

    expect(breadcrumbs[2].label).toBe('Supply_Chain_Optimizer_V2');
    expect(breadcrumbs[1].label).toBe('SESSION_A92_XP_2024');
  });

  it('should keep intermediate session segment clickable on the workflow page', () => {
    const breadcrumbs = parseBreadcrumbsFromPath(
      '/sessions/SESSION_A92_XP_2024/workflow/wf-982-xk-11'
    );

    expect(breadcrumbs[1].href).toBe('/sessions/SESSION_A92_XP_2024');
    expect(breadcrumbs[1].isCurrent).toBe(false);
  });
});
