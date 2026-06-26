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
package com.infenia.yukta.service.session.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.config.SessionConfigProperties;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@MockitoSettings
class InMemorySessionConfigStoreTest {

  @Mock private WorkflowDefinitionStore workflowDefinitionStore;

  private InMemorySessionConfigStore configService;

  @BeforeEach
  void setUp() {
    SessionConfigProperties props = new SessionConfigProperties();
    props.setBaseDir(System.getProperty("user.home") + "/.yukta");
    props.setFileLogSubDir("modified-files");
    props.setResultLogSubDir("results");
    props.setExecutionTimeoutSeconds(3600L);
    configService = new InMemorySessionConfigStore(props, workflowDefinitionStore);
  }

  @Test
  void testDefaultValues() {
    String sessionId = "sess-1";
    StepVerifier.create(configService.getProjectPath(sessionId)).expectNext("").verifyComplete();
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

    StepVerifier.create(configService.getProjectPath(sessionId))
        .expectNext("/api/path")
        .verifyComplete();

    // Another session should still have defaults
    String otherSession = "sess-2";
    StepVerifier.create(configService.getProjectPath(otherSession)).expectNext("").verifyComplete();
  }

  @Test
  void testGetSessionIds() {
    configService.setProjectPath("s1", "/p1").block();
    configService.setInitiator("s3", "i1").block();
    configService.setInitiatedTime("s4", "t1").block();
    configService.setTags("s5", Map.of("t", "v")).block();
    configService.setDescription("s6", "d1").block();

    StepVerifier.create(configService.getSessionIds().collectList())
        .expectNextMatches(
            ids -> ids.size() == 5 && ids.containsAll(List.of("s1", "s3", "s4", "s5", "s6")))
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

    when(workflowDefinitionStore.findAll(sessionId)).thenReturn(Mono.just(Map.of()));

    StepVerifier.create(configService.getAllConfigs(sessionId))
        .expectNextMatches(
            map ->
                "Jules".equals(map.get("initiator"))
                    && "now".equals(map.get("initiatedTime"))
                    && Map.of("k", "v").equals(map.get("tags"))
                    && "Sample".equals(map.get("description"))
                    && "/meta/path".equals(map.get("projectPath"))
                    && map.containsKey("executionTimeout")
                    && map.containsKey("fileLogDir")
                    && map.containsKey("resultLogDir")
                    && map.containsKey("workflows"))
        .verifyComplete();
  }

  @Test
  void testApplySessionConfig() {
    final String sessionId = "sess-apply";
    final WorkflowDefinition workflow =
        new WorkflowDefinition(
            "test-workflow",
            "desc",
            List.of(new WorkflowDefinition.Node("n1", "gradle", Map.of())),
            List.of());
    final SessionConfigData data =
        new SessionConfigData(
            sessionId,
            "full desc",
            "initiator-x",
            Map.of("env", "prod"),
            "/full/path",
            Map.of("w1", workflow));

    when(workflowDefinitionStore.save(sessionId, workflow)).thenReturn(Mono.empty());

    StepVerifier.create(configService.applySessionConfig(data)).verifyComplete();

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

    verify(workflowDefinitionStore).save(sessionId, workflow);
  }

  @Test
  void applySessionConfigDelegatesWorkflowsToStore() {
    final WorkflowDefinition wf =
        new WorkflowDefinition(
            "wf1", "desc", List.of(new WorkflowDefinition.Node("n1", "t", Map.of())), List.of());
    final SessionConfigData data =
        new SessionConfigData(
            "s1", "some description", "init", Map.of(), "/path", Map.of("wf1", wf));

    when(workflowDefinitionStore.save("s1", wf)).thenReturn(Mono.empty());

    StepVerifier.create(configService.applySessionConfig(data)).verifyComplete();

    verify(workflowDefinitionStore).save("s1", wf);
  }

