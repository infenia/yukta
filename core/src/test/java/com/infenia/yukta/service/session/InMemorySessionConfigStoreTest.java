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
package com.infenia.yukta.service.session;

import com.infenia.yukta.config.SessionConfigProperties;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class InMemorySessionConfigStoreTest {

  private InMemorySessionConfigStore configService;

  @BeforeEach
  void setUp() {
    SessionConfigProperties props = new SessionConfigProperties();
    props.setBaseDir(System.getProperty("user.home") + "/.yukta");
    props.setFileLogSubDir("modified-files");
    props.setResultLogSubDir("results");
    props.setExecutionTimeoutSeconds(3600L);
    configService = new InMemorySessionConfigStore(props);
  }

  @Test
  void testDefaultValues() {
    String sessionId = "sess-1";
    StepVerifier.create(configService.getProjectPath(sessionId)).expectNext("").verifyComplete();
    StepVerifier.create(configService.getWorkflow(sessionId, "w1"))
        .verifyComplete(); // Empty initially
    StepVerifier.create(configService.getWorkflows(sessionId))
        .expectNext(Map.of())
        .verifyComplete();
    StepVerifier.create(configService.getExecutionTimeout(sessionId))
        .expectNext(3600L)
        .verifyComplete();
    String home = System.getProperty("user.home");
    StepVerifier.create(configService.getFileLogDir(sessionId))
        .expectNext(home + "/.yukta/modified-files")
        .verifyComplete();
    StepVerifier.create(configService.getResultLogDir(sessionId))
        .expectNext(home + "/.yukta/results")
        .verifyComplete();

    // Metadata defaults
    StepVerifier.create(configService.getInitiator(sessionId)).expectNext("").verifyComplete();
    StepVerifier.create(configService.getInitiatedTime(sessionId)).expectNext("").verifyComplete();
    StepVerifier.create(configService.getTags(sessionId)).expectNext(Map.of()).verifyComplete();
    StepVerifier.create(configService.getDescription(sessionId)).expectNext("").verifyComplete();
  }

  @Test
  void testSetNullMetadata() {
    String sessionId = "sess-null";
    StepVerifier.create(configService.setInitiator(sessionId, null)).verifyComplete();
    StepVerifier.create(configService.setInitiatedTime(sessionId, null)).verifyComplete();
    StepVerifier.create(configService.setTags(sessionId, null)).verifyComplete();
    StepVerifier.create(configService.setDescription(sessionId, null)).verifyComplete();

    StepVerifier.create(configService.getInitiator(sessionId)).expectNext("").verifyComplete();
    StepVerifier.create(configService.getInitiatedTime(sessionId)).expectNext("").verifyComplete();
    StepVerifier.create(configService.getTags(sessionId)).expectNext(Map.of()).verifyComplete();
    StepVerifier.create(configService.getDescription(sessionId)).expectNext("").verifyComplete();
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
  void testGetSessionIds() {
    configService.setProjectPath("s1", "/p1").block();
    configService
        .setWorkflows("s2", Map.of("w1", new WorkflowDefinition("d", List.of(), List.of())))
        .block();
    configService.setInitiator("s3", "i1").block();
    configService.setInitiatedTime("s4", "t1").block();
    configService.setTags("s5", Map.of("t", "v")).block();
    configService.setDescription("s6", "d1").block();

    StepVerifier.create(configService.getSessionIds().collectList())
        .expectNextMatches(
            ids -> ids.size() == 6 && ids.containsAll(List.of("s1", "s2", "s3", "s4", "s5", "s6")))
        .verifyComplete();
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
    configService.setProjectPath(sessionId, "/meta/path").block();
    WorkflowDefinition workflow = new WorkflowDefinition("w", List.of(), List.of());
    configService.setWorkflows(sessionId, Map.of("w1", workflow)).block();

    StepVerifier.create(configService.getAllConfigs(sessionId))
        .expectNextMatches(
            map ->
                "Jules".equals(map.get("initiator"))
                    && "now".equals(map.get("initiatedTime"))
                    && Map.of("k", "v").equals(map.get("tags"))
                    && "Sample".equals(map.get("description"))
                    && "/meta/path".equals(map.get("projectPath"))
                    && Map.of("w1", workflow).equals(map.get("workflows"))
                    && map.containsKey("executionTimeout")
                    && map.containsKey("fileLogDir")
                    && map.containsKey("resultLogDir"))
        .verifyComplete();
  }

  @Test
  void testApplySessionConfig() {
    String sessionId = "sess-apply";
    WorkflowDefinition workflow =
        new WorkflowDefinition(
            "desc", List.of(new WorkflowDefinition.Node("n1", "gradle", Map.of())), List.of());
    SessionConfigData data =
        new SessionConfigData(
            sessionId,
            "full desc",
            "initiator-x",
            Map.of("env", "prod"),
            "/full/path",
            Map.of("w1", workflow));

    StepVerifier.create(configService.applySessionConfig(data)).verifyComplete();

    // Verify all data was applied
    StepVerifier.create(configService.getProjectPath(sessionId))
        .expectNext("/full/path")
        .verifyComplete();
    StepVerifier.create(configService.getDescription(sessionId))
        .expectNext("full desc")
        .verifyComplete();
    StepVerifier.create(configService.getInitiator(sessionId))
        .expectNext("initiator-x")
        .verifyComplete();
    StepVerifier.create(configService.getTags(sessionId))
        .expectNext(Map.of("env", "prod"))
        .verifyComplete();
    StepVerifier.create(configService.getWorkflow(sessionId, "w1"))
        .expectNext(workflow)
        .verifyComplete();
  }
}
