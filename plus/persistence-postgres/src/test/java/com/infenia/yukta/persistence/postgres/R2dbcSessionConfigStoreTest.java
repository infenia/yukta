// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.config.SessionConfigProperties;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.session.SessionSnapshot;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class R2dbcSessionConfigStoreTest extends PostgresTestSupport {

  private R2dbcSessionConfigStore store;

  private static WorkflowDefinition workflow() {
    return new WorkflowDefinition(
        "w1",
        "d",
        List.of(new WorkflowDefinition.Node("n1", "CONSTANT_SOURCE", Map.of())),
        List.of());
  }

  @BeforeEach
  void setUp() {
    final ObjectMapper mapper = new ObjectMapper();
    final SessionConfigProperties props = new SessionConfigProperties();
    props.setBaseDir("/data");
    store =
        new R2dbcSessionConfigStore(
            db, mapper, props, new R2dbcWorkflowDefinitionStore(db, mapper));
  }

  @Test
  void applyThatFailsHalfway_leavesNothingBehind() {
    // a NUL character cannot be stored in PostgreSQL text, so the second workflow fails
    final Map<String, WorkflowDefinition> workflows =
        new java.util.concurrent.ConcurrentHashMap<>();
    workflows.put("w1", workflow());
    workflows.put("bad", new WorkflowDefinition("bad\u0000id", "d", List.of(), List.of()));

    assertThatThrownBy(
            () ->
                store
                    .applySessionConfig(
                        new SessionConfigData(
                            "s2", "name", "desc", "alice", Map.of(), "/p", workflows))
                    .block())
        .isNotNull();

    assertThat(store.getSessionIds().collectList().block()).isEmpty();
    assertThat(new R2dbcWorkflowDefinitionStore(db, new ObjectMapper()).findAll("s2").block())
        .isEmpty();
  }

  @Test
  void unknownSessionHasEmptyValues() {
    assertThat(store.getProjectPath("none").block()).isEmpty();
    assertThat(store.getDescription("none").block()).isEmpty();
    assertThat(store.getInitiator("none").block()).isEmpty();
    assertThat(store.getInitiatedTime("none").block()).isEmpty();
    assertThat(store.getTags("none").block()).isEmpty();
    assertThat(store.getAllConfigs("none").block()).isNull();
    assertThat(store.getSessionIds().collectList().block()).isEmpty();
  }

  @Test
  void applyStoresTheConfigurationAndItsWorkflows() {
    store
        .applySessionConfig(
            new SessionConfigData(
                "s1",
                "name",
                "desc",
                "alice",
                Map.of("team", "core"),
                "/p",
                Map.of("w1", workflow())))
        .block();

    final SessionSnapshot all = store.getAllConfigs("s1").block();

    assertThat(all.sessionId()).isEqualTo("s1");
    assertThat(all.projectPath()).isEqualTo("/p");
    assertThat(all.initiator()).isEqualTo("alice");
    assertThat(all.description()).isEqualTo("desc");
    assertThat(all.name()).isEqualTo("name");
    assertThat(all.tags()).isEqualTo(Map.of("team", "core"));
    assertThat(all.executionTimeout()).isEqualTo(3600L);
    assertThat(all.fileLogDir()).isEqualTo("/data/modified-files");
    assertThat(all.resultLogDir()).isEqualTo("/data/results");
    assertThat(all.initiatedTime()).isNotBlank();
    assertThat(all.workflows()).isEqualTo(Map.of("w1", workflow()));
    assertThat(store.getSessionIds().collectList().block()).containsExactly("s1");

    // applying again replaces the configuration
    store
        .applySessionConfig(
            new SessionConfigData("s1", "n2", "d2", "bob", null, null, Map.of("w1", workflow())))
        .block();
    assertThat(store.getInitiator("s1").block()).isEqualTo("bob");
    assertThat(store.getTags("s1").block()).isEmpty();
    assertThat(store.getProjectPath("s1").block()).isEmpty();
  }

  @Test
  void projectPathCanAlwaysChange_otherValuesOnlyWhileEmpty() {
    store.setProjectPath("s", "/one").block();
    store.setProjectPath("s", "/two").block();
    assertThat(store.getProjectPath("s").block()).isEqualTo("/two");

    store.setDescription("s", "first").block();
    store.setDescription("s", "second").block();
    store.setInitiator("s", "alice").block();
    store.setInitiator("s", "bob").block();
    store.setInitiatedTime("s", "t1").block();
    store.setInitiatedTime("s", "t2").block();
    store.setTags("s", Map.of("a", "1")).block();
    store.setTags("s", Map.of("b", "2")).block();

    assertThat(store.getDescription("s").block()).isEqualTo("first");
    assertThat(store.getInitiator("s").block()).isEqualTo("alice");
    assertThat(store.getInitiatedTime("s").block()).isEqualTo("t1");
    assertThat(store.getTags("s").block()).isEqualTo(Map.of("a", "1"));
  }

  @Test
  void nullValuesAreIgnored() {
    store.setDescription("s", null).block();
    store.setInitiator("s", null).block();
    store.setInitiatedTime("s", null).block();
    store.setTags("s", null).block();

    assertThat(store.getSessionIds().collectList().block()).isEmpty();
  }

  @Test
  void settersCreateTheSessionWhenNew() {
    store.setTags("new", Map.of("k", "v")).block();

    assertThat(store.getSessionIds().collectList().block()).containsExactly("new");
    assertThat(store.getExecutionTimeout("new").block()).isEqualTo(3600L);
  }

  @Test
  void removeSession_forgetsTheSessionAndIsQuietWhenItIsUnknown() {
    store
        .applySessionConfig(
            new SessionConfigData("gone", "n", "d", "alice", Map.of(), "/p", Map.of()))
        .block();

    store.removeSession("gone").block();
    store.removeSession("never-was").block();

    assertThat(store.getSessionIds().collectList().block()).doesNotContain("gone");
  }
}
