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
package com.infenia.yukta.cli.command.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.cli.CliFormatter;
import com.infenia.yukta.cli.YuktaDaemonClient;
import com.infenia.yukta.dto.request.ConfigRequest;
import com.infenia.yukta.dto.request.WorkflowDefinitionRequest;
import com.infenia.yukta.dto.request.WorkflowDefinitionRequest.NodeRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class SessionApplyCommandTest {
  @Mock private YuktaDaemonClient mockClient;
  @Mock private ObjectMapper mockMapper;
  @Mock private CliFormatter mockFormatter;
  private SessionApplyCommand command;

  @BeforeEach
  void setUp() {
    command = new SessionApplyCommand(mockClient, mockMapper, mockFormatter);
  }

  @Test
  void constructor_createsInstance() {
    assertThat(command).isNotNull();
  }

  @Test
  void isRunnable() {
    assertThat(command).isInstanceOf(Runnable.class);
  }

  @Test
  void run_validInlineJsonWithDefaultTableFormat_appliesSessionAndPrintsTable() throws Exception {
    // Given
    final var configRequest = createValidConfigRequest("session1");
    final var jsonInput = "{\"sessionId\":\"session1\"}";
    when(mockMapper.readValue(jsonInput, ConfigRequest.class)).thenReturn(configRequest);
    setPrivateField("configInput", jsonInput);

    // When
    command.run();

    // Then
    verify(mockClient).applySession(configRequest);
    var tableCaptor = ArgumentCaptor.forClass(List.class);
    verify(mockFormatter).printTable(tableCaptor.capture());
    assertThat(tableCaptor.getValue()).containsExactly("Session applied: session1");
  }

  @Test
  void run_validInlineJsonWithJsonFormat_appliesSessionAndPrintsJson() throws Exception {
    // Given
    final var configRequest = createValidConfigRequest("session2");
    final var jsonInput = "{\"sessionId\":\"session2\"}";
    when(mockMapper.readValue(jsonInput, ConfigRequest.class)).thenReturn(configRequest);
    setPrivateField("configInput", jsonInput);
    setPrivateField("outputFormat", "json");

    // When
    command.run();

    // Then
    verify(mockClient).applySession(configRequest);
    var jsonCaptor = ArgumentCaptor.forClass(Map.class);
    verify(mockFormatter).printJson(jsonCaptor.capture());
    Map<String, String> capturedJson = jsonCaptor.getValue();
    assertThat(capturedJson).containsEntry("status", "success");
    assertThat(capturedJson).containsEntry("message", "Session applied: session2");
  }

  @Test
  void run_invalidJsonFormat_throwsRuntimeException() throws Exception {
    // Given
    final var invalidJson = "{\"invalid\"}";
    when(mockMapper.readValue(invalidJson, ConfigRequest.class))
        .thenThrow(new IllegalArgumentException("Invalid config JSON"));
    setPrivateField("configInput", invalidJson);

    // When-Then
    assertThatThrownBy(command::run)
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
    verify(mockFormatter, never()).printTable(any());
    verify(mockFormatter, never()).printJson(any());
  }

  @Test
  void run_daemonClientThrowsException_wrapsInRuntimeException() throws Exception {
    // Given
    final var configRequest = createValidConfigRequest("session3");
    final var jsonInput = "{\"sessionId\":\"session3\"}";
    when(mockMapper.readValue(jsonInput, ConfigRequest.class)).thenReturn(configRequest);
    doThrow(new RuntimeException("Daemon connection failed"))
        .when(mockClient)
        .applySession(configRequest);
    setPrivateField("configInput", jsonInput);

    // When-Then
    assertThatThrownBy(command::run).isInstanceOf(RuntimeException.class);
    verify(mockFormatter, never()).printTable(any());
    verify(mockFormatter, never()).printJson(any());
  }

  @Test
  void run_outputFormatNotJsonNorTable_defaultsToTable() throws Exception {
    // Given
    final var configRequest = createValidConfigRequest("session4");
    final var jsonInput = "{\"sessionId\":\"session4\"}";
    when(mockMapper.readValue(jsonInput, ConfigRequest.class)).thenReturn(configRequest);
    setPrivateField("configInput", jsonInput);
    setPrivateField("outputFormat", "invalid-format");

    // When
    command.run();

    // Then - defaults to table format
    verify(mockFormatter).printTable(any());
    verify(mockFormatter, never()).printJson(any());
  }

  @Test
  void readConfigInput_inlineJsonString_returnsInputAsIs() throws Exception {
    // Given
    final var inlineJson = "{\"test\": \"value\"}";

    // When
    var result = invokeReadConfigInput(inlineJson);

    // Then
    assertThat(result).isEqualTo(inlineJson);
  }

  @Test
  void readConfigInput_filePathWithExistingFile_returnsFileContent(@TempDir Path tempDir)
      throws Exception {
    // Given
    final var testFile = tempDir.resolve("config.json");
    final var expectedContent = "{\"sessionId\": \"from-file\", \"description\": \"test\"}";
    Files.writeString(testFile, expectedContent);

    // When
    var result = invokeReadConfigInput(testFile.toString());

    // Then
    assertThat(result).isEqualTo(expectedContent);
  }

  @Test
  void readConfigInput_nonExistentFilePath_treatsAsInlineJson() throws Exception {
    // Given
    final var nonExistentPath = "/nonexistent/path/to/config.json";

    // When
    var result = invokeReadConfigInput(nonExistentPath);

    // Then - returns input as-is since file doesn't exist
    assertThat(result).isEqualTo(nonExistentPath);
  }

  @Test
  void readConfigInput_invalidFilePathSyntax_treatAsInlineJson() throws Exception {
    // Given
    final var invalidPath = "\0invalid\0path"; // null bytes are invalid in paths

    // When
    var result = invokeReadConfigInput(invalidPath);

    // Then - returns input as-is since path is invalid
    assertThat(result).isEqualTo(invalidPath);
  }

  @Test
  void run_daemonClientApplySessionCompletes_stillPrintsOutput() throws Exception {
    // Given
    final var configRequest = createValidConfigRequest("session5");
    final var jsonInput = "{\"sessionId\":\"session5\"}";
    when(mockMapper.readValue(jsonInput, ConfigRequest.class)).thenReturn(configRequest);
    doNothing().when(mockClient).applySession(configRequest);
    setPrivateField("configInput", jsonInput);

    // When
    command.run();

    // Then - still prints table output
    var tableCaptor = ArgumentCaptor.forClass(List.class);
    verify(mockFormatter).printTable(tableCaptor.capture());
    assertThat(tableCaptor.getValue()).containsExactly("Session applied: session5");
  }

  @Test
  void run_sessionIdExtractedCorrectlyFromRequest() throws Exception {
    // Given
    final var sessionId = "unique-session-id-12345";
    final var configRequest = createValidConfigRequest(sessionId);
    final var jsonInput = "{\"sessionId\":\"" + sessionId + "\"}";
    when(mockMapper.readValue(jsonInput, ConfigRequest.class)).thenReturn(configRequest);
    setPrivateField("configInput", jsonInput);

    // When
    command.run();

    // Then - verifies correct sessionId is used in message
    var tableCaptor = ArgumentCaptor.forClass(List.class);
    verify(mockFormatter).printTable(tableCaptor.capture());
    assertThat(tableCaptor.getValue()).containsExactly("Session applied: " + sessionId);
  }

  @Test
  void run_jsonFormatWithCustomSessionId_printsCorrectMessage() throws Exception {
    // Given
    final var sessionId = "custom-json-session";
    final var configRequest = createValidConfigRequest(sessionId);
    final var jsonInput = "{\"sessionId\":\"" + sessionId + "\"}";
    when(mockMapper.readValue(jsonInput, ConfigRequest.class)).thenReturn(configRequest);
    setPrivateField("configInput", jsonInput);
    setPrivateField("outputFormat", "json");

    // When
    command.run();

    // Then
    var jsonCaptor = ArgumentCaptor.forClass(Map.class);
    verify(mockFormatter).printJson(jsonCaptor.capture());
    Map<String, String> capturedJson = jsonCaptor.getValue();
    assertThat(capturedJson).containsEntry("message", "Session applied: " + sessionId);
  }

  @Test
  void run_mapperThrowsCheckedException_wrappedInRuntimeException() throws Exception {
    // Given
    final var jsonInput = "{\"test\": \"value\"}";
    when(mockMapper.readValue(jsonInput, ConfigRequest.class))
        .thenThrow(new RuntimeException("Mapper error"));
    setPrivateField("configInput", jsonInput);

    // When-Then
    assertThatThrownBy(command::run).isInstanceOf(RuntimeException.class);
  }

  // ============== Helper Methods ==============

  private ConfigRequest createValidConfigRequest(String sessionId) {
    final var node = new NodeRequest("node-1", "quality-check", Map.of("timeout", "5000"));
    final var workflow =
        new WorkflowDefinitionRequest(
            "workflow-1", "Quality check workflow", List.of(node), List.of());
    return new ConfigRequest(
        sessionId,
        "Test session",
        "TestUser",
        Map.of("environment", "test"),
        "/test/path",
        Map.of("workflow-1", workflow));
  }

  private void setPrivateField(String fieldName, String value)
      throws NoSuchFieldException, IllegalAccessException {
    final var field = SessionApplyCommand.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(command, value);
  }

  private String invokeReadConfigInput(String input) throws Exception {
    final var method = SessionApplyCommand.class.getDeclaredMethod("readConfigInput", String.class);
    method.setAccessible(true);
    return (String) method.invoke(command, input);
  }
}
