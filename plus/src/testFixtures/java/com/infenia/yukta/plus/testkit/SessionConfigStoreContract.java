// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.session.SessionSummary;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.sessionstore.SessionConfigStore;
import com.infenia.yukta.testing.Fixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What every {@link SessionConfigStore} must do. Extend it in the test of an implementation and
 * return a store that starts empty from {@link #create()}.
 */
public abstract class SessionConfigStoreContract {
  private static final String FIRST = "first";

  private static final String SECOND = "second";

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
        Fixtures.workflow(
            "w", "a workflow", List.of(Fixtures.node("n", "CONSTANT_SOURCE", Map.of())), List.of());
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
  void summariesListEverySessionByIdWithItsWorkflowCount() {
    final SessionConfigStore store = create();
    store.applySessionConfig(session("s2", SECOND)).block();
    store.applySessionConfig(session("s1", FIRST)).block();

    final List<SessionSummary> summaries = store.summaries().collectList().block();

    assertThat(summaries).extracting(SessionSummary::sessionId).containsExactly("s1", "s2");
    assertThat(summaries.getFirst())
        .isEqualTo(
            new SessionSummary(
                "s1", "name-s1", FIRST, "initiator", Map.of("team", "a"), "/tmp", 1));
  }

  @Test
  void appliedConfigurationCanBeReadBack() {
    final SessionConfigStore store = create();

    store.applySessionConfig(session("s1", FIRST)).block();

    assertThat(store.getDescription("s1").block()).isEqualTo(FIRST);
    assertThat(store.getInitiator("s1").block()).isEqualTo("initiator");
    assertThat(store.getProjectPath("s1").block()).isEqualTo("/tmp");
    assertThat(store.getTags("s1").block()).containsEntry("team", "a");
    assertThat(store.getSessionIds().collectList().block()).containsExactly("s1");
  }

  /**
   * The initiator is the one value with a setter: it is set when a run is the first sign of a
   * session.
   */
  @Test
  void initiatorOnNewSession_isReadable() {
    final SessionConfigStore store = create();

    store.setInitiator("s1", "someone").block();

    assertThat(store.getInitiator("s1").block()).isEqualTo("someone");
  }

  @Test
  void sessionsDoNotShareValues() {
    final SessionConfigStore store = create();
    store.applySessionConfig(session("s1", FIRST)).block();
    store.applySessionConfig(session("s2", SECOND)).block();

    assertThat(store.getDescription("s2").block()).isEqualTo(SECOND);
    assertThat(store.getDescription("s1").block()).isEqualTo(FIRST);
    assertThat(store.getSessionIds().collectList().block()).contains("s1", "s2");
    assertThat(store.getAllConfigs("s2").block()).isNotNull();
  }

  @Test
  void applyingAgain_replacesTheValues_butKeepsTheInitiatedTime() throws InterruptedException {
    final SessionConfigStore store = create();
    store.applySessionConfig(session("s1", FIRST)).block();
    final String initiated = store.getInitiatedTime("s1").block();
    Thread.sleep(5);

    store.applySessionConfig(session("s1", SECOND)).block();

    assertThat(initiated).isNotBlank();
    assertThat(store.getInitiatedTime("s1").block()).isEqualTo(initiated);
    assertThat(store.getDescription("s1").block()).isEqualTo(SECOND);
    assertThat(store.getAllConfigs("s1").block().initiatedTime()).isEqualTo(initiated);
  }

  @Test
  void sessionThatWasNeverAppliedIsNotListed() {
    assertThat(create().getSessionIds().collectList().block()).isEmpty();
  }
}
