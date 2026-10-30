// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Fragment } from 'react';
import { Link, useInRouterContext, useLocation } from 'react-router';
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbList,
  BreadcrumbSeparator,
} from '@/components/ui/breadcrumb';
import { parseBreadcrumbsFromPath } from '@/lib/breadcrumb-utils';

/** The page's trail above its title; a top-level page (one crumb) has none, nor a header shown outside a router. */
export default function BreadcrumbNav({ className }: { className?: string }) {
  return useInRouterContext() ? <Trail className={className} /> : null;
}

function Trail({ className }: { className?: string }) {
  const { pathname } = useLocation();
  const breadcrumbs = parseBreadcrumbsFromPath(pathname);
  if (breadcrumbs.length < 2) return null;

  return (
    <Breadcrumb className={className}>
      <BreadcrumbList className="gap-spacing-sm">
        {breadcrumbs.map((item, index) => (
          <Fragment key={`${item.label}-${index}`}>
            {index > 0 && (
              <BreadcrumbSeparator className="text-outline-variant">
                <span className="material-symbols-outlined text-xs">
                  chevron_right
                </span>
              </BreadcrumbSeparator>
            )}
            <BreadcrumbItem>
              {item.href ? (
                <Link
                  to={item.href}
                  className="text-body-sm text-on-surface-variant transition-standard hover:text-on-surface rounded-sm"
                >
                  {item.label}
                </Link>
              ) : (
                // only the current page has no link
                <span
                  aria-current="page"
                  className="text-body-sm text-on-surface font-medium"
                >
                  {item.label}
                </span>
              )}
            </BreadcrumbItem>
          </Fragment>
        ))}
      </BreadcrumbList>
    </Breadcrumb>
  );
}
