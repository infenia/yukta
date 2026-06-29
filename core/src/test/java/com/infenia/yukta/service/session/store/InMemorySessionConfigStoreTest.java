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
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Test suite for InMemorySessionConfigStore. */
@MockitoSettings
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.LawOfDemeter", "PMD.AvoidDuplicateLiterals"})
class InMemorySessionConfigStoreTest {

  /** Placeholder string value used in test cases. */
  private static final String OTHER = "Other";

  /** Initiator field name. */
  private static final String INITIATOR = "initiator";

  /** Description field name. */
  private static final String DESCRIPTION = "description";

  /** Short description field name. */
  private static final String DESC = "desc";

  /** Environment field name. */
  private static final String ENV = "env";

  /** Workflow identifier. */
  private static final String WORKFLOW = "wf1";

  /** Path value. */
  private static final String PATH = "/path";

  /** Empty session identifier. */
  private static final String EMPTY_SESSION_ID = "s-empty";

  /** Mock store for workflow definitions. */
  @Mock private WorkflowDefinitionStore workflowDefinitionStore;

  /** Service instance under test. */
  private InMemorySessionConfigStore configService;

  @BeforeEach
  void setUp() {
    final SessionConfigProperties props = new SessionConfigProperties();
    props.setBaseDir(System.getProperty("user.home") + "/.yukta");
    props.setFileLogSubDir("modified-files");
    props.setResultLogSubDir("results");
    props.setExecutionTimeoutSeconds(3600L);
    configService = new InMemorySessionConfigStore(props, workflowDefinitionStore);
  }

  @Test
  void testDefaultValues() {
    final String sessionId = "sess-1";
    StepVerifier.create(configService.getProjectPath(sessionId)).expectNext("").verifyComplete();
    StepVerifier.create(configService.getExecutionTimeout(sessionId))
        .expectNext(3600L)
        .verifyComplete();
    final String home = System.getProperty("user.home");
    StepVerifier.create(configService.getFileLogDir(sessionId))
        .expectNext(home + "/.yukta/modified-files")
        .verifyComplete();
    StepVerifier.create(configService.getResultLogDir(sessionId))
        .expectNext(home + "/.yukta/results")
        .verifyComplete();

    StepVerifier.create(configService.getInitiator(sessionId)).expectNext("").verifyComplete();
    StepVerifier.create(configService.getInitiatedTime(sessionId)).expectNext("").verifyComplete();
    StepVerifier.create(configService.getTags(sessionId)).expectNext(Map.of()).verifyComplete();
    StepVerifier.create(configService.getDescription(sessionId)).expectNext("").verifyComplete();
  }

  @Test
  void testSetNullMetadata() {
    final String sessionId = "sess-null";
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
    final String sessionId = "sess-1";
    StepVerifier.create(configService.setProjectPath(sessionId, "/api/path")).verifyComplete();

    StepVerifier.create(configService.getProjectPath(sessionId))
        .expectNext("/api/path")
        .verifyComplete();

    final String otherSession = "sess-2";
    StepVerifier.create(configService.getProjectPath(otherSession)).expectNext("").verifyComplete();
  }

  @Test
  void testGetSessionIds() {
    configService.setProjectPath("s1", "/p1").block();
    configService.setInitiator("s3", "i1").block();
    configService.setInitiatedTime("s4", "t1").block();
    configService.setTags("s5", Map.of("t", "v")).block();
    configService.setDescription("s6", "d1").block();

    final List<String> expectedIds = List.of("s1", "s3", "s4", "s5", "s6");
    StepVerifier.create(configService.getSessionIds().collectList())
        .expectNextMatches(ids -> ids.size() == 5 && ids.containsAll(expectedIds))
        .verifyComplete();
  }

  @Test
  void testMetadataOverrides() {
    final String sessionId = "sess-1";
    final String initiator = "John Doe";
    final String time = "2026-02-21T21:00:00Z";
    final String description = "Sample Session";
    final Map<String, String> tags = Map.of("clientId", "c1");

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

    StepVerifier.create(configService.setInitiator(sessionId, OTHER)).verifyComplete();
    StepVerifier.create(configService.setDescription(sessionId, OTHER)).verifyComplete();
    StepVerifier.create(configService.getInitiator(sessionId))
        .expectNext(initiator)
        .verifyComplete();

    StepVerifier.create(configService.setInitiatedTime(sessionId, OTHER)).verifyComplete();
    StepVerifier.create(configService.getInitiatedTime(sessionId))
        .expectNext(time)
        .verifyComplete();
    StepVerifier.create(configService.getDescription(sessionId))
        .expectNext(description)
        .verifyComplete();

    StepVerifier.create(configService.setTags(sessionId, Map.of(OTHER, "Val"))).verifyComplete();
    StepVerifier.create(configService.getTags(sessionId)).expectNext(tags).verifyComplete();
  }

