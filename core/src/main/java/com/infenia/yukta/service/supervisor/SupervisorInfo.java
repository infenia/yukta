// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import org.jspecify.annotations.Nullable;

/**
 * What the control bus knows about one running supervisor.
 *
 * @param id the id in the audit log
 * @param kind {@code CONTROL_PLANE} for a control-plane plugin, otherwise {@code SUPERVISOR}
 * @param type the implementing class
 * @param order the review order, lowest first
 * @param dryRun whether its commands are only recorded
 * @param maxCommandsPerSecond how many commands it may issue per second
 * @param applied commands applied since startup
 * @param rejected commands that were refused
 * @param recorded commands only recorded because of dry-run
 * @param rateLimited commands dropped by the rate limit
 * @param scope the runs it watches: {@code GLOBAL}, {@code SESSION} or {@code WORKFLOW}
 * @param scopeSessionId the session it is limited to, or {@code null}
 * @param scopeWorkflowId the workflow it is limited to, or {@code null}
 */
public record SupervisorInfo(
    String id,
    String kind,
    String type,
    int order,
    boolean dryRun,
    int maxCommandsPerSecond,
    long applied,
    long rejected,
    long recorded,
    long rateLimited,
    String scope,
    @Nullable String scopeSessionId,
    @Nullable String scopeWorkflowId) {}
