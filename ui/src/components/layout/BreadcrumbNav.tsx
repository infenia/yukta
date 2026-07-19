// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useLocation } from 'react-router';
import { Link } from 'react-router';
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbList,
  BreadcrumbSeparator,
} from '@/components/ui/breadcrumb';
import { parseBreadcrumbsFromPath } from '@/lib/breadcrumb-utils';

export default function BreadcrumbNav() {
  const { pathname } = useLocation();
  const breadcrumbs = parseBreadcrumbsFromPath(pathname);

  return (
    <div className="px-spacing-md bg-surface-container-low border-outline-variant flex h-8 w-full items-center border-t p-4">
      <Breadcrumb>
        <BreadcrumbList className="gap-spacing-sm">
          {breadcrumbs.map((item, index) => (
            <>
              {index > 0 && (
                <BreadcrumbSeparator className="text-outline-variant">
                  <span className="material-symbols-outlined text-xs">
                    chevron_right
                  </span>
                </BreadcrumbSeparator>
              )}
              <BreadcrumbItem key={`${item.label}-${index}`}>
                {item.isCurrent ? (
                  <span className="text-on-surface text-xs font-medium">
                    {item.label}
                  </span>
                ) : item.href ? (
                  <Link
                    to={item.href}
                    className="text-on-surface-variant hover:text-on-surface text-xs transition-colors"
                  >
                    {item.label}
                  </Link>
                ) : (
                  <span className="text-on-surface-variant text-xs">
                    {item.label}
                  </span>
                )}
              </BreadcrumbItem>
            </>
          ))}
        </BreadcrumbList>
      </Breadcrumb>
    </div>
  );
}