  @Test
  void applySessionConfigWithEmptyWorkflows() {
    final SessionConfigData data =
        new SessionConfigData(
            "s-empty", "description", "initiator", Map.of("key", "value"), "/path", Map.of());

    StepVerifier.create(configService.applySessionConfig(data)).verifyComplete();

    StepVerifier.create(configService.getProjectPath("s-empty"))
        .expectNext("/path")
        .verifyComplete();
    StepVerifier.create(configService.getDescription("s-empty"))
        .expectNext("description")
        .verifyComplete();
    StepVerifier.create(configService.getInitiator("s-empty"))
        .expectNext("initiator")
        .verifyComplete();
  }

  @Test
  void getAllConfigsWhenSessionDoesNotExist() {
    StepVerifier.create(configService.getAllConfigs("non-existent-session")).verifyComplete();
  }

  @Test
  void sessionExistsChecksAllMaps() {
    String sessionId = "test-session";
    when(workflowDefinitionStore.findAll(sessionId)).thenReturn(Mono.just(Map.of()));
    configService.setInitiator(sessionId, "initiator").block();
    StepVerifier.create(configService.getAllConfigs(sessionId))
        .expectNextMatches(map -> "initiator".equals(map.get("initiator")))
        .verifyComplete();

    String sessionId2 = "test-session2";
    when(workflowDefinitionStore.findAll(sessionId2)).thenReturn(Mono.just(Map.of()));
    configService.setInitiatedTime(sessionId2, "2026-01-01").block();
    StepVerifier.create(configService.getAllConfigs(sessionId2))
        .expectNextMatches(map -> "2026-01-01".equals(map.get("initiatedTime")))
        .verifyComplete();

    String sessionId3 = "test-session3";
    when(workflowDefinitionStore.findAll(sessionId3)).thenReturn(Mono.just(Map.of()));
    configService.setTags(sessionId3, Map.of("tag1", "val1")).block();
    StepVerifier.create(configService.getAllConfigs(sessionId3))
        .expectNextMatches(map -> Map.of("tag1", "val1").equals(map.get("tags")))
        .verifyComplete();

    String sessionId4 = "test-session4";
    when(workflowDefinitionStore.findAll(sessionId4)).thenReturn(Mono.just(Map.of()));
    configService.setDescription(sessionId4, "desc").block();
    StepVerifier.create(configService.getAllConfigs(sessionId4))
        .expectNextMatches(map -> "desc".equals(map.get("description")))
        .verifyComplete();
  }

  @Test
  void applySessionConfigWithMultipleWorkflows() {
    final String sessionId = "sess-multi";
    final WorkflowDefinition wf1 =
        new WorkflowDefinition(
            "wf1", "desc1", List.of(new WorkflowDefinition.Node("n1", "t1", Map.of())), List.of());
    final WorkflowDefinition wf2 =
        new WorkflowDefinition(
            "wf2", "desc2", List.of(new WorkflowDefinition.Node("n2", "t2", Map.of())), List.of());
    final SessionConfigData data =
        new SessionConfigData(
            sessionId,
            "description",
            "initiator",
            Map.of("env", "test"),
            "/multi/path",
            Map.of("wf1", wf1, "wf2", wf2));

    when(workflowDefinitionStore.save(sessionId, wf1)).thenReturn(Mono.empty());
    when(workflowDefinitionStore.save(sessionId, wf2)).thenReturn(Mono.empty());

    StepVerifier.create(configService.applySessionConfig(data)).verifyComplete();

    verify(workflowDefinitionStore).save(sessionId, wf1);
    verify(workflowDefinitionStore).save(sessionId, wf2);
    StepVerifier.create(configService.getProjectPath(sessionId))
        .expectNext("/multi/path")
        .verifyComplete();
  }

