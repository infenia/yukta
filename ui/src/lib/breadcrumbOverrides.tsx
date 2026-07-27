// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';

interface BreadcrumbOverridesContextValue {
  overrides: Record<string, string>;
  setOverride: (segment: string, label: string) => void;
  clearOverride: (segment: string) => void;
}

const BreadcrumbOverridesContext = createContext<BreadcrumbOverridesContextValue>({
  overrides: {},
  setOverride: () => undefined,
  clearOverride: () => undefined,
});

/** Provides route-segment label overrides so pages can name dynamic breadcrumb segments. */
export function BreadcrumbOverridesProvider({ children }: { children: ReactNode }) {
  const [overrides, setOverrides] = useState<Record<string, string>>({});

  const setOverride = useCallback((segment: string, label: string) => {
    setOverrides((previous) =>
      previous[segment] === label ? previous : { ...previous, [segment]: label }
    );
  }, []);

  const clearOverride = useCallback((segment: string) => {
    setOverrides((previous) => {
      if (!(segment in previous)) {
        return previous;
      }
      return Object.fromEntries(
        Object.entries(previous).filter(([key]) => key !== segment)
      );
    });
  }, []);

  const value = useMemo(
    () => ({ overrides, setOverride, clearOverride }),
    [overrides, setOverride, clearOverride]
  );

  return (
    <BreadcrumbOverridesContext.Provider value={value}>
      {children}
    </BreadcrumbOverridesContext.Provider>
  );
}

/** Read the current breadcrumb label overrides. */
export function useBreadcrumbOverrides(): Record<string, string> {
  return useContext(BreadcrumbOverridesContext).overrides;
}

/** Override the label of one path segment while the calling component is mounted. */
export function useBreadcrumbOverride(segment: string | undefined, label: string | undefined) {
  const { setOverride, clearOverride } = useContext(BreadcrumbOverridesContext);

  useEffect(() => {
    if (!segment || !label) {
      return undefined;
    }
    setOverride(segment, label);
    return () => clearOverride(segment);
  }, [segment, label, setOverride, clearOverride]);
}
