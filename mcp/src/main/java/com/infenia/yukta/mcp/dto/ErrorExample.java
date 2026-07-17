// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

/**
 * Example of a common configuration error, used in creation guides.
 *
 * @param error the error type or name
 * @param cause the cause of the error
 * @param resolution the resolution or fix for the error
 */
public record ErrorExample(String error, String cause, String resolution) {}
