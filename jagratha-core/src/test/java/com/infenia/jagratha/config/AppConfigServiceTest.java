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
package com.infenia.jagratha.config;

import com.infenia.jagratha.model.WorkflowDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class AppConfigServiceTest {

  private AppConfigService configService;

  @BeforeEach
  void setUp() {
    configService = new AppConfigService();
  }

  @Test
  void testDefaultValues() {
    String sessionId = "sess-1";
    StepVerifier.create(configService.getProjectPath(sessionId)).expectNext("").verifyComplete();
    StepVerifier.create(configService.getWorkflow(sessionId, "w1"))
        .verifyComplete(); // Empty initially
    StepVerifier.create(configService.getExecutionTimeout(sessionId))
        .expectNext(3600L)
        .verifyComplete();
    String home = System.getProperty("user.home");
    StepVerifier.create(configService.getFileLogDir(sessionId))
        .expectNext(home + "/.jagratha/modified-files")
        .verifyComplete();
    StepVerifier.create(configService.getResultLogDir(sessionId))
        .expectNext(home + "/.jagratha/results")
        .verifyComplete();
  }

  @Test
  void testApiOverrides() {
    String sessionId = "sess-1";
    StepVerifier.create(configService.setProjectPath(sessionId, "/api/path")).verifyComplete();
    WorkflowDefinition workflow =
        new WorkflowDefinition(
            "desc", List.of(new WorkflowDefinition.Node("n1", "gradle", Map.of())), List.of());
    StepVerifier.create(configService.setWorkflows(sessionId, Map.of("w1", workflow)))
        .verifyComplete();

    StepVerifier.create(configService.getProjectPath(sessionId))
        .expectNext("/api/path")
        .verifyComplete();
    StepVerifier.create(configService.getWorkflow(sessionId, "w1"))
        .expectNext(workflow)
        .verifyComplete();

    // Another session should still have defaults
    String otherSession = "sess-2";
    StepVerifier.create(configService.getProjectPath(otherSession)).expectNext("").verifyComplete();
  }

  @Test
  void testActiveSessionTracking() {
    String sess1 = "sess-1";
    String sess2 = "sess-2";

    StepVerifier.create(configService.getActiveSessionIds()).expectNextCount(0).verifyComplete();
    StepVerifier.create(configService.isActive(sess1)).expectNext(false).verifyComplete();

    StepVerifier.create(configService.setProjectPath(sess1, "/path/1")).verifyComplete();
    StepVerifier.create(configService.getActiveSessionIds()).expectNext(sess1).verifyComplete();
    StepVerifier.create(configService.isActive(sess1)).expectNext(true).verifyComplete();
    StepVerifier.create(configService.isActive(sess2)).expectNext(false).verifyComplete();

    WorkflowDefinition workflow =
        new WorkflowDefinition(
            "desc", List.of(new WorkflowDefinition.Node("n1", "api-trigger", Map.of())), List.of());
    StepVerifier.create(configService.setWorkflows(sess2, Map.of("w1", workflow))).verifyComplete();

    StepVerifier.create(configService.getActiveSessionIds()).expectNextCount(2).verifyComplete();
  }

  @Test
  void testMetadataOverrides() {
    String sessionId = "sess-1";
    String initiator = "John Doe";
    String time = "2026-02-21T21:00:00Z";
    String description = "Sample Session";
    Map<String, String> tags = Map.of("clientId", "c1");

    StepVerifier.create(configService.setInitiator(sessionId, initiator)).verifyComplete();
    StepVerifier.create(configService.setDescription(sessionId, description)).verifyComplete();
    StepVerifier.create(configService.setInitiatedTime(sessionId, time)).verifyComplete();
    StepVerifier.create(configService.setTags(sessionId, tags)).verifyComplete();

    StepVerifier.create(configService.getInitiator(sessionId))
        .expectNext(initiator)
        .verifyComplete();
    StepVerifier.create(configService.getInitiatedTime(sessionId))
        .expectNext(time)
        .verifyComplete();
    StepVerifier.create(configService.getTags(sessionId)).expectNext(tags).verifyComplete();
    StepVerifier.create(configService.getDescription(sessionId))
        .expectNext(description)
        .verifyComplete();

    // Verify putIfAbsent (immutability)
    StepVerifier.create(configService.setInitiator(sessionId, "Other")).verifyComplete();
    StepVerifier.create(configService.setDescription(sessionId, "Other")).verifyComplete();
    StepVerifier.create(configService.getInitiator(sessionId))
        .expectNext(initiator)
        .verifyComplete();

    StepVerifier.create(configService.setInitiatedTime(sessionId, "Other")).verifyComplete();
    StepVerifier.create(configService.getInitiatedTime(sessionId))
        .expectNext(time)
        .verifyComplete();
    StepVerifier.create(configService.getDescription(sessionId))
        .expectNext(description)
        .verifyComplete();

    StepVerifier.create(configService.setTags(sessionId, Map.of("Other", "Val"))).verifyComplete();
    StepVerifier.create(configService.getTags(sessionId)).expectNext(tags).verifyComplete();
  }

  @Test
  void testGetAllConfigsIncludingMetadata() {
    String sessionId = "sess-meta";
    configService.setInitiator(sessionId, "Jules").block();
    configService.setInitiatedTime(sessionId, "now").block();
    configService.setTags(sessionId, Map.of("k", "v")).block();
    configService.setDescription(sessionId, "Sample").block();

    StepVerifier.create(configService.getAllConfigs(sessionId))
        .expectNextMatches(
            map ->
                "Jules".equals(map.get("initiator"))
                    && "now".equals(map.get("initiatedTime"))
                    && Map.of("k", "v").equals(map.get("tags"))
                    && "Sample".equals(map.get("description")))
        .verifyComplete();
  }

  @Test
  void testActiveTrackingWithMetadata() {
    String sess = "sess-tracking";
    StepVerifier.create(configService.isActive(sess)).expectNext(false).verifyComplete();

    configService.setInitiator(sess, "Jules").block();
    StepVerifier.create(configService.isActive(sess)).expectNext(true).verifyComplete();
  }
}
