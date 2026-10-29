// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

/**
 * The execution limits in force and how much of them is in use.
 *
 * @param maxConcurrent most executions running at once, 0 for unlimited
 * @param maxPerWorkflow most executions of one workflow running at once, 0 for unlimited
 * @param running executions holding a slot now
 * @param queued starts waiting for a slot now
 * @param queueCapacity how many starts may wait
 */
public record SchedulerLimitsResult(
    int maxConcurrent, int maxPerWorkflow, int running, int queued, int queueCapacity) {}
