// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

export interface BreadcrumbItem {
  label: string;
  href?: string;
  isCurrent: boolean;
}

export interface RouteSegmentMap {
  [key: string]: string;
}
