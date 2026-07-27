// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { BreadcrumbItem, RouteSegmentMap } from '@/types/breadcrumb';

// Map route segments to friendly breadcrumb labels
const ROUTE_LABEL_MAP: RouteSegmentMap = {
  sessions: 'Sessions',
  workflows: 'Workflows',
};

// Literal route segments folded into their neighbours instead of rendering their own crumb,
// e.g. /sessions/:id/workflow/:workflowId renders Sessions > :id > :workflowId
const FOLDED_SEGMENTS = new Set(['workflow']);

/**
 * Parse pathname into breadcrumb items with navigation links.
 * Routes like /sessions/123/workflows/456 become:
 * [Sessions (link to /sessions)] > [Workflows (current)]
 *
 * Optional overrides replace the label of a raw path segment (e.g. a workflow ID
 * segment labelled with the workflow's display name).
 */
export function parseBreadcrumbsFromPath(
  pathname: string,
  overrides: Record<string, string> = {}
): BreadcrumbItem[] {
  // Remove leading/trailing slashes and split
  const segments = pathname.split('/').filter(Boolean).filter((segment) => !FOLDED_SEGMENTS.has(segment));

  if (segments.length === 0) {
    // Root path
    return [
      {
        label: 'Sessions',
        href: undefined, // Root is current
        isCurrent: true,
      },
    ];
  }

  // Pre-compute which numeric segments have non-numeric segments following them
  const hasNonNumericAfter: boolean[] = [];
  for (let i = 0; i < segments.length; i++) {
    let hasNonNumericFollowing = false;
    for (let j = i + 1; j < segments.length; j++) {
      if (!/^\d+$/.test(segments[j])) {
        hasNonNumericFollowing = true;
        break;
      }
    }
    hasNonNumericAfter[i] = hasNonNumericFollowing;
  }

  const breadcrumbs: BreadcrumbItem[] = [];
  let accumulatedPath = '';
  let sessionsPrependNeeded = false;

  segments.forEach((segment, index) => {
    accumulatedPath += `/${segment}`;
    const isLast = index === segments.length - 1;
    const isNumeric = /^\d+$/.test(segment);

    // Skip numeric IDs (params like 123, 456) — fold into parent label
    if (isNumeric) {
      // Update the last breadcrumb to mark it as current if this is the last segment
      if (breadcrumbs.length > 0) {
        const lastBreadcrumb = breadcrumbs[breadcrumbs.length - 1];
        if (isLast) {
          lastBreadcrumb.isCurrent = true;
          lastBreadcrumb.href = undefined; // Current items are not clickable
        }
      }
      return;
    }

    // Overrides win, then the friendly label map, then the capitalized segment
    const hasOwn = Object.prototype.hasOwnProperty;
    const label =
      (hasOwn.call(overrides, segment) && overrides[segment]) ||
      (hasOwn.call(ROUTE_LABEL_MAP, segment) && ROUTE_LABEL_MAP[segment]) ||
      capitalizeFirst(segment);

    // Determine if Sessions needs to be prepended
    if (breadcrumbs.length === 0 && label !== 'Sessions') {
      const isMappedRoute = hasOwn.call(ROUTE_LABEL_MAP, segment);
      // Need to prepend Sessions for mapped routes or multi-segment paths
      sessionsPrependNeeded = isMappedRoute || segments.length > 1;
    }

    // Determine if this breadcrumb is current: it's current if it's the last non-numeric-following segment
    const isCurrent = isLast || !hasNonNumericAfter[index];

    breadcrumbs.push({
      label,
      href: isCurrent ? undefined : accumulatedPath, // Current items have no href
      isCurrent,
    });
  });

  // Prepend Sessions if needed
  if (sessionsPrependNeeded) {
    breadcrumbs.unshift({
      label: 'Sessions',
      href: '/sessions',
      isCurrent: false,
    });
  }

  return breadcrumbs;
}

function capitalizeFirst(str: string): string {
  return str.charAt(0).toUpperCase() + str.slice(1);
}