  @Test
  void setInitiatorOnExistingSessionWithEmptyInitiator() {
    String sessionId = "sess-empty-initiator";
    configService.setProjectPath(sessionId, "/path").block();

    StepVerifier.create(configService.setInitiator(sessionId, "new-initiator")).verifyComplete();
    StepVerifier.create(configService.getInitiator(sessionId))
        .expectNext("new-initiator")
        .verifyComplete();
  }

  @Test
  void setDescriptionOnExistingSessionWithEmptyDescription() {
    String sessionId = "sess-empty-description";
    configService.setProjectPath(sessionId, "/path").block();

    StepVerifier.create(configService.setDescription(sessionId, "new-description"))
        .verifyComplete();
    StepVerifier.create(configService.getDescription(sessionId))
        .expectNext("new-description")
        .verifyComplete();
  }

  @Test
  void setInitiatedTimeOnExistingSessionWithEmptyInitiatedTime() {
    String sessionId = "sess-empty-time";
    configService.setProjectPath(sessionId, "/path").block();

    StepVerifier.create(configService.setInitiatedTime(sessionId, "2026-06-21T10:00:00Z"))
        .verifyComplete();
    StepVerifier.create(configService.getInitiatedTime(sessionId))
        .expectNext("2026-06-21T10:00:00Z")
        .verifyComplete();
  }

  @Test
  void setTagsOnExistingSessionWithEmptyTags() {
    String sessionId = "sess-empty-tags";
    configService.setProjectPath(sessionId, "/path").block();

    Map<String, String> newTags = Map.of("env", "test");
    StepVerifier.create(configService.setTags(sessionId, newTags)).verifyComplete();
    StepVerifier.create(configService.getTags(sessionId)).expectNext(newTags).verifyComplete();
  }

  @Test
  void multipleFieldsCanBeSetIndependentlyOnNewSession() {
    String sessionId = "sess-multi-fields";

    configService.setProjectPath(sessionId, "/path").block();
    configService.setInitiator(sessionId, "initiator1").block();
    configService.setDescription(sessionId, "desc1").block();
    configService.setInitiatedTime(sessionId, "time1").block();
    configService.setTags(sessionId, Map.of("k1", "v1")).block();

    StepVerifier.create(configService.getProjectPath(sessionId))
        .expectNext("/path")
        .verifyComplete();
    StepVerifier.create(configService.getInitiator(sessionId))
        .expectNext("initiator1")
        .verifyComplete();
    StepVerifier.create(configService.getDescription(sessionId))
        .expectNext("desc1")
        .verifyComplete();
    StepVerifier.create(configService.getInitiatedTime(sessionId))
        .expectNext("time1")
        .verifyComplete();
    StepVerifier.create(configService.getTags(sessionId))
        .expectNext(Map.of("k1", "v1"))
        .verifyComplete();
  }

  @Test
  void logInitializationExecutedOnBeanCreation()
      throws InvocationTargetException, IllegalAccessException {
    SessionConfigProperties props = new SessionConfigProperties();
    props.setBaseDir(System.getProperty("user.home") + "/.yukta");
    props.setFileLogSubDir("modified-files");
    props.setResultLogSubDir("results");
    props.setExecutionTimeoutSeconds(3600L);

    WorkflowDefinitionStore workflowDefinitionStore =
        org.mockito.Mockito.mock(WorkflowDefinitionStore.class);
    InMemorySessionConfigStore store =
        new InMemorySessionConfigStore(props, workflowDefinitionStore);

    Method logInitMethod = null;
    for (Method method : InMemorySessionConfigStore.class.getDeclaredMethods()) {
      if ("logInitialization".equals(method.getName())) {
        logInitMethod = method;
        break;
      }
    }

    assertThat(logInitMethod).isNotNull();
    Method safeLogInitMethod =
        Objects.requireNonNull(logInitMethod, "Expected method logInitialization to exist");
    safeLogInitMethod.setAccessible(true);
    safeLogInitMethod.invoke(store);

    assertThat(store).isNotNull();
  }
}
