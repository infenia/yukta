// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

/**
 * What {@code mark_notifications_read} did.
 *
 * @param marked how many notifications were marked read for the caller; an id the caller cannot see
 *     is ignored
 */
public record MarkedRead(int marked) {}
