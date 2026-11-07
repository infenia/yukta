// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.api;

/**
 * A log line with its position in the run's whole log. The position counts every line the run ever
 * wrote, also those a store dropped to stay within its bounds, so a follower's cursor stays valid.
 *
 * @param sequence the line's position, from 0
 * @param entry the line
 */
public record IndexedLog(long sequence, PluginLogEntry entry) {}