  @Test
  void testGetAllConfigsIncludingMetadata() {
    final String sessionId = "sess-meta";
    configService.setInitiator(sessionId, "Jules").block();
    configService.setInitiatedTime(sessionId, "now").block();
    configService.setTags(sessionId, Map.of("k", "v")).block();
    configService.setDescription(sessionId, "Sample").block();
    configService.setProjectPath(sessionId, "/meta/path").block();

    when(workflowDefinitionStore.findAll(sessionId)).thenReturn(Mono.just(Map.of()));

    StepVerifier.create(configService.getAllConfigs(sessionId))
        .expectNextMatches(InMemorySessionConfigStoreTest::matchesAllConfigsData)
        .verifyComplete();
  }

  /**
   * Validates that the config map contains all expected fields and values.
   *
   * @param map the config map to validate
   * @return true if all conditions are met
   */
  private static boolean matchesAllConfigsData(final Map<String, ?> map) {
    return "Jules".equals(map.get(INITIATOR))
        && "now".equals(map.get("initiatedTime"))
        && Map.of("k", "v").equals(map.get("tags"))
        && "Sample".equals(map.get(DESCRIPTION))
        && "/meta/path".equals(map.get("projectPath"))
        && map.containsKey("executionTimeout")
        && map.containsKey("fileLogDir")
        && map.containsKey("resultLogDir")
        && map.containsKey("workflows");
  }

