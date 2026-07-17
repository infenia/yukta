// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.session.store;

import static org.assertj.core.api.Assertions.assertThat;
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
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

/** Unit tests for {@link FileSessionConfigStore}. */
@MockitoSettings(strictness = Strictness.LENIENT)
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
class FileSessionConfigStoreTest {

  /** The sessions directory name. */
  private static final String SESSIONS_DIR = "sessions";

  /** JSON file extension. */
  private static final String JSON_EXT = ".json";

  /** A generic path string. */
  private static final String TEST_PATH = "/path";

  /** Root filesystem path for testing null filename. */
  private static final String ROOT_PATH = "/";

  /** A generic description string. */
  private static final String TEST_DESC = "desc";

  /** A generic 'now' string. */
  private static final String NOW = "now";

  /** Shared 'Other' literal. */
  private static final String OTHER = "Other";

  /** Temporary directory for file-based storage. */
  @TempDir /* default */ Path tempDir;

  /** Mocked workflow store. */
  @Mock private WorkflowDefinitionStore workflowDefinitionStore;

  /** Store instance under test. */
  private FileSessionConfigStore configStore;

  /** Properties configuration. */
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
    final String sessionId = "sess-default";
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
    final String sessionId = "sess-project";
    final String projectPath = "/api/project";

    StepVerifier.create(configStore.setProjectPath(sessionId, projectPath)).verifyComplete();

