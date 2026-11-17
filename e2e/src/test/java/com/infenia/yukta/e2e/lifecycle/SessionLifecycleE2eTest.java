// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.Api.Reply;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.Scenarios;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A session from validation to deletion, its workflow versions, and the catalogs a user picks from.
 */
@E2eTest
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class SessionLifecycleE2eTest {

  private static final tools.jackson.databind.json.JsonMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();
  private static final String DRAFT =
      "{\"workflowId\":\"w\",\"description\":\"d\",\"nodes\":["
          + "{\"nodeId\":\"t\",\"type\":\"CONSTANT_SOURCE\",\"config\":{}},"
          + "{\"nodeId\":\"e\",\"type\":\"CONSOLE_TERMINAL\",\"config\":{}}],"
          + "\"edges\":[{\"source\":\"t\",\"target\":\"e\"}]}";
  private static final String VERSIONS = "/api/sessions/{s}/workflows/w/versions";

  @Test
  void sessionIsValidatedCreatedReadListedUpdatedAndDeleted(
      final Api api, final Scenarios scenarios) {
    final String id = Scenarios.unique("session");
    final String definition = Workflows.echo(id, "hi");
    assertThat(
            api.data(
                    api.client()
                        .post()
                        .uri("/api/sessions/validate")
                        .header("Content-Type", "application/json")
                        .bodyValue(DRAFT)
                        .exchange())
                .path("valid")
                .asBoolean())
        .isTrue();

    scenarios.applySession(definition);

    final JsonNode one = api.data(api.client().get().uri("/api/sessions/{s}", id).exchange());
    assertThat(one.path("workflowIds").get(0).asString()).isEqualTo("w");
    assertThat(api.call("GET", "/api/sessions", null).body()).contains(id);
    assertThat(api.call("GET", "/api/sessions/summaries", null).body()).contains(id);
    assertThat(
            api.data(api.client().get().uri("/api/sessions/{s}/workflows", id).exchange())
                .get(0)
                .path("nodeCount")
                .asInt())
        .isEqualTo(3);
    assertThat(
            api.data(api.client().get().uri("/api/sessions/{s}/workflows/w", id).exchange())
                .path("nodes"))
        .hasSize(3);
    assertThat(
            api.data(api.client().get().uri("/api/sessions/{s}/workflows/w/graph", id).exchange())
                .path("nodes")
                .get(0)
                .has("category"))
        .isTrue();

    final String updated = Workflows.echo(id, "changed");
    scenarios.applySession(updated);
    assertThat(api.call("GET", "/api/sessions/" + id + "/workflows/w", null).body())
        .contains("echo changed");

    assertThat(api.call("DELETE", "/api/sessions/" + id, null).status()).isEqualTo(204);
    api.failure(api.client().get().uri("/api/sessions/{s}", id).exchange(), 404);
  }

  @Test
  void theConfigOfSessionCanBeAppliedAgain(final Api api, final Scenarios scenarios) {
    final String id = Scenarios.unique("session");
    scenarios.applySession(Workflows.echo(id, "hi"));

    final String exported = api.call("GET", "/api/sessions/" + id + "/config", null).body();
    final String config = JSON.readTree(exported).path("data").toString();

    assertThat(api.call("POST", "/api/sessions", config).status()).as(config).isEqualTo(200);
  }

  @Test
  void brokenDraftIsReportedNotRefused(final Api api) {
    final Reply reply =
        api.call(
            "POST", "/api/sessions/validate", DRAFT.replace("CONSTANT_SOURCE", "NO_SUCH_PLUGIN"));

    assertThat(reply.status()).isEqualTo(200);
    assertThat(JSON.readTree(reply.body()).path("data").path("valid").asBoolean()).isFalse();
  }

  @Test
  void invalidSessionIs400WithTheFieldsNamed(final Api api) {
    final Reply reply = api.call("POST", "/api/sessions", "{}");

    assertThat(reply.status()).isEqualTo(400);
    assertThat(reply.body()).contains("sessionId");
  }

  @Test
  void workflowCanBeDeletedFromSession(final Api api, final Scenarios scenarios) {
    final String id = Scenarios.unique("session");
    scenarios.applySession(Workflows.echo(id, "hi"));

    assertThat(api.call("DELETE", "/api/sessions/" + id + "/workflows/w", null).status())
        .isIn(200, 204);
    api.failure(api.client().get().uri("/api/sessions/{s}/workflows/w", id).exchange(), 404);
  }

  @Test
  void workingDirectoryIsChecked(final Api api) {
    assertThat(
            api.data(
                    api.client()
                        .post()
                        .uri("/api/sessions/validate/project-path")
                        .header("Content-Type", "application/json")
                        .bodyValue("{\"projectPath\":\"/tmp\"}")
                        .exchange())
                .path("exists")
                .asBoolean())
        .isTrue();
  }

  @Test
  void versionsAreListedNotedComparedAndRolledBack(final Api api, final Scenarios scenarios) {
    final String id = Scenarios.unique("session");
    scenarios.applySession(Workflows.echo(id, "one"));
    scenarios.applySession(Workflows.echo(id, "two"));

    final JsonNode versions = api.data(api.client().get().uri(VERSIONS, id).exchange());
    assertThat(versions.size()).isGreaterThanOrEqualTo(2);
    final String newest = versions.get(0).path("id").asString();
    final String oldest = versions.get(versions.size() - 1).path("id").asString();

    final Reply note =
        api.call(
            "POST",
            "/api/sessions/" + id + "/workflows/w/versions/note",
            "{\"version\":\"" + oldest + "\",\"note\":\"first\"}");
    assertThat(note.status()).as(note.body()).isEqualTo(200);
    assertThat(api.call("GET", "/api/sessions/" + id + "/workflows/w/versions", null).body())
        .contains("first");

    final JsonNode diff =
        api.data(
            api.client()
                .get()
                .uri(VERSIONS + "/diff?from={a}&to={b}", id, oldest, newest)
                .exchange());
    assertThat(diff.has("addedNodes")).isTrue();
    assertThat(diff.has("changedNodes")).isTrue();

    final Reply rolled =
        api.call("POST", "/api/sessions/" + id + "/workflows/w/rollback?version=" + oldest, null);
    assertThat(rolled.status()).as(rolled.body()).isEqualTo(200);
    assertThat(api.call("GET", "/api/sessions/" + id + "/workflows/w", null).body())
        .contains("echo one");
    assertThat(
            api.call("POST", "/api/sessions/" + id + "/workflows/w/rollback?version=nope", null)
                .status())
        .isEqualTo(404);
    assertThat(api.call("GET", "/api/sessions/" + id + "/workflows/w/versions/diff", null).status())
        .isEqualTo(400);
  }

  @Test
  void everyTemplateNamesPluginsThatExist(final Api api) {
    final JsonNode templates = api.data(api.client().get().uri("/api/templates").exchange());
    final String plugins = api.call("GET", "/api/plugins", null).body();

    assertThat(templates).isNotEmpty();
    for (final JsonNode template : templates) {
      for (final JsonNode type : template.path("pluginTypes")) {
        assertThat(plugins)
            .as("template %s", template.path("id").asString())
            .contains(type.asString());
      }
    }
    final String first = templates.get(0).path("id").asString();
    assertThat(api.call("GET", "/api/templates/" + first, null).status()).isEqualTo(200);
    assertThat(api.call("GET", "/api/templates/e2e-nope", null).status()).isEqualTo(404);
  }

  @Test
  void theCatalogHasTheCoreSetOfPluginsAndDetailsOfEach(final Api api) {
    final String plugins = api.call("GET", "/api/plugins", null).body();

    assertThat(plugins)
        .contains(
            "API_TRIGGER",
            "CONSTANT_SOURCE",
            "CONSOLE_TERMINAL",
            "PROCESS_EXECUTOR",
            "MAPPER",
            "BRANCH",
            "SUB_WORKFLOW",
            "WAIT_FOR_SIGNAL",
            "ASK_INPUT",
            "NOTIFY_TERMINAL",
            "OUTCOME_TERMINAL",
            "WEBHOOK_TRIGGER",
            "SCHEDULE_TRIGGER");
    assertThat(api.call("GET", "/api/plugins/MAPPER", null).status()).isEqualTo(200);
    assertThat(api.call("GET", "/api/plugins/NO_SUCH_PLUGIN", null).status()).isEqualTo(404);
  }

  @Test
  void theListEndpointsAnswerWithTheEnvelope(final Api api) {
    for (final String path :
        new String[] {
          "/api/plugins", "/api/templates", "/api/sessions", "/api/sessions/summaries",
          "/api/executions", "/api/metrics/snapshot", "/api/supervisors", "/api/edition",
          "/api/me", "/api/scheduler/limits"
        }) {
      final Reply reply = api.call("GET", path, null);
      assertThat(reply.status()).as("%s: %s", path, reply.body()).isEqualTo(200);
      assertThat(JSON.readTree(reply.body()).path("status").asInt()).as(path).isEqualTo(200);
    }
  }

  @Test
  void theUiIsServed(final Api api) {
    final Reply reply = Api.reply(api.client().get().uri("/").exchange());

    assertThat(reply.status()).isEqualTo(200);
    assertThat(reply.body()).contains("<div id=\"root\"");
  }
}
