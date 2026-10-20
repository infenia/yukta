// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fetchApi } from '@/lib/apiClient';
import type { EditionInfo } from '../types';

export const getEdition = () => fetchApi<EditionInfo>('/api/edition');
