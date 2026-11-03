// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.workflow;

import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import com.infenia.yukta.service.redact.InputRedactor;
import com.infenia.yukta.service.session.InternalSessions;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Makes supervisors that are workflows of an internal session. Settings: {@code sessionId} (must be
 * an internal session), {@code workflowId} (must exist), {@code events} (event type names such as
 * {@code InputRequested}), {@code maxConcurrent} (default 2, at most the reserved slots), {@code
 * timeout} in seconds (default 60), next to {@code allowedCommands}, {@code canAnswer} and {@code
 * maxRisk}. A workflow supervisor may send no command until its profile names some.
 *
 * <p>That the session is internal and the workflow exists is checked when the profile is saved
 * ({@link WorkflowSupervisorChecks}) and again before each run.
 */
@Component
@RequiredArgsConstructor
public class WorkflowSupervisorFactory implements SupervisorFactory {

  /** The supervisor type in a profile. */
  public static final String PROFILE_TYPE = "workflow";

  private final SupervisorWorkflowRunner runner;

  private final InternalSessions internal;

  private final InputRedactor redactor;

  @Override
  public String type() {
    return PROFILE_TYPE;
  }

  @Override
  public ExecutionSupervisor create(final SupervisorProfile profile) {
    final WorkflowSettings settings = WorkflowSettings.read(profile, runner.reservedSlots());
    final SupervisorCapabilities capabilities =
        SupervisorCapabilities.from(profile, defaultAllowedCommands());
    return new WorkflowSupervisor(
        profile.id(), settings, runner, internal, redactor, capabilities.allowedCommands());
  }

  @Override
  public Set<String> defaultAllowedCommands() {
    return Set.of();
  }
}
