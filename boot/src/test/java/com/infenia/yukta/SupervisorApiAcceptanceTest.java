// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** The supervisor catalog and management API against the real application. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@NoArgsConstructor
@SuppressWarnings({"PMD.SignatureDeclareThrowsException", "PMD.AvoidDuplicateLiterals"})
class SupervisorApiAcceptanceTest {

  @LocalServerPort private int port;

  private HttpResponse<String> call(final String method, final String path, final String body)
      throws Exception {
    final HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void catalogListsEveryShippedTypeWithItsSchema() throws Exception {
    final HttpResponse<String> list = call("GET", "/api/supervisor-engines", null);
    assertThat(list.statusCode()).isEqualTo(200);
    assertThat(list.body()).contains("\"rules\"", "\"workflow\"", "\"http\"");
    assertThat(list.body()).doesNotContain("model-control-plane", "failure-guard");

    final HttpResponse<String> one = call("GET", "/api/supervisor-engines/rules", null);
    assertThat(one.statusCode()).isEqualTo(200);
    assertThat(one.body()).contains("configSchema", "commonSchema", "exampleSettings", "rules");
    assertThat(call("GET", "/api/supervisor-engines/nope", null).statusCode()).isEqualTo(404);
    assertThat(call("GET", "/api/supervisor-types", null).statusCode()).isEqualTo(404);
  }

  @Test
  void supervisorIsValidatedCreatedAssignedLockedAndRemoved() throws Exception {
    final String id = "api-" + UUID.randomUUID().toString().substring(0, 8);
    final String body = "{\"id\":\"" + id + "\",\"type\":\"rules\",\"settings\":{\"rules\":[]}}";

    final HttpResponse<String> bad =
        call(
            "POST", "/api/supervisors/validate", "{\"type\":\"rules\",\"settings\":{\"bogus\":1}}");
    assertThat(bad.statusCode()).as(bad.body()).isEqualTo(200);
    assertThat(bad.body()).contains("\"valid\":false", "settings.bogus");

    final HttpResponse<String> refused =
        call(
            "POST",
            "/api/supervisors",
            "{\"id\":\"x" + id + "\",\"type\":\"rules\",\"settings\":{\"bogus\":1}}");
    assertThat(refused.statusCode()).isEqualTo(400);
    assertThat(refused.body()).contains("settings.bogus");

    assertThat(call("POST", "/api/supervisors", body).statusCode()).isEqualTo(201);
    assertThat(call("POST", "/api/supervisors", body).statusCode()).isEqualTo(409);

    final String owns = "[{\"target\":\"session:s1\",\"locked\":true}]";
    assertThat(call("POST", "/api/supervisors/" + id + "/assignments/check", owns).body())
        .contains("\"valid\":true");
    assertThat(call("PUT", "/api/supervisors/" + id + "/assignments", owns).statusCode())
        .isEqualTo(200);
    final HttpResponse<String> effective =
        call("GET", "/api/supervisors/assignments?session=s1&source=MANUAL", null);
    assertThat(effective.body()).contains("\"supervisorId\":\"" + id, "\"source\":\"MANUAL\"");
    assertThat(call("GET", "/api/supervisors/" + id + "/assignments", null).body())
        .contains("\"locked\":true");
    assertThat(call("GET", "/api/supervisors/status", null).statusCode()).isEqualTo(200);
    assertThat(call("GET", "/api/supervisors/decisions?supervisor=" + id, null).statusCode())
        .isEqualTo(200);

    assertThat(call("DELETE", "/api/supervisors/" + id, null).statusCode()).isEqualTo(409);
    // a locked assignment stays unless the request asks to unlock
    assertThat(call("PUT", "/api/supervisors/" + id + "/assignments", "[]").statusCode())
        .isEqualTo(409);
    assertThat(
            call("PUT", "/api/supervisors/" + id + "/assignments?unlock=true", "[]").statusCode())
        .isEqualTo(200);
    assertThat(call("DELETE", "/api/supervisors/" + id, null).statusCode()).isEqualTo(200);
    assertThat(call("GET", "/api/supervisors/" + id, null).statusCode()).isEqualTo(404);
  }

  @Test
  void theOldProfileAndBindingPathsAreGone() throws Exception {
    assertThat(call("GET", "/api/supervisors/profiles", null).statusCode()).isEqualTo(404);
    assertThat(call("GET", "/api/supervisors/bindings", null).statusCode()).isEqualTo(404);
    assertThat(call("GET", "/api/supervisors/types", null).statusCode()).isEqualTo(404);
    assertThat(call("POST", "/api/supervisors/attachments", "{}").statusCode()).isEqualTo(405);
  }

  private static String session(final String id, final String sessionRefs, final String flowRefs) {
    return "{\"sessionId\":\""
        + id
        + "\",\"name\":\"n\",\"description\":\"d\",\"initiator\":\"t\","
        + "\"projectPath\":\"/tmp\",\"assignments\":"
        + sessionRefs
        + ",\"workflows\":{\"flow\":{\"workflowId\":\"flow\",\"description\":\"d\","
        + "\"nodes\":[{\"nodeId\":\"build.a\",\"type\":\"API_TRIGGER\",\"config\":{}},"
        + "{\"nodeId\":\"end\",\"type\":\"CONSOLE_TERMINAL\",\"config\":{}}],"
        + "\"edges\":[{\"source\":\"build.a\",\"target\":\"end\"}],\"assignments\":"
        + flowRefs
        + "}}}";
  }

  @Test
  void supervisorsDeclaredInSessionAreAttachedFollowTheDefinitionAndBlockDeletion()
      throws Exception {
    final String tag = UUID.randomUUID().toString().substring(0, 8);
    final String supervisor = "decl-" + tag;
    final String sessionId = "decl-session-" + tag;
    assertThat(
            call(
                    "POST",
                    "/api/supervisors",
                    "{\"id\":\""
                        + supervisor
                        + "\",\"type\":\"rules\","
                        + "\"settings\":{\"rules\":[]}}")
                .statusCode())
        .isEqualTo(201);

    final String declared =
        session(
            sessionId,
            "[{\"supervisor\":\"" + supervisor + "\"}]",
            "[{\"supervisor\":\"" + supervisor + "\",\"nodes\":[\"build.*\"]}]");
    final HttpResponse<String> saved = call("POST", "/api/sessions", declared);
    assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);

    final String listed =
        call(
                "GET",
                "/api/supervisors/assignments?session=" + sessionId + "&source=DEFINITION",
                null)
            .body();
    assertThat(listed)
        .contains("session:" + sessionId, "workflow:" + sessionId + "/flow//build.*", "def-");
    assertThat(call("GET", "/api/sessions/" + sessionId + "/config", null).body())
        .contains("\"assignments\":[{\"supervisor\":\"" + supervisor + "\"");
    final HttpResponse<String> blocked = call("DELETE", "/api/supervisors/" + supervisor, null);
    assertThat(blocked.statusCode()).isEqualTo(409);
    assertThat(blocked.body()).contains("session " + sessionId, "workflow " + sessionId + "/flow");
    // setting what the supervisor owns by hand leaves what definitions declare alone
    assertThat(call("PUT", "/api/supervisors/" + supervisor + "/assignments", "[]").statusCode())
        .isEqualTo(200);
    assertThat(call("GET", "/api/supervisors/" + supervisor + "/assignments", null).body())
        .contains("def-");

    final HttpResponse<String> unknown =
        call("POST", "/api/sessions", session(sessionId, "[{\"supervisor\":\"nope\"}]", "[]"));
    assertThat(unknown.statusCode()).isEqualTo(400);
    assertThat(unknown.body()).contains("assignments[0].supervisor");

    assertThat(call("POST", "/api/sessions", session(sessionId, "[]", "[]")).statusCode())
        .isEqualTo(200);
    assertThat(call("GET", "/api/supervisors/assignments?session=" + sessionId, null).body())
        .doesNotContain("def-");
    assertThat(call("DELETE", "/api/sessions/" + sessionId, null).statusCode()).isEqualTo(204);
    assertThat(call("DELETE", "/api/supervisors/" + supervisor, null).statusCode()).isEqualTo(200);
  }
}
