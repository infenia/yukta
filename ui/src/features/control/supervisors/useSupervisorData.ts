// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useFallbackPoll } from '../context/ControlEventsContext';
import {
  attach,
  createProfile,
  deleteProfile,
  detach,
  getBindings,
  getDecisions,
  getProfiles,
  getSupervisors,
  getTypes,
  replaceProfile,
} from '../api/supervisors';

/** Every key sits under this one, so a control event refreshes them all at once. */
const ROOT = ['control', 'supervisors'] as const;

export const useRunningSupervisors = () => {
  const poll = useFallbackPoll();
  return useQuery({
    queryKey: ROOT,
    queryFn: getSupervisors,
    refetchInterval: poll,
  });
};

export const useBindings = () => {
  const poll = useFallbackPoll();
  return useQuery({
    queryKey: [...ROOT, 'bindings'],
    queryFn: getBindings,
    refetchInterval: poll,
  });
};

export const useProfiles = () =>
  useQuery({ queryKey: [...ROOT, 'profiles'], queryFn: getProfiles });

export const useSupervisorTypes = () =>
  useQuery({
    queryKey: [...ROOT, 'types'],
    queryFn: getTypes,
    staleTime: Infinity,
  });

export const useDecisions = (limit = 30) => {
  const poll = useFallbackPoll();
  return useQuery({
    queryKey: [...ROOT, 'decisions', limit],
    queryFn: () => getDecisions(limit),
    refetchInterval: poll,
  });
};

/** A write that refreshes everything about supervisors when it ends. */
function useWrite<A, R>(fn: (arg: A) => Promise<R>) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSettled: () => client.invalidateQueries({ queryKey: ROOT }),
  });
}

export const useCreateProfile = () => useWrite(createProfile);
export const useReplaceProfile = () => useWrite(replaceProfile);
export const useDeleteProfile = () => useWrite(deleteProfile);
export const useAttach = () => useWrite(attach);
export const useDetach = () => useWrite(detach);
