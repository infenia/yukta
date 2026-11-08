// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.input.DefaultAnswerAuthorizer;
import com.infenia.yukta.service.input.EligibleSupervisors;
import com.infenia.yukta.service.supervisor.hierarchy.SupervisorResolver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Tells the input broker which supervisors are attached, from {@link AttachedSupervisors}.
 *
 * @since 0.1.0-alpha.4
 */
@Component
public class AttachedSupervisorsDirectory implements EligibleSupervisors {

  /** The attached supervisors. */
  private final AttachedSupervisors attached;

  /** Finds the start payload of a run; null where no run is known. */
  @Nullable private final ExecutionControlStore runs;

  @Nullable private ObjectProvider<SupervisorResolver> resolverProvider;

  /**
   * Creates the directory.
   *
   * @param attached the attached supervisors
   * @param runs finds the start payload of the run that raised a request
   */
  @Autowired
  public AttachedSupervisorsDirectory(
      final AttachedSupervisors attached, final ExecutionControlStore runs) {
    this.attached = attached;
    this.runs = runs;
  }

  /**
   * Creates a directory that does not know which supervisor started a run.
   *
   * @param attached the attached supervisors
   */
  public AttachedSupervisorsDirectory(final AttachedSupervisors attached) {
    this.attached = attached;
    this.runs = null;
  }

  /**
   * Gives the directory the stored supervisors, for the parents of a supervisor.
   *
   * @param resolver looked up late, because the service that owns it needs the broker
   */
  @Autowired(required = false)
  public void setResolver(final ObjectProvider<SupervisorResolver> resolver) {
    this.resolverProvider = resolver;
  }

  @Override
  public List<Candidate> bound() {
    return attached.stream().map(s -> new Candidate(s.id(), s.scope())).toList();
  }

  @Override
  public boolean mayReceive(final Candidate candidate, final InputRequest request) {
    if (runs == null) {
      return true;
    }
    final Map<String, Object> payload =
        runs.findByExecutionId(request.executionId())
            .map(ExecutionControl::payload)
            .orElse(Map.of());
    if (!(payload.get(DefaultAnswerAuthorizer.DEPTH_KEY) instanceof Number depth)
        || depth.intValue() <= 0) {
      return true;
    }
    final Object starter = payload.get(DefaultAnswerAuthorizer.STARTER_KEY);
    // the supervisor that started the run is gone or unknown: only people may answer
    return attached.stream().anyMatch(s -> s.id().equals(starter))
        && outranks(candidate.id(), String.valueOf(starter));
  }

  @Override
  public List<String> parentsOf(final String candidateId) {
    final SupervisorResolver resolver =
        resolverProvider == null ? null : resolverProvider.getIfAvailable();
    if (resolver == null) {
      return List.of();
    }
    final Optional<BoundSupervisor> child =
        attached.stream().filter(s -> s.id().equals(candidateId)).findFirst();
    final List<String> parents =
        child
            .flatMap(c -> resolver.supervisor(c.attachment().supervisorId()))
            .map(Supervisor::parents)
            .orElse(List.of());
    return attached.stream()
        .filter(s -> parents.contains(s.attachment().supervisorId()))
        .map(BoundSupervisor::id)
        .toList();
  }
}