  @Test
  void testApplySessionConfig() {
    final String sessionId = "sess-apply";
    final WorkflowDefinition workflow =
        new WorkflowDefinition(
            "test-workflow",
            DESC,
            List.of(new WorkflowDefinition.Node("n1", "gradle", Map.of())),
            List.of());
    final SessionConfigData data =
        new SessionConfigData(
            sessionId,
            "full desc",
            "initiator-x",
            Map.of(ENV, "prod"),
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
        .expectNext(Map.of(ENV, "prod"))
        .verifyComplete();

    verify(workflowDefinitionStore).save(sessionId, workflow);
  }

  @Test
  void applySessionConfigDelegatesWorkflowsToStore() {
    final WorkflowDefinition workflow =
        new WorkflowDefinition(
            WORKFLOW, DESC, List.of(new WorkflowDefinition.Node("n1", "t", Map.of())), List.of());
    final SessionConfigData data =
        new SessionConfigData(
            "s1", "some description", "init", Map.of(), PATH, Map.of(WORKFLOW, workflow));

    when(workflowDefinitionStore.save("s1", workflow)).thenReturn(Mono.empty());

    StepVerifier.create(configService.applySessionConfig(data)).verifyComplete();

    verify(workflowDefinitionStore).save("s1", workflow);
  }

  @Test
  void applySessionConfigWithEmptyWorkflows() {
    final SessionConfigData data =
        new SessionConfigData(
            EMPTY_SESSION_ID, "description", "initiator", Map.of("key", "value"), PATH, Map.of());

    StepVerifier.create(configService.applySessionConfig(data)).verifyComplete();

    StepVerifier.create(configService.getProjectPath(EMPTY_SESSION_ID))
        .expectNext(PATH)
        .verifyComplete();
    StepVerifier.create(configService.getDescription(EMPTY_SESSION_ID))
        .expectNext("description")
        .verifyComplete();
    StepVerifier.create(configService.getInitiator(EMPTY_SESSION_ID))
        .expectNext("initiator")
        .verifyComplete();
  }

  @Test
  void shouldReturnEmptyWhenSessionDoesNotExist() {
    StepVerifier.create(configService.getAllConfigs("non-existent-session")).verifyComplete();
  }

  @Test
  void sessionExistsChecksAllMaps() {
    final String sessionId = "test-session";
    when(workflowDefinitionStore.findAll(sessionId)).thenReturn(Mono.just(Map.of()));
    configService.setInitiator(sessionId, "initiator").block();
    StepVerifier.create(configService.getAllConfigs(sessionId))
        .expectNextMatches(map -> "initiator".equals(map.get(INITIATOR)))
        .verifyComplete();

    final String sessionId2 = "test-session2";
    when(workflowDefinitionStore.findAll(sessionId2)).thenReturn(Mono.just(Map.of()));
    configService.setInitiatedTime(sessionId2, "2026-01-01").block();
    StepVerifier.create(configService.getAllConfigs(sessionId2))
        .expectNextMatches(map -> "2026-01-01".equals(map.get("initiatedTime")))
        .verifyComplete();

    final String sessionId3 = "test-session3";
    when(workflowDefinitionStore.findAll(sessionId3)).thenReturn(Mono.just(Map.of()));
    configService.setTags(sessionId3, Map.of("tag1", "val1")).block();
    StepVerifier.create(configService.getAllConfigs(sessionId3))
        .expectNextMatches(map -> Map.of("tag1", "val1").equals(map.get("tags")))
        .verifyComplete();

    final String sessionId4 = "test-session4";
    when(workflowDefinitionStore.findAll(sessionId4)).thenReturn(Mono.just(Map.of()));
    configService.setDescription(sessionId4, DESC).block();
    StepVerifier.create(configService.getAllConfigs(sessionId4))
        .expectNextMatches(map -> DESC.equals(map.get(DESCRIPTION)))
        .verifyComplete();
  }

  @Test
  void applySessionConfigWithMultipleWorkflows() {
    final String sessionId = "sess-multi";
    final WorkflowDefinition workflow1 =
        new WorkflowDefinition(
            "wf1", "desc1", List.of(new WorkflowDefinition.Node("n1", "t1", Map.of())), List.of());
    final WorkflowDefinition workflow2 =
        new WorkflowDefinition(
            "wf2", "desc2", List.of(new WorkflowDefinition.Node("n2", "t2", Map.of())), List.of());
    final SessionConfigData data =
        new SessionConfigData(
            sessionId,
            "description",
            "initiator",
            Map.of(ENV, "test"),
            "/multi/path",
            Map.of("wf1", workflow1, "wf2", workflow2));

    when(workflowDefinitionStore.save(sessionId, workflow1)).thenReturn(Mono.empty());
    when(workflowDefinitionStore.save(sessionId, workflow2)).thenReturn(Mono.empty());

    StepVerifier.create(configService.applySessionConfig(data)).verifyComplete();

    verify(workflowDefinitionStore).save(sessionId, workflow1);
    verify(workflowDefinitionStore).save(sessionId, workflow2);
    StepVerifier.create(configService.getProjectPath(sessionId))
        .expectNext("/multi/path")
        .verifyComplete();
  }

  @Test
  void setInitiatorOnExistingSessionWithEmptyInitiator() {
    final String sessionId = "sess-empty-initiator";
    configService.setProjectPath(sessionId, PATH).block();

    StepVerifier.create(configService.setInitiator(sessionId, "new-initiator")).verifyComplete();
    StepVerifier.create(configService.getInitiator(sessionId))
        .expectNext("new-initiator")
        .verifyComplete();
  }

  @Test
  void setDescriptionOnExistingSessionWithEmptyDescription() {
    final String sessionId = "sess-empty-description";
    configService.setProjectPath(sessionId, PATH).block();

    StepVerifier.create(configService.setDescription(sessionId, "new-description"))
        .verifyComplete();
    StepVerifier.create(configService.getDescription(sessionId))
        .expectNext("new-description")
        .verifyComplete();
  }

  @Test
  void setInitiatedTimeOnExistingSessionWithEmptyInitiatedTime() {
    final String sessionId = "sess-empty-time";
    configService.setProjectPath(sessionId, PATH).block();

    StepVerifier.create(configService.setInitiatedTime(sessionId, "2026-06-21T10:00:00Z"))
        .verifyComplete();
    StepVerifier.create(configService.getInitiatedTime(sessionId))
        .expectNext("2026-06-21T10:00:00Z")
        .verifyComplete();
  }

  @Test
  void setTagsOnExistingSessionWithEmptyTags() {
    final String sessionId = "sess-empty-tags";
    configService.setProjectPath(sessionId, PATH).block();

    final Map<String, String> newTags = Map.of(ENV, "test");
    StepVerifier.create(configService.setTags(sessionId, newTags)).verifyComplete();
    StepVerifier.create(configService.getTags(sessionId)).expectNext(newTags).verifyComplete();
  }

  @Test
  void multipleFieldsCanBeSetIndependentlyOnNewSession() {
    final String sessionId = "sess-multi-fields";

    configService.setProjectPath(sessionId, PATH).block();
    configService.setInitiator(sessionId, "initiator1").block();
    configService.setDescription(sessionId, "desc1").block();
    configService.setInitiatedTime(sessionId, "time1").block();
    configService.setTags(sessionId, Map.of("k1", "v1")).block();

    StepVerifier.create(configService.getProjectPath(sessionId)).expectNext(PATH).verifyComplete();
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
  @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
  void logInitializationExecutedOnBeanCreation()
      throws InvocationTargetException, IllegalAccessException {
    final SessionConfigProperties props = new SessionConfigProperties();
    props.setBaseDir(System.getProperty("user.home") + "/.yukta");
    props.setFileLogSubDir("modified-files");
    props.setResultLogSubDir("results");
    props.setExecutionTimeoutSeconds(3600L);

    final WorkflowDefinitionStore workflowStore =
        org.mockito.Mockito.mock(WorkflowDefinitionStore.class);
    final InMemorySessionConfigStore store = new InMemorySessionConfigStore(props, workflowStore);

    final Method logInitMethod = findLogInitializationMethod();
    assertThat(logInitMethod).isNotNull();
    final Method safeLogInitMethod =
        Objects.requireNonNull(logInitMethod, "Expected method logInitialization to exist");
    safeLogInitMethod.setAccessible(true);
    safeLogInitMethod.invoke(store);

    assertThat(store).isNotNull();
  }

  /**
   * Finds the logInitialization method in InMemorySessionConfigStore.
   *
   * @return the logInitialization method, or null if not found
   */
  private static Method findLogInitializationMethod() {
    final String logInitMethodName = "logInitialization";
    Method result = null;
    for (final Method method : InMemorySessionConfigStore.class.getDeclaredMethods()) {
      if (logInitMethodName.equals(method.getName())) {
        result = method;
        break;
      }
    }
    return result;
  }
}