    StepVerifier.create(configStore.getProjectPath(sessionId))
        .expectNext(projectPath)
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("PMD.LawOfDemeter")
  void testGetSessionIds() {
    configStore.setProjectPath("s1", "/p1").block();
    configStore.setProjectPath("s2", "/p2").block();

    StepVerifier.create(configStore.getSessionIds().collectList())
        .expectNextMatches(ids -> ids.size() == 2 && ids.containsAll(List.of("s1", "s2")))
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("PMD.LawOfDemeter")
  void testGetSessionIdsEmpty() {
    StepVerifier.create(configStore.getSessionIds().collectList())
        .expectNext(List.of())
        .verifyComplete();
  }

  @Test
  void testCacheBehavior() {
    final String sessionId = "cache-test";
    final String origPath = "/orig";
    configStore.setProjectPath(sessionId, origPath).block();

    // Verify it's cached
    StepVerifier.create(configStore.getProjectPath(sessionId))
        .expectNext(origPath)
        .verifyComplete();

    // Directly modify the file to bypass cache
    final Path sessionFile = tempDir.resolve(SESSIONS_DIR).resolve(sessionId + JSON_EXT);
    try {
      final String content = Files.readString(sessionFile);
      Files.writeString(sessionFile, content.replace(origPath, "/modified"));
    } catch (final IOException e) {
      throw new IllegalStateException("Failed to modify session file", e);
    }

    // Should still return cached value
    StepVerifier.create(configStore.getProjectPath(sessionId))
        .expectNext(origPath)
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("PMD.LawOfDemeter")
  void testGetSessionIdsWithNonJsonFiles() throws IOException {
    final Path sessionsDir = tempDir.resolve(SESSIONS_DIR);
    Files.createDirectories(sessionsDir);
    Files.writeString(sessionsDir.resolve("s1" + JSON_EXT), "{}");
    Files.writeString(sessionsDir.resolve("s2.txt"), "{}");

    StepVerifier.create(configStore.getSessionIds().collectList())
        .expectNextMatches(ids -> ids.size() == 1 && ids.contains("s1"))
        .verifyComplete();
  }

  @Test
  void testMetadataPersistence() {
    final String sessionId = "sess-metadata";
    final String initiator = "John Doe";
    final String time = "2026-02-21T21:00:00Z";
    final String description = "Sample Session";
    final Map<String, String> tags = Map.of("clientId", "c1");

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
    final String sessionId = "sess-put-if-absent";
    final String initiator = "John Doe";

    StepVerifier.create(configStore.setInitiator(sessionId, initiator)).verifyComplete();
    StepVerifier.create(configStore.getInitiator(sessionId)).expectNext(initiator).verifyComplete();

    StepVerifier.create(configStore.setInitiator(sessionId, OTHER)).verifyComplete();
    StepVerifier.create(configStore.getInitiator(sessionId)).expectNext(initiator).verifyComplete();

    // Verify for others as well
    StepVerifier.create(configStore.setDescription(sessionId, TEST_DESC)).verifyComplete();
    StepVerifier.create(configStore.getDescription(sessionId))
        .expectNext(TEST_DESC)
        .verifyComplete();
    StepVerifier.create(configStore.setDescription(sessionId, OTHER)).verifyComplete();
    StepVerifier.create(configStore.getDescription(sessionId))
        .expectNext(TEST_DESC)
        .verifyComplete();

    StepVerifier.create(configStore.setInitiatedTime(sessionId, NOW)).verifyComplete();
    StepVerifier.create(configStore.getInitiatedTime(sessionId)).expectNext(NOW).verifyComplete();
    StepVerifier.create(configStore.setInitiatedTime(sessionId, OTHER)).verifyComplete();
    StepVerifier.create(configStore.getInitiatedTime(sessionId)).expectNext(NOW).verifyComplete();

    final Map<String, String> tags2 = Map.of("k", "v");
    StepVerifier.create(configStore.setTags(sessionId, tags2)).verifyComplete();
    StepVerifier.create(configStore.getTags(sessionId)).expectNext(tags2).verifyComplete();
    StepVerifier.create(configStore.setTags(sessionId, Map.of(OTHER, "Val"))).verifyComplete();
    StepVerifier.create(configStore.getTags(sessionId)).expectNext(tags2).verifyComplete();
  }

  @Test
  void testGetAllConfigs() {
    final String sessionId = "sess-all-configs";
    configStore.setInitiator(sessionId, "Jules").block();
    configStore.setInitiatedTime(sessionId, NOW).block();
    configStore.setTags(sessionId, Map.of("k", "v")).block();
    configStore.setDescription(sessionId, "Sample").block();
    configStore.setProjectPath(sessionId, "/meta/path").block();

    StepVerifier.create(configStore.getAllConfigs(sessionId))
        .expectNextMatches(
            map ->
                "Jules".equals(map.get("initiator"))
                    && NOW.equals(map.get("initiatedTime"))
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
    final String sessionId = "sess-empty-metadata";
    final Path sessionsDir = tempDir.resolve(SESSIONS_DIR);
    Files.createDirectories(sessionsDir);
    // Create file with empty metadata
    Files.writeString(
        sessionsDir.resolve(sessionId + JSON_EXT),
        "{\"projectPath\":\"\",\"description\":\"\",\"initiator\":"
            + "\"\",\"initiatedTime\":\"\",\"tags\":{}}");

    final String newDesc = "New Description";
    StepVerifier.create(configStore.setDescription(sessionId, newDesc)).verifyComplete();
    StepVerifier.create(configStore.getDescription(sessionId)).expectNext(newDesc).verifyComplete();

    final String newInit = "New Initiator";
    StepVerifier.create(configStore.setInitiator(sessionId, newInit)).verifyComplete();
    StepVerifier.create(configStore.getInitiator(sessionId)).expectNext(newInit).verifyComplete();

    final String newTime = "New Time";
    StepVerifier.create(configStore.setInitiatedTime(sessionId, newTime)).verifyComplete();
    StepVerifier.create(configStore.getInitiatedTime(sessionId))
        .expectNext(newTime)
        .verifyComplete();

    final Map<String, String> newTags = Map.of("new", "tag");
    StepVerifier.create(configStore.setTags(sessionId, newTags)).verifyComplete();
    StepVerifier.create(configStore.getTags(sessionId)).expectNext(newTags).verifyComplete();
  }

  @Test
  void testMetadataUpdateOnExistingFileWithNullTags() throws IOException {
    final String sessionId = "sess-null-tags-json";
    final Path sessionsDir = tempDir.resolve(SESSIONS_DIR);
    Files.createDirectories(sessionsDir);
    // Create file without tags field
    Files.writeString(sessionsDir.resolve(sessionId + JSON_EXT), "{\"projectPath\":\"/path\"}");

    final Map<String, String> newTags = Map.of("new", "tag");
    StepVerifier.create(configStore.setTags(sessionId, newTags)).verifyComplete();
    StepVerifier.create(configStore.getTags(sessionId)).expectNext(newTags).verifyComplete();
  }

  @Test
  void testGetSessionIdsIoException() throws IOException {
    // Create a file where the sessions directory should be to trigger IOException on Files.list
    final Path sessionsDir = tempDir.resolve(SESSIONS_DIR);
    Files.writeString(sessionsDir, "not a directory");

    StepVerifier.create(configStore.getSessionIds()).expectError(IOException.class).verify();
  }

  @Test
  void testMetadataUpdateOnExistingFileWithNullFields() throws IOException {
    final String sessionId = "sess-null-metadata-json";
    final Path sessionsDir = tempDir.resolve(SESSIONS_DIR);
    Files.createDirectories(sessionsDir);
    // Create file without metadata fields
    Files.writeString(sessionsDir.resolve(sessionId + JSON_EXT), "{}");

    final String newDesc = "New Description";
    StepVerifier.create(configStore.setDescription(sessionId, newDesc)).verifyComplete();
    StepVerifier.create(configStore.getDescription(sessionId)).expectNext(newDesc).verifyComplete();

    final String newInit = "New Initiator";
    StepVerifier.create(configStore.setInitiator(sessionId, newInit)).verifyComplete();
    StepVerifier.create(configStore.getInitiator(sessionId)).expectNext(newInit).verifyComplete();

    final String newTime = "New Time";
    StepVerifier.create(configStore.setInitiatedTime(sessionId, newTime)).verifyComplete();
    StepVerifier.create(configStore.getInitiatedTime(sessionId))
        .expectNext(newTime)
        .verifyComplete();
  }

  @Test
  void testLoadSessionConfigIoException() throws IOException {
    final String sessionId = "sess-io-error-load";
    final Path sessionsDir = tempDir.resolve(SESSIONS_DIR);
    Files.createDirectories(sessionsDir);
    // Create a directory where the file should be to trigger IOException on Files.readString
    Files.createDirectories(sessionsDir.resolve(sessionId + JSON_EXT));

    StepVerifier.create(configStore.getProjectPath(sessionId)).expectError().verify();
  }

  @Test
  void testSaveSessionConfigIoException() throws IOException {
    final String sessionId = "sess-save-error";
    // Create a file where the sessions directory should be to trigger IOException on
    // createDirectories
    final Path sessionsDir = tempDir.resolve(SESSIONS_DIR);
    Files.writeString(sessionsDir, "not a directory");

    StepVerifier.create(configStore.setProjectPath(sessionId, TEST_PATH)).expectError().verify();
  }

  @Test
  @SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
  void testFileStorageStructure() throws IOException {
    final String sessionId = "sess-structure";
    configStore.setProjectPath(sessionId, "/test/path").block();

    final Path sessionsDir = tempDir.resolve(SESSIONS_DIR);
    final Path sessionFile = sessionsDir.resolve(sessionId + JSON_EXT);

    assert Files.exists(sessionFile);
    final String content = Files.readString(sessionFile);
    assert content.contains("projectPath");
    assert content.contains("/test/path");
  }

  @Test
  void testSetNullMetadata() {
    final String sessionId = "sess-null-meta";
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
    final String sessionId = "sess-persistence";
    configStore.setProjectPath(sessionId, "/persistent/path").block();

    // Create a new store instance pointing to the same baseDir
    final FileSessionConfigStore newStore =
        new FileSessionConfigStore(props, new ObjectMapper(), workflowDefinitionStore);

    // Should load from disk
    StepVerifier.create(newStore.getProjectPath(sessionId))
        .expectNext("/persistent/path")
        .verifyComplete();
  }

  @Test
  void testGetFieldsNullInFile() throws IOException {
    final String sessionId = "sess-null-fields";
    final Path sessionsDir = tempDir.resolve(SESSIONS_DIR);
    Files.createDirectories(sessionsDir);
    // Create JSON with only sessionId, all other fields missing/null
    Files.writeString(
        sessionsDir.resolve(sessionId + JSON_EXT), "{\"sessionId\":\"" + sessionId + "\"}");

    StepVerifier.create(configStore.getProjectPath(sessionId)).expectNext("").verifyComplete();
    StepVerifier.create(configStore.getTags(sessionId)).expectNext(Map.of()).verifyComplete();
    StepVerifier.create(configStore.getDescription(sessionId)).expectNext("").verifyComplete();
    StepVerifier.create(configStore.getInitiator(sessionId)).expectNext("").verifyComplete();
    StepVerifier.create(configStore.getInitiatedTime(sessionId)).expectNext("").verifyComplete();
  }

  @Test
  void testApplySessionConfig() {
    final String sessionId = "sess-apply";
    final WorkflowDefinition workflow =
        new WorkflowDefinition(
            "test-workflow",
            TEST_DESC,
            List.of(new WorkflowDefinition.Node("n1", "gradle", Map.of())),
            List.of());
    final SessionConfigData data =
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
    final String sessionId = "malformed-json";
    final Path sessionsDir = tempDir.resolve(SESSIONS_DIR);
    Files.createDirectories(sessionsDir);
    Files.writeString(sessionsDir.resolve(sessionId + JSON_EXT), "{ invalid json }");

    StepVerifier.create(configStore.getProjectPath(sessionId)).expectError().verify();
  }

  @Test
  void testSetInitiatedTimeFirst() {
    final String sessionId = "sess-time-first";
    StepVerifier.create(configStore.setInitiatedTime(sessionId, NOW)).verifyComplete();
    StepVerifier.create(configStore.getInitiatedTime(sessionId)).expectNext(NOW).verifyComplete();
  }

  @Test
  void testSetTagsFirst() {
    final String sessionId = "sess-tags-first";
    final Map<String, String> tags = Map.of("a", "b");
    StepVerifier.create(configStore.setTags(sessionId, tags)).verifyComplete();
    StepVerifier.create(configStore.getTags(sessionId)).expectNext(tags).verifyComplete();
  }

  @Test
  void testMetadataUpdateWhenExistingIsSame() {
    final String sessionId = "sess-same-meta";
    configStore.setDescription(sessionId, TEST_DESC).block();

    // Calling again with same value
    StepVerifier.create(configStore.setDescription(sessionId, TEST_DESC)).verifyComplete();
    StepVerifier.create(configStore.getDescription(sessionId))
        .expectNext(TEST_DESC)
        .verifyComplete();
  }

  @Test
  void testApplySessionConfigDelegatesWorkflowsToStore() {
    final String sessionId = "sess-wf-delegate";
    final WorkflowDefinition workflow =
        new WorkflowDefinition(
            "wf1", TEST_DESC, List.of(new WorkflowDefinition.Node("n1", "t", Map.of())), List.of());
    final SessionConfigData data =
        new SessionConfigData(
            sessionId, "some description", "init", Map.of(), TEST_PATH, Map.of("wf1", workflow));

    when(workflowDefinitionStore.save(sessionId, workflow)).thenReturn(Mono.empty());

    StepVerifier.create(configStore.applySessionConfig(data)).verifyComplete();

    verify(workflowDefinitionStore).save(sessionId, workflow);
  }

  @Test
  void testApplySessionConfigWithEmptyWorkflows() {
    final String sessionId = "sess-empty-wf";
    final SessionConfigData data =
        new SessionConfigData(sessionId, "some description", "init", Map.of(), TEST_PATH, Map.of());

    StepVerifier.create(configStore.applySessionConfig(data)).verifyComplete();

    StepVerifier.create(configStore.getProjectPath(sessionId))
        .expectNext(TEST_PATH)
        .verifyComplete();
  }

  @Test
  @SuppressWarnings({"PMD.AvoidAccessibilityAlteration", "PMD.UnitTestShouldIncludeAssert"})
  void testLogInitialization() throws Exception {
    final java.lang.reflect.Method logInitMethod =
        FileSessionConfigStore.class.getDeclaredMethod("logInitialization");
    logInitMethod.setAccessible(true);
    logInitMethod.invoke(configStore);
  }

  @Test
  @SuppressWarnings({"PMD.AvoidAccessibilityAlteration"})
  void testPathTraversalDetectionInGetSessionConfigPath() throws Exception {
    final java.lang.reflect.Method getSessionConfigPathMethod =
        FileSessionConfigStore.class.getDeclaredMethod("getSessionConfigPath", String.class);
    getSessionConfigPathMethod.setAccessible(true);

    final java.lang.reflect.InvocationTargetException invocationException =
        org.junit.jupiter.api.Assertions.assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> getSessionConfigPathMethod.invoke(configStore, "../../../etc/passwd"));

    assertThat(invocationException.getCause())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("path traversal detected");
  }

  @Test
  @SuppressWarnings({"PMD.AvoidAccessibilityAlteration"})
  void testPathTraversalDetectionWithAbsolutePathInGetSessionConfigPath() throws Exception {
    final java.lang.reflect.Method getSessionConfigPathMethod =
        FileSessionConfigStore.class.getDeclaredMethod("getSessionConfigPath", String.class);
    getSessionConfigPathMethod.setAccessible(true);

    final java.lang.reflect.InvocationTargetException invocationException =
        org.junit.jupiter.api.Assertions.assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> getSessionConfigPathMethod.invoke(configStore, "/etc/passwd"));

    assertThat(invocationException.getCause())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("path traversal detected");
  }

  @Test
  @SuppressWarnings({"PMD.AvoidAccessibilityAlteration"})
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
      justification = "Root path '/' is intentionally used to test null filename edge case")
  void testExtractSessionIdFromFileWithNullFileName() throws Exception {
    final java.lang.reflect.Method extractSessionIdMethod =
        FileSessionConfigStore.class.getDeclaredMethod("extractSessionIdFromFile", Path.class);
    extractSessionIdMethod.setAccessible(true);

    // Root path has getFileName() == null, testing defensive null check
    final String result = (String) extractSessionIdMethod.invoke(configStore, Path.of(ROOT_PATH));

    assertThat(result).isEmpty();
  }
}
