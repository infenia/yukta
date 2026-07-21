// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for SessionConfigResponse defensive copying and null handling. */
@SuppressWarnings("PMD.MissingStaticMethodInNonInstantiatableClass")
final class SessionConfigResponseTest {

  /** Test session ID constant. */
  private static final String TEST_ID = "id";

  /** Test description constant. */
  private static final String TEST_DESC = "desc";

  /** Test initiator constant. */
  private static final String TEST_INIT = "init";

  /** Test name constant. */
  private static final String TEST_NAME = "test-name";

  /** Test path constant. */
  private static final String TEST_PATH = "path";

  /** Prevent instantiation. */
  private SessionConfigResponseTest() {
    // Prevent instantiation of test class
  }

  @Test
  void testSessionConfigResponse_withNullTags_defaultsToEmpty() {
    final var response =
        new SessionConfigResponse(
            TEST_ID, TEST_NAME, TEST_DESC, TEST_INIT, null, TEST_PATH, Map.of());
    assertThat(response.tags()).isEmpty();
  }

  @Test
  void testSessionConfigResponse_withNullWorkflows_defaultsToEmpty() {
    final var response =
        new SessionConfigResponse(
            TEST_ID, TEST_NAME, TEST_DESC, TEST_INIT, Map.of(), TEST_PATH, null);
    assertThat(response.workflows()).isEmpty();
  }

  @Test
  void testSessionConfigResponse_defensivelyCopiesTags() {
    final var tags = new HashMap<String, String>();
    tags.put("env", "test");
    final var response =
        new SessionConfigResponse(
            TEST_ID, TEST_NAME, TEST_DESC, TEST_INIT, tags, TEST_PATH, Map.of());
    tags.put("extra", "value");
    assertThat(response.tags()).hasSize(1).containsEntry("env", "test");
  }

  @Test
  void testSessionConfigResponse_defensivelyCopiesWorkflows() {
    final var workflows = new HashMap<String, WorkflowDefinition>();
    final var response =
        new SessionConfigResponse(
            TEST_ID, TEST_NAME, TEST_DESC, TEST_INIT, Map.of(), TEST_PATH, workflows);
    workflows.put("w1", new WorkflowDefinition("w1", "description", List.of(), List.of()));
    assertThat(response.workflows()).isEmpty();
  }
}
