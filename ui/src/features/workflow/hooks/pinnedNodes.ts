// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { createContext } from 'react';

/** The nodes that hold a pinned test output (editor only); the canvas marks them. */
export const PinnedNodes = createContext<ReadonlySet<string>>(new Set());
