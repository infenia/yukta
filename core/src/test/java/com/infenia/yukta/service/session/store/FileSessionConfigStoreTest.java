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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.config.SessionConfigProperties;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

@MockitoSettings(strictness = Strictness.LENIENT)
class FileSessionConfigStoreTest {

  @TempDir Path tempDir;

  @Mock private WorkflowDefinitionStore workflowDefinitionStore;

  private FileSessionConfigStore configStore;
  private SessionConfigProperties props;

  @BeforeEach
  void setUp() {
    props = new SessionConfigProperties();
    props.setBaseDir(tempDir.toString());
    props.setFileLogSubDir("modified-files");
    props.setResultLogSubDir("results");
    props.setExecutionTimeoutSeconds(3600L);
    final ObjectMapper objectMapper = new ObjectMapper();
    when(workflowDefinitionStore.findAll(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Mono.just(Map.of()));
    configStore = new FileSessionConfigStore(props, objectMapper, workflowDefinitionStore);
  }

  @Test
  void testDefaultValues() {
    String sessionId = "sess-default";
    StepVerifier.create(configStore.getProjectPath(sessionId)).expectNext("").verifyComplete();
    StepVerifier.create(configStore.getExecutionTimeout(sessionId))
        .expectNext(3600L)
        .verifyComplete();
    StepVerifier.create(configStore.getInitiator(sessionId)).expectNext("").verifyComplete();
    StepVerifier.create(configStore.getInitiatedTime(sessionId)).expectNext("").verifyComplete();
    StepVerifier.create(configStore.getTags(sessionId)).expectNext(Map.of()).verifyComplete();
    StepVerifier.create(configStore.getDescription(sessionId)).expectNext("").verifyComplete();
    StepVerifier.create(configStore.getFileLogDir(sessionId))
        .expectNext(tempDir.toString() + "/modified-files")
        .verifyComplete();
    StepVerifier.create(configStore.getResultLogDir(sessionId))
        .expectNext(tempDir.toString() + "/results")
        .verifyComplete();
  }

  @Test
  void testProjectPathPersistence() {
    String sessionId = "sess-project";
    String projectPath = "/api/project";

    StepVerifier.create(configStore.setProjectPath(sessionId, projectPath)).verifyComplete();

    StepVerifier.create(configStore.getProjectPath(sessionId))
        .expectNext(projectPath)
        .verifyComplete();
  }

  @Test
  void testGetSessionIds() {
    configStore.setProjectPath("s1", "/p1").block();
    configStore.setProjectPath("s2", "/p2").block();

    StepVerifier.create(configStore.getSessionIds().collectList())
        .expectNextMatches(ids -> ids.size() == 2 && ids.containsAll(List.of("s1", "s2")))
        .verifyComplete();
  }

  @Test
  void testGetSessionIdsEmpty() {
    StepVerifier.create(configStore.getSessionIds().collectList())
        .expectNext(List.of())
        .verifyComplete();
  }

  @Test
  void testCacheBehavior() {
    String sessionId = "cache-test";
    configStore.setProjectPath(sessionId, "/orig").block();

    // Verify it's cached
    StepVerifier.create(configStore.getProjectPath(sessionId)).expectNext("/orig").verifyComplete();

    // Directly modify the file to bypass cache
    Path sessionFile = tempDir.resolve("sessions").resolve(sessionId + ".json");
    try {
      String content = Files.readString(sessionFile);
      Files.writeString(sessionFile, content.replace("/orig", "/modified"));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    // Should still return cached value
    StepVerifier.create(configStore.getProjectPath(sessionId)).expectNext("/orig").verifyComplete();
  }

  @Test
  void testGetSessionIdsWithNonJsonFiles() throws IOException {
    Path sessionsDir = tempDir.resolve("sessions");
    Files.createDirectories(sessionsDir);
    Files.writeString(sessionsDir.resolve("s1.json"), "{}");
    Files.writeString(sessionsDir.resolve("s2.txt"), "{}");

    StepVerifier.create(configStore.getSessionIds().collectList())
        .expectNextMatches(ids -> ids.size() == 1 && ids.contains("s1"))
        .verifyComplete();
  }

  @Test
  void testMetadataPersistence() {
    String sessionId = "sess-metadata";
    String initiator = "John Doe";
    String time = "2026-02-21T21:00:00Z";
    String description = "Sample Session";
    Map<String, String> tags = Map.of("clientId", "c1");

    StepVerifier.create(configStore.setInitiator(sessionId, initiator)).verifyComplete();
    StepVerifier.create(configStore.setDescription(sessionId, description)).verifyComplete();
    StepVerifier.create(configStore.setInitiatedTime(sessionId, time)).verifyComplete();
    StepVerifier.create(configStore.setTags(sessionId, tags)).verifyComplete();

    StepVerifier.create(configStore.getInitiator(sessionId)).expectNext(initiator).verifyComplete();
    StepVerifier.create(configStore.getInitiatedTime(sessionId)).expectNext(time).verifyComplete();
    StepVerifier.create(configStore.getDescription(sessionId))
        .expectNext(description)
        .verifyComplete();
    StepVerifier.create(configStore.getTags(sessionId)).expectNext(tags).verifyComplete();
  }

  @Test
  void testPutIfAbsentBehaviorForMetadata() {
    String sessionId = "sess-put-if-absent";
    String initiator = "John Doe";

    StepVerifier.create(configStore.setInitiator(sessionId, initiator)).verifyComplete();
    StepVerifier.create(configStore.getInitiator(sessionId)).expectNext(initiator).verifyComplete();

    // Try to set again - should not override
    StepVerifier.create(configStore.setInitiator(sessionId, "Other")).verifyComplete();
    StepVerifier.create(configStore.getInitiator(sessionId)).expectNext(initiator).verifyComplete();

    // Verify for others as well
    String desc = "d1";
    StepVerifier.create(configStore.setDescription(sessionId, desc)).verifyComplete();
    StepVerifier.create(configStore.getDescription(sessionId)).expectNext(desc).verifyComplete();
    StepVerifier.create(configStore.setDescription(sessionId, "Other")).verifyComplete();
    StepVerifier.create(configStore.getDescription(sessionId)).expectNext(desc).verifyComplete();

    String time2 = "t1";
    StepVerifier.create(configStore.setInitiatedTime(sessionId, time2)).verifyComplete();
    StepVerifier.create(configStore.getInitiatedTime(sessionId)).expectNext(time2).verifyComplete();
    StepVerifier.create(configStore.setInitiatedTime(sessionId, "Other")).verifyComplete();
    StepVerifier.create(configStore.getInitiatedTime(sessionId)).expectNext(time2).verifyComplete();

    Map<String, String> tags2 = Map.of("k", "v");
    StepVerifier.create(configStore.setTags(sessionId, tags2)).verifyComplete();
    StepVerifier.create(configStore.getTags(sessionId)).expectNext(tags2).verifyComplete();
    StepVerifier.create(configStore.setTags(sessionId, Map.of("Other", "Val"))).verifyComplete();
    StepVerifier.create(configStore.getTags(sessionId)).expectNext(tags2).verifyComplete();
  }

  @Test
  void testGetAllConfigs() throws IOException {
    String sessionId = "sess-all-configs";
    configStore.setInitiator(sessionId, "Jules").block();
    configStore.setInitiatedTime(sessionId, "now").block();
    configStore.setTags(sessionId, Map.of("k", "v")).block();
    configStore.setDescription(sessionId, "Sample").block();
    configStore.setProjectPath(sessionId, "/meta/path").block();

    StepVerifier.create(configStore.getAllConfigs(sessionId))
        .expectNextMatches(
            map ->
                "Jules".equals(map.get("initiator"))
                    && "now".equals(map.get("initiatedTime"))
                    && Map.of("k", "v").equals(map.get("tags"))
                    && "Sample".equals(map.get("description"))
                    && "/meta/path".equals(map.get("projectPath"))
                    && map.containsKey("executionTimeout")
                    && map.containsKey("fileLogDir")
                    && map.containsKey("resultLogDir"))
        .verifyComplete();
  }

  @Test
  void testMetadataUpdateOnExistingFileWithEmptyFields() throws IOException {
    String sessionId = "sess-empty-metadata";
    Path sessionsDir = tempDir.resolve("sessions");
    Files.createDirectories(sessionsDir);
    // Create file with empty metadata
    Files.writeString(
        sessionsDir.resolve(sessionId + ".json"),
        "{\"projectPath\":\"\",\"description\":\"\",\"initiator\":\"\",\"initiatedTime\":\"\",\"tags\":{}}");

    String newDesc = "New Description";
    StepVerifier.create(configStore.setDescription(sessionId, newDesc)).verifyComplete();
    StepVerifier.create(configStore.getDescription(sessionId)).expectNext(newDesc).verifyComplete();

    String newInit = "New Initiator";
    StepVerifier.create(configStore.setInitiator(sessionId, newInit)).verifyComplete();
    StepVerifier.create(configStore.getInitiator(sessionId)).expectNext(newInit).verifyComplete();

    String newTime = "New Time";
    StepVerifier.create(configStore.setInitiatedTime(sessionId, newTime)).verifyComplete();
    StepVerifier.create(configStore.getInitiatedTime(sessionId))
        .expectNext(newTime)
        .verifyComplete();

    Map<String, String> newTags = Map.of("new", "tag");
    StepVerifier.create(configStore.setTags(sessionId, newTags)).verifyComplete();
    StepVerifier.create(configStore.getTags(sessionId)).expectNext(newTags).verifyComplete();
  }

  @Test
  void testMetadataUpdateOnExistingFileWithNullTags() throws IOException {
    String sessionId = "sess-null-tags-json";
    Path sessionsDir = tempDir.resolve("sessions");
    Files.createDirectories(sessionsDir);
    // Create file without tags field
    Files.writeString(sessionsDir.resolve(sessionId + ".json"), "{\"projectPath\":\"/path\"}");

    Map<String, String> newTags = Map.of("new", "tag");
    StepVerifier.create(configStore.setTags(sessionId, newTags)).verifyComplete();
    StepVerifier.create(configStore.getTags(sessionId)).expectNext(newTags).verifyComplete();
  }

  @Test
  void testGetSessionIdsIoException() throws IOException {
    // Create a file where the sessions directory should be to trigger IOException on Files.list
    Path sessionsDir = tempDir.resolve("sessions");
    Files.writeString(sessionsDir, "not a directory");

    StepVerifier.create(configStore.getSessionIds()).expectError(IOException.class).verify();
  }

  @Test
  void testMetadataUpdateOnExistingFileWithNullFields() throws IOException {
    String sessionId = "sess-null-metadata-json";
    Path sessionsDir = tempDir.resolve("sessions");
    Files.createDirectories(sessionsDir);
    // Create file without metadata fields
    Files.writeString(sessionsDir.resolve(sessionId + ".json"), "{}");

    String newDesc = "New Description";
    StepVerifier.create(configStore.setDescription(sessionId, newDesc)).verifyComplete();
    StepVerifier.create(configStore.getDescription(sessionId)).expectNext(newDesc).verifyComplete();

    String newInit = "New Initiator";
    StepVerifier.create(configStore.setInitiator(sessionId, newInit)).verifyComplete();
    StepVerifier.create(configStore.getInitiator(sessionId)).expectNext(newInit).verifyComplete();

    String newTime = "New Time";
    StepVerifier.create(configStore.setInitiatedTime(sessionId, newTime)).verifyComplete();
    StepVerifier.create(configStore.getInitiatedTime(sessionId))
        .expectNext(newTime)
        .verifyComplete();
  }

  @Test
  void testLoadSessionConfigIoException() throws IOException {
    String sessionId = "sess-io-error-load";
    Path sessionsDir = tempDir.resolve("sessions");
    Files.createDirectories(sessionsDir);
    // Create a directory where the file should be to trigger IOException on Files.readString
    Files.createDirectories(sessionsDir.resolve(sessionId + ".json"));

    StepVerifier.create(configStore.getProjectPath(sessionId)).expectError().verify();
  }

  @Test
  void testSaveSessionConfigIoException() throws IOException {
    String sessionId = "sess-save-error";
    // Create a file where the sessions directory should be to trigger IOException on
    // createDirectories
    Path sessionsDir = tempDir.resolve("sessions");
    Files.writeString(sessionsDir, "not a directory");

    StepVerifier.create(configStore.setProjectPath(sessionId, "/path")).expectError().verify();
  }

  @Test
  void testFileStorageStructure() throws IOException {
    String sessionId = "sess-structure";
    configStore.setProjectPath(sessionId, "/test/path").block();

    Path sessionsDir = tempDir.resolve("sessions");
    Path sessionFile = sessionsDir.resolve(sessionId + ".json");

    assert Files.exists(sessionFile);
    String content = Files.readString(sessionFile);
    assert content.contains("projectPath");
    assert content.contains("/test/path");
  }

  @Test
  void testSetNullMetadata() {
    String sessionId = "sess-null-meta";
    StepVerifier.create(configStore.setInitiator(sessionId, null)).verifyComplete();
    StepVerifier.create(configStore.setInitiatedTime(sessionId, null)).verifyComplete();
    StepVerifier.create(configStore.setTags(sessionId, null)).verifyComplete();
    StepVerifier.create(configStore.setDescription(sessionId, null)).verifyComplete();

    StepVerifier.create(configStore.getInitiator(sessionId)).expectNext("").verifyComplete();
    StepVerifier.create(configStore.getInitiatedTime(sessionId)).expectNext("").verifyComplete();
    StepVerifier.create(configStore.getTags(sessionId)).expectNext(Map.of()).verifyComplete();
    StepVerifier.create(configStore.getDescription(sessionId)).expectNext("").verifyComplete();
  }

  @Test
  void testLoadFromDiskNewInstance() {
    String sessionId = "sess-persistence";
    configStore.setProjectPath(sessionId, "/persistent/path").block();

    // Create a new store instance pointing to the same baseDir
    FileSessionConfigStore newStore =
        new FileSessionConfigStore(props, new ObjectMapper(), workflowDefinitionStore);

    // Should load from disk
    StepVerifier.create(newStore.getProjectPath(sessionId))
        .expectNext("/persistent/path")
        .verifyComplete();
  }

  @Test
  void testGetFieldsNullInFile() throws IOException {
    String sessionId = "sess-null-fields";
    Path sessionsDir = tempDir.resolve("sessions");
    Files.createDirectories(sessionsDir);
    // Create JSON with only sessionId, all other fields missing/null
    Files.writeString(
        sessionsDir.resolve(sessionId + ".json"), "{\"sessionId\":\"" + sessionId + "\"}");

    StepVerifier.create(configStore.getProjectPath(sessionId)).expectNext("").verifyComplete();
    StepVerifier.create(configStore.getTags(sessionId)).expectNext(Map.of()).verifyComplete();
    StepVerifier.create(configStore.getDescription(sessionId)).expectNext("").verifyComplete();
    StepVerifier.create(configStore.getInitiator(sessionId)).expectNext("").verifyComplete();
    StepVerifier.create(configStore.getInitiatedTime(sessionId)).expectNext("").verifyComplete();
  }

  @Test
  void testApplySessionConfig() {
    String sessionId = "sess-apply";
    WorkflowDefinition workflow =
        new WorkflowDefinition(
            "test-workflow",
            "desc",
            List.of(new WorkflowDefinition.Node("n1", "gradle", Map.of())),
            List.of());
    SessionConfigData data =
        new SessionConfigData(
            sessionId,
            "full desc",
            "initiator-y",
            Map.of("tier", "prod"),
            "/file/path",
            Map.of("w1", workflow));

    when(workflowDefinitionStore.save(sessionId, workflow)).thenReturn(Mono.empty());

    StepVerifier.create(configStore.applySessionConfig(data)).verifyComplete();

    // Verify all data was persisted and retrieved
    StepVerifier.create(configStore.getProjectPath(sessionId))
        .expectNext("/file/path")
        .verifyComplete();
    StepVerifier.create(configStore.getDescription(sessionId))
        .expectNext("full desc")
        .verifyComplete();
    StepVerifier.create(configStore.getInitiator(sessionId))
        .expectNext("initiator-y")
        .verifyComplete();
    StepVerifier.create(configStore.getTags(sessionId))
        .expectNext(Map.of("tier", "prod"))
        .verifyComplete();

    verify(workflowDefinitionStore).save(sessionId, workflow);
  }

  @Test
  void testLoadMalformedJson() throws IOException {
    String sessionId = "malformed-json";
    Path sessionsDir = tempDir.resolve("sessions");
    Files.createDirectories(sessionsDir);
    Files.writeString(sessionsDir.resolve(sessionId + ".json"), "{ invalid json }");

    StepVerifier.create(configStore.getProjectPath(sessionId)).expectError().verify();
  }

  @Test
  void testSetInitiatedTimeFirst() {
    String sessionId = "sess-time-first";
    StepVerifier.create(configStore.setInitiatedTime(sessionId, "now")).verifyComplete();
    StepVerifier.create(configStore.getInitiatedTime(sessionId)).expectNext("now").verifyComplete();
  }

  @Test
  void testSetTagsFirst() {
    String sessionId = "sess-tags-first";
    Map<String, String> tags = Map.of("a", "b");
    StepVerifier.create(configStore.setTags(sessionId, tags)).verifyComplete();
    StepVerifier.create(configStore.getTags(sessionId)).expectNext(tags).verifyComplete();
  }

  @Test
  void testMetadataUpdateWhenExistingIsSame() throws IOException {
    String sessionId = "sess-same-meta";
    configStore.setDescription(sessionId, "desc").block();

    // Calling again with same value
    StepVerifier.create(configStore.setDescription(sessionId, "desc")).verifyComplete();
    StepVerifier.create(configStore.getDescription(sessionId)).expectNext("desc").verifyComplete();
  }

  @Test
  void testApplySessionConfigDelegatesWorkflowsToStore() {
    final String sessionId = "sess-wf-delegate";
    final WorkflowDefinition wf =
        new WorkflowDefinition(
            "wf1", "desc", List.of(new WorkflowDefinition.Node("n1", "t", Map.of())), List.of());
    final SessionConfigData data =
        new SessionConfigData(
            sessionId, "some description", "init", Map.of(), "/path", Map.of("wf1", wf));

    when(workflowDefinitionStore.save(sessionId, wf)).thenReturn(Mono.empty());

    StepVerifier.create(configStore.applySessionConfig(data)).verifyComplete();

    verify(workflowDefinitionStore).save(sessionId, wf);
  }

  @Test
  void testApplySessionConfigWithEmptyWorkflows() {
    final String sessionId = "sess-empty-wf";
    final SessionConfigData data =
        new SessionConfigData(sessionId, "some description", "init", Map.of(), "/path", Map.of());

    StepVerifier.create(configStore.applySessionConfig(data)).verifyComplete();

    StepVerifier.create(configStore.getProjectPath(sessionId)).expectNext("/path").verifyComplete();
  }

  @Test
  void testLogInitialization() throws Exception {
    java.lang.reflect.Method logInitMethod =
        FileSessionConfigStore.class.getDeclaredMethod("logInitialization");
    logInitMethod.setAccessible(true);
    logInitMethod.invoke(configStore);
  }
}
