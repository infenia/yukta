// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import org.jspecify.annotations.Nullable;

/**
 * One thing a supervisor should own, as an agent sends it.
 *
 * @param target server, session:<id>, workflow:<s>/<w>, run:<s>/<w> or a section
 *     workflow:<s>/<w>//<selector>,... (node ids, <prefix>.*, group:<name>)
 * @param enabled whether it runs; true when missing
 * @param locked whether only a lock holder may change or remove it; false when missing
 */
public record AssignmentInput(String target, @Nullable Boolean enabled, @Nullable Boolean locked) {}
