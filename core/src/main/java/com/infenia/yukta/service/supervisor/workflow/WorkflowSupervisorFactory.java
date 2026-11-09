// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.workflow;

import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorCategory;
import com.infenia.yukta.plugin.control.SupervisorDescriptor;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.ConfigField.Type;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.service.redact.InputRedactor;
import com.infenia.yukta.service.sessionstore.InternalSessions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Makes supervisors that are workflows of an internal session. Settings: {@code sessionId} (must be
 * an internal session), {@code workflowId} (must exist), {@code events} (event type names such as
 * {@code InputRequested}), {@code maxConcurrent} (default 2, at most the reserved slots), {@code
 * timeout} in seconds (default 60), next to {@code allowedCommands}, {@code canAnswer} and {@code
 * maxRisk}. A workflow supervisor may send no command until its supervisor names some.
 *
 * <p>That the session is internal and the workflow exists is checked when the supervisor is saved
 * ({@link WorkflowSupervisorChecks}) and again before each run.
 */
@Component
@RequiredArgsConstructor
public class WorkflowSupervisorFactory implements SupervisorFactory {

  /** The supervisor type in a supervisor. */
  public static final String TYPE_NAME = "workflow";

  private final SupervisorWorkflowRunner runner;

  private final InternalSessions internal;

  private final InputRedactor redactor;

  @Override
  public String type() {
    return TYPE_NAME;
  }

  @Override
  public ExecutionSupervisor create(final Supervisor supervisor) {
    final WorkflowSettings settings = WorkflowSettings.read(supervisor, runner.reservedSlots());
    final SupervisorCapabilities capabilities =
        SupervisorCapabilities.from(supervisor, defaultAllowedCommands());
    return new WorkflowSupervisor(
        supervisor.id(), settings, runner, internal, redactor, capabilities.allowedCommands());
  }

  @Override
  public Set<String> defaultAllowedCommands() {
    return Set.of();
  }

  @Override
  public SupervisorDescriptor describe() {
    return new SupervisorDescriptor(
        "Workflow",
        "A workflow in an internal session decides: it is started for each matching event, and"
            + " what its last node returns is the decision.",
        "Use it when the decision needs steps: calling a service, asking a model, or waiting.",
        SupervisorCategory.ON_THIS_SERVER,
        Optional.of(new UiDesign("teal", "schema", "Workflow", 140, 80)),
        Map.of(
            "sessionId",
            "supervisors",
            "workflowId",
            "triage",
            "events",
            List.of("InputRequested")));
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.required(
            "sessionId", Type.STRING, "An internal session that holds the workflow"),
        ConfigField.required("workflowId", Type.STRING, "A workflow of that session"),
        ConfigField.required(
            "events", Type.LIST, "The event types that start it, such as InputRequested"),
        ConfigField.optional(
            "when", Type.EXPRESSION, null, "A read-only SpEL condition over the redacted event"),
        ConfigField.optional("maxConcurrent", Type.NUMBER, 2, "How many runs may go at once"),
        ConfigField.optional("timeout", Type.NUMBER, 60, "Seconds one run may take, 1 to 600"));
  }
}
