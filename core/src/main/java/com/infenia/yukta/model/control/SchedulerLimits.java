// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.control;

/**
 * The execution limits in force and how much of them is in use. A limit of 0 means unlimited.
 *
 * @param maxConcurrent most executions running at once
 * @param maxPerWorkflow most executions of one workflow running at once
 * @param running executions holding a slot now
 * @param queued starts waiting for a slot now
 * @param queueCapacity how many starts may wait
 */
public record SchedulerLimits(
    int maxConcurrent, int maxPerWorkflow, int running, int queued, int queueCapacity) {}
