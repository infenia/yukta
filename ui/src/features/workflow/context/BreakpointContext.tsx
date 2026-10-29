// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from 'react';

/** A breakpoint the user set from this tab: the node pauses when `field` equals `equals`. */
export interface Breakpoint {
  field: string;
  equals: string;
}

interface BreakpointStore {
  /** The breakpoint set on a node of a run in this tab, if any. */
  get: (executionId: string, nodeId: string) => Breakpoint | undefined;
  set: (executionId: string, nodeId: string, breakpoint: Breakpoint) => void;
  clear: (executionId: string, nodeId: string) => void;
}

const key = (executionId: string, nodeId: string) => `${executionId}:${nodeId}`;

const NONE: BreakpointStore = {
  get: () => undefined,
  set: () => {},
  clear: () => {},
};

const BreakpointContext = createContext<BreakpointStore>(NONE);

/**
 * Remembers the breakpoints set from this tab. The server does not report active breakpoints yet
 * (#1176), so this is the only source; when it does, `useBreakpoint` is the one place to read
 * `TaskProgress.breakpoint` instead.
 */
export function BreakpointProvider({ children }: { children: ReactNode }) {
  const [breakpoints, setBreakpoints] = useState<
    ReadonlyMap<string, Breakpoint>
  >(new Map());

  const set = useCallback(
    (executionId: string, nodeId: string, breakpoint: Breakpoint) => {
      setBreakpoints((previous) =>
        new Map(previous).set(key(executionId, nodeId), breakpoint)
      );
    },
    []
  );
  const clear = useCallback((executionId: string, nodeId: string) => {
    setBreakpoints((previous) => {
      const next = new Map(previous);
      next.delete(key(executionId, nodeId));
      return next;
    });
  }, []);
  const value = useMemo<BreakpointStore>(
    () => ({
      get: (executionId, nodeId) => breakpoints.get(key(executionId, nodeId)),
      set,
      clear,
    }),
    [breakpoints, set, clear]
  );

  return (
    <BreakpointContext.Provider value={value}>
      {children}
    </BreakpointContext.Provider>
  );
}

/** The breakpoint store of the nearest provider (an empty one outside any). */
export const useBreakpoints = () => useContext(BreakpointContext);

/** The breakpoint on one node of a run, if any. */
export function useBreakpoint(
  executionId: string | null,
  nodeId: string
): Breakpoint | undefined {
  const store = useBreakpoints();
  return executionId ? store.get(executionId, nodeId) : undefined;
}
