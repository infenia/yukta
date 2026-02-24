/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.jagratha.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.jagratha.model.ApiResponse;
import com.infenia.jagratha.model.ConfigRequest;
import com.infenia.jagratha.model.WorkflowProgress;
import com.infenia.jagratha.model.WorkflowTriggerRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Harness for testing workflow execution using WebTestClient. */
@RequiredArgsConstructor
public class WorkflowTestHarness {

  private final WebTestClient webClient;

  /**
   * Initialize a session with workflows.
   *
   * @param request the config request
   * @return the session ID
   */
  public String initSession(final ConfigRequest request) {
    webClient
        .post()
        .uri("/api/config")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .exists("X-Response-Time");

    return request.sessionId();
  }

  /**
   * Trigger a workflow execution.
   *
   * @param sessionId the session ID
   * @param workflowId the workflow ID
   * @param payload the initial payload
   * @return the execution ID
   */
  public String triggerWorkflow(
      final String sessionId, final String workflowId, final Map<String, Object> payload) {
    final ApiResponse<com.infenia.jagratha.model.TriggerResponse> response =
        webClient
            .post()
            .uri("/api/workflow/trigger")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new WorkflowTriggerRequest(sessionId, workflowId, payload))
            .exchange()
            .expectStatus()
            .isAccepted()
            .expectHeader()
            .exists("X-Response-Time")
            .returnResult(
                new ParameterizedTypeReference<
                    ApiResponse<com.infenia.jagratha.model.TriggerResponse>>() {})
            .getResponseBody()
            .blockFirst();

    return response != null && response.data() != null ? response.data().executionId() : null;
  }

  /**
   * Poll until the workflow execution is finished.
   *
   * @param sessionId the session ID
   * @param executionId the execution ID
   * @return the final workflow progress
   */
  public WorkflowProgress pollUntilFinished(final String sessionId, final String executionId) {
    final long start = System.currentTimeMillis();
    final long timeout = 30_000L;
    final long interval = 500L;

    while (System.currentTimeMillis() - start < timeout) {
      final ApiResponse<WorkflowProgress> response =
          webClient
              .get()
              .uri("/api/workflow/{sessionId}/status/{executionId}", sessionId, executionId)
              .exchange()
              .expectStatus()
              .isOk()
              .expectHeader()
              .exists("X-Response-Time")
              .returnResult(new ParameterizedTypeReference<ApiResponse<WorkflowProgress>>() {})
              .getResponseBody()
              .blockFirst();

      if (response != null && response.data() != null) {
        final WorkflowProgress progress = response.data();
        System.out.println(
            "Polling status for " + sessionId + ":" + executionId + ": " + progress.status());
        if (!"RUNNING".equals(progress.status())) {
          return progress;
        }
      }

      try {
        Thread.sleep(interval);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Polling interrupted", e);
      }
    }
    throw new RuntimeException("Workflow polling timed out after " + timeout + "ms");
  }

  /**
   * Verify the workflow progress.
   *
   * @param progress the workflow progress
   * @param expectedStatus the expected final status
   */
  public void verifyStatus(final WorkflowProgress progress, final String expectedStatus) {
    assertThat(progress).isNotNull();
    assertThat(progress.status()).isEqualTo(expectedStatus);
  }
}
