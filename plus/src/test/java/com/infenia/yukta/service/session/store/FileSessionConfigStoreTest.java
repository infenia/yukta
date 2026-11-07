// SPDX-License-Identifier: AGPL-3.0-only
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
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidInstantiatingObjectsInLoops"
})
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
    final ObjectMapper objectMapper = new ObjectMapper();
    when(workflowDefinitionStore.findAll(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Mono.just(Map.of()));
    configStore = new FileSessionConfigStore(props, objectMapper, workflowDefinitionStore);
  }

  @Test
  void testGetSessionIdsEmpty() {
    StepVerifier.create(configStore.getSessionIds().collectList())
        .expectNext(List.of())
        .verifyComplete();
  }

  @Test
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
  void testGetSessionIdsIoException() throws IOException {
    // Create a file where the sessions directory should be to trigger IOException on Files.list
    final Path sessionsDir = tempDir.resolve(SESSIONS_DIR);
    Files.writeString(sessionsDir, "not a directory");

    StepVerifier.create(configStore.getSessionIds()).expectError(IOException.class).verify();
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
  void testGetFieldsNullInFile() throws IOException {
    final String sessionId = "sess-null-fields";
    final Path sessionsDir = tempDir.resolve(SESSIONS_DIR);
    Files.createDirectories(sessionsDir);
    // Create JSON with only sessionId, all other fields missing/null
    final String jsonContent = "{\"sessionId\":\"" + sessionId + "\"}";
    Files.writeString(sessionsDir.resolve(sessionId + JSON_EXT), jsonContent);

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
            "apply-session",
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
  void testApplySessionConfigDelegatesWorkflowsToStore() {
    final String sessionId = "sess-wf-delegate";
    final WorkflowDefinition workflow =
        new WorkflowDefinition(
            "wf1", TEST_DESC, List.of(new WorkflowDefinition.Node("n1", "t", Map.of())), List.of());
    final SessionConfigData data =
        new SessionConfigData(
            sessionId,
            "workflow-delegate",
            "some description",
            "init",
            Map.of(),
            TEST_PATH,
            Map.of("wf1", workflow));

    when(workflowDefinitionStore.save(sessionId, workflow)).thenReturn(Mono.empty());

    StepVerifier.create(configStore.applySessionConfig(data)).verifyComplete();

    verify(workflowDefinitionStore).save(sessionId, workflow);
  }

  @Test
  void testApplySessionConfigWithEmptyWorkflows() {
    final String sessionId = "sess-empty-wf";
    final SessionConfigData data =
        new SessionConfigData(
            sessionId,
            "empty-workflows",
            "some description",
            "init",
            Map.of(),
            TEST_PATH,
            Map.of());

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

  @Test
  void removeSession_deletesTheFileAndTheCachedCopy_andIsQuietForAnUnknownSession() {
    final SessionConfigData data =
        new SessionConfigData("gone", "n", "d", "i", Map.of(), "/p", Map.of());
    StepVerifier.create(configStore.applySessionConfig(data)).verifyComplete();
    assertThat(tempDir.resolve("sessions/gone.json")).exists();

    StepVerifier.create(configStore.removeSession("gone")).verifyComplete();
    StepVerifier.create(configStore.removeSession("never-was")).verifyComplete();

    assertThat(tempDir.resolve("sessions/gone.json")).doesNotExist();
    assertThat(configStore.getSessionIds().collectList().block()).doesNotContain("gone");
    StepVerifier.create(configStore.getDescription("gone")).expectNext("").verifyComplete();
  }

  @Test
  void removeSession_failingToDeleteTheFile_isReportedNotSwallowed() throws IOException {
    final Path blocker = tempDir.resolve("sessions/stuck.json");
    Files.createDirectories(blocker);
    Files.writeString(blocker.resolve("child"), "x");

    StepVerifier.create(configStore.removeSession("stuck"))
        .expectError(java.io.UncheckedIOException.class)
        .verify();
  }

  private static SessionConfigData data(final String id, final String initiator) {
    return new SessionConfigData(id, "n", "d", initiator, Map.of("k", "v"), "/p", Map.of());
  }

  @Test
  void setInitiator_null_changesNothing() {
    StepVerifier.create(configStore.setInitiator("nobody", null)).verifyComplete();

    assertThat(configStore.getSessionIds().collectList().block()).isEmpty();
  }

  @Test
  void setInitiator_keepsTheInitiatorAlreadySet() {
    configStore.applySessionConfig(data("s1", "first")).block();

    configStore.setInitiator("s1", "second").block();

    assertThat(configStore.getInitiator("s1").block()).isEqualTo("first");
  }

  @Test
  void setInitiator_fillsAnEmptyInitiator() {
    configStore.applySessionConfig(data("s1", "")).block();

    configStore.setInitiator("s1", "someone").block();

    assertThat(configStore.getInitiator("s1").block()).isEqualTo("someone");
  }

  @Test
  void aNewStoreReadsWhatAnotherWrote() {
    configStore.applySessionConfig(data("s1", "first")).block();
    final FileSessionConfigStore reopened =
        new FileSessionConfigStore(props, new ObjectMapper(), workflowDefinitionStore);

    assertThat(reopened.getInitiator("s1").block()).isEqualTo("first");
    assertThat(reopened.getTags("s1").block()).containsEntry("k", "v");
    assertThat(reopened.getInitiatedTime("s1").block()).isNotNull();
    assertThat(reopened.getProjectPath("s1").block()).isEqualTo("/p");
  }

  @Test
  void anUnknownSessionReadsAsEmpty() {
    assertThat(configStore.getInitiator("none").block()).isEmpty();
    assertThat(configStore.getDescription("none").block()).isEmpty();
  }

  @Test
  void setInitiator_fillsAFileThatHasNoInitiatorAtAll() throws IOException {
    final Path sessions = tempDir.resolve(SESSIONS_DIR);
    Files.createDirectories(sessions);
    Files.writeString(sessions.resolve("bare" + JSON_EXT), "{}");

    configStore.setInitiator("bare", "someone").block();

    assertThat(configStore.getInitiator("bare").block()).isEqualTo("someone");
  }
}
