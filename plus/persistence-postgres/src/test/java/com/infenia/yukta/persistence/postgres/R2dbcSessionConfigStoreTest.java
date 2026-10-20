// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.config.SessionConfigProperties;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals", "PMD.LawOfDemeter"})
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

    final Map<String, Object> all = store.getAllConfigs("s1").block();

    assertThat(all)
        .containsEntry("sessionId", "s1")
        .containsEntry("projectPath", "/p")
        .containsEntry("initiator", "alice")
        .containsEntry("description", "desc")
        .containsEntry("name", "name")
        .containsEntry("tags", Map.of("team", "core"))
        .containsEntry("executionTimeout", 3600L)
        .containsEntry("fileLogDir", "/data/modified-files")
        .containsEntry("resultLogDir", "/data/results");
    assertThat(all.get("initiatedTime")).asString().isNotBlank();
    assertThat(all.get("workflows")).isEqualTo(Map.of("w1", workflow()));
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
}
