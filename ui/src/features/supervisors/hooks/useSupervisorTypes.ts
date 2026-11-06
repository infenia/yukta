// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { getSupervisorType, getSupervisorTypes } from '../api/supervisorTypes';
import { getSupervisors } from '../api/supervisors';

export const useSupervisorTypes = () =>
  useQuery({
    queryKey: ['supervisors', 'types'],
    queryFn: getSupervisorTypes,
  });

export const useSupervisorType = (type: string | null) =>
  useQuery({
    queryKey: ['supervisors', 'types', type],
    queryFn: () => getSupervisorType(type as string),
    enabled: Boolean(type),
  });

export const useSupervisors = () =>
  useQuery({ queryKey: ['supervisors', 'list'], queryFn: getSupervisors });
