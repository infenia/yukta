// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

/**
 * A tool call the model asked for.
 *
 * @param id the provider's id of the call
 * @param name the tool to call
 * @param arguments the arguments as JSON text
 */
@SuppressWarnings("PMD.ShortVariable")
public record ToolCallRequest(String id, String name, String arguments) {}
