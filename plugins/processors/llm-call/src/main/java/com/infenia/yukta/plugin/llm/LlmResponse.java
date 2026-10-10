// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

/**
 * Raw model answer.
 *
 * @param text the generated text
 * @param inputTokens prompt tokens, 0 when unreported
 * @param outputTokens completion tokens, 0 when unreported
 * @param model the model that answered
 */
public record LlmResponse(String text, long inputTokens, long outputTokens, String model) {}
