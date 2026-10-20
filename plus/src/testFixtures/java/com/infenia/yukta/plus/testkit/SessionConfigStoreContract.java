// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import com.infenia.yukta.service.session.store.SessionConfigStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What every {@link SessionConfigStore} must do. Extend it in the test of an implementation and
 * return a store that starts empty from {@link #create()}.
 */
@SuppressWarnings("PMD.LawOfDemeter")
public abstract class SessionConfigStoreContract {

  /** Creates the contract. */
  protected SessionConfigStoreContract() {
    super();
  }

  /**
   * A new, empty store.
   *
   * @return the store under test
   */
  protected abstract SessionConfigStore create();

  private static SessionConfigData session(final String sessionId, final String description) {
    final WorkflowDefinition workflow =
        new WorkflowDefinition(
            "w", "a workflow", List.of(new Node("n", "CONSTANT_SOURCE", Map.of())), List.of());
    return new SessionConfigData(
        sessionId,
        "name-" + sessionId,
        description,
        "initiator",
        Map.of("team", "a"),
        "/tmp",
        Map.of("w", workflow));
  }

  @Test
  void appliedConfigurationCanBeReadBack() {
    final SessionConfigStore store = create();

    store.applySessionConfig(session("s1", "first")).block();

    assertThat(store.getDescription("s1").block()).isEqualTo("first");
    assertThat(store.getInitiator("s1").block()).isEqualTo("initiator");
    assertThat(store.getProjectPath("s1").block()).isEqualTo("/tmp");
    assertThat(store.getTags("s1").block()).containsEntry("team", "a");
    assertThat(store.getSessionIds().collectList().block()).containsExactly("s1");
  }

  /**
   * The setters on a session that was never applied. What a setter does to a value that is already
   * set differs between the stores (the in-memory and file stores keep the first description,
   * PostgreSQL overwrites), so it is deliberately not part of the contract.
   */
  @Test
  void settersOnNewSession_makeTheValuesReadable() {
    final SessionConfigStore store = create();

    store.setDescription("s1", "described").block();
    store.setInitiator("s1", "someone").block();
    store.setProjectPath("s1", "/var").block();
    store.setTags("s1", Map.of("env", "prod")).block();
    store.setInitiatedTime("s1", "2026-01-01T00:00:00Z").block();

    assertThat(store.getDescription("s1").block()).isEqualTo("described");
    assertThat(store.getInitiator("s1").block()).isEqualTo("someone");
    assertThat(store.getProjectPath("s1").block()).isEqualTo("/var");
    assertThat(store.getTags("s1").block()).containsOnly(Map.entry("env", "prod"));
    assertThat(store.getInitiatedTime("s1").block()).isEqualTo("2026-01-01T00:00:00Z");
  }

  @Test
  void sessionsDoNotShareValues() {
    final SessionConfigStore store = create();
    store.applySessionConfig(session("s2", "second")).block();

    store.setDescription("s1", "other").block();

    assertThat(store.getDescription("s2").block()).isEqualTo("second");
    assertThat(store.getDescription("s1").block()).isEqualTo("other");
    assertThat(store.getSessionIds().collectList().block()).contains("s2");
    assertThat(store.getAllConfigs("s2").block()).isNotEmpty();
  }

  @Test
  void sessionThatWasNeverAppliedIsNotListed() {
    assertThat(create().getSessionIds().collectList().block()).isEmpty();
  }
}
