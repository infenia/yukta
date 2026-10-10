// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

/**
 * One conversation turn.
 *
 * @param role "user" or "assistant" (anything else is treated as "user")
 * @param content the turn text
 */
public record ChatTurn(String role, String content) {}
