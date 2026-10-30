// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Fragment } from 'react';
import { useLocation } from 'react-router';
import { Link } from 'react-router';
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbList,
  BreadcrumbSeparator,
} from '@/components/ui/breadcrumb';
import { parseBreadcrumbsFromPath } from '@/lib/breadcrumb-utils';
import { useBreadcrumbOverrides } from '@/lib/breadcrumbOverrides';

export default function BreadcrumbNav() {
  const { pathname } = useLocation();
  const overrides = useBreadcrumbOverrides();
  const breadcrumbs = parseBreadcrumbsFromPath(pathname, overrides);

  return (
    <div className="px-spacing-md border-border flex h-10 w-full items-center border-t sm:px-6">
      <Breadcrumb>
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
                {item.isCurrent ? (
                  <span className="text-body-sm text-on-surface font-medium">
                    {item.label}
                  </span>
                ) : item.href ? (
                  <Link
                    to={item.href}
                    className="text-body-sm text-on-surface-variant hover:text-on-surface transition-standard rounded-sm"
                  >
                    {item.label}
                  </Link>
                ) : (
                  <span className="text-body-sm text-on-surface-variant">
                    {item.label}
                  </span>
                )}
              </BreadcrumbItem>
            </Fragment>
          ))}
        </BreadcrumbList>
      </Breadcrumb>
    </div>
  );
}
