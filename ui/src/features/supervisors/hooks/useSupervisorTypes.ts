// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getSupervisorType,
  getSupervisorTypePresets,
  getSupervisorTypes,
  getSupervisorTypeUsage,
} from '../api/supervisorTypes';
import {
  getOwnership,
  getSupervision,
  type ControlTarget,
} from '../api/supervision';
import {
  createSupervisor,
  getAttachments,
  getSupervisor,
  getSupervisorDecisions,
  getSupervisorVersions,
  getSupervisors,
  rollbackSupervisor,
  replaceSupervisor,
  setAttachmentEnabled,
  type AttachmentFilter,
} from '../api/supervisors';

/** Every key sits under this one, so a write refreshes them all. */
const ROOT = ['supervisors'] as const;

export const useSupervisorTypes = () =>
  useQuery({ queryKey: [...ROOT, 'types'], queryFn: getSupervisorTypes });

export const useSupervisorType = (type: string | null) =>
  useQuery({
    queryKey: [...ROOT, 'types', type],
    queryFn: () => getSupervisorType(type as string),
    enabled: Boolean(type),
  });

/** The supervisors wired to a decision maker. */
export const useSupervisorTypeUsage = (type: string) =>
  useQuery({
    queryKey: [...ROOT, 'types', type, 'usage'],
    queryFn: () => getSupervisorTypeUsage(type),
  });

/** The presets of a decision maker. */
export const useSupervisorTypePresets = (type: string) =>
  useQuery({
    queryKey: [...ROOT, 'types', type, 'presets'],
    queryFn: () => getSupervisorTypePresets(type),
  });

/** The kept versions of a supervisor, newest first. */
export const useSupervisorVersions = (id: string) =>
  useQuery({
    queryKey: [...ROOT, 'versions', id],
    queryFn: () => getSupervisorVersions(id),
  });

export const useSupervisors = () =>
  useQuery({ queryKey: [...ROOT, 'list'], queryFn: getSupervisors });

export const useSupervisor = (id: string | undefined) =>
  useQuery({
    queryKey: [...ROOT, 'one', id],
    queryFn: () => getSupervisor(id as string),
    enabled: Boolean(id),
  });

export const useAttachments = (filter: AttachmentFilter = {}) =>
  useQuery({
    queryKey: [...ROOT, 'attachments', filter],
    queryFn: () => getAttachments(filter),
  });

/** The owners of a workflow's units; off until both ids are known. */
export const useOwnership = (session: string | null, workflow: string | null) =>
  useQuery({
    queryKey: ['supervision', 'ownership', session, workflow],
    queryFn: () => getOwnership(session as string, workflow as string),
    enabled: Boolean(session && workflow),
  });

/** Who may send each command to a run, a node or the server; not retried, an old server lacks it. */
export const useSupervision = (target: ControlTarget, enabled = true) =>
  useQuery({
    queryKey: [
      'supervision',
      target.execution ?? null,
      target.node ?? null,
      target.session ?? null,
      target.workflow ?? null,
    ],
    queryFn: () => getSupervision(target),
    enabled,
    retry: false,
    staleTime: 10_000,
  });

export const useSupervisorDecisions = (supervisor?: string) =>
  useQuery({
    queryKey: [...ROOT, 'decisions', supervisor ?? null],
    queryFn: () => getSupervisorDecisions(supervisor, 200),
  });

/** A write that refreshes everything about supervisors when it ends. */
function useWrite<A, R>(fn: (arg: A) => Promise<R>) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSettled: () => client.invalidateQueries({ queryKey: ROOT }),
  });
}

export const useCreateSupervisor = () => useWrite(createSupervisor);
export const useReplaceSupervisor = () => useWrite(replaceSupervisor);
export const useSetAttachmentEnabled = () =>
  useWrite((a: { id: string; enabled: boolean }) =>
    setAttachmentEnabled(a.id, a.enabled)
  );
export const useRollbackSupervisor = () => useWrite(rollbackSupervisor);
