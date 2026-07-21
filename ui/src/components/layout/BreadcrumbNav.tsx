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
    <div className="px-spacing-md flex h-8 w-full items-center border-t border-outline-variant bg-surface-container-low p-4">
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
                  <span className="text-xs font-medium text-on-surface">
                    {item.label}
                  </span>
                ) : item.href ? (
                  <Link
                    to={item.href}
                    className="text-xs text-on-surface-variant transition-colors hover:text-on-surface"
                  >
                    {item.label}
                  </Link>
                ) : (
                  <span className="text-xs text-on-surface-variant">
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
