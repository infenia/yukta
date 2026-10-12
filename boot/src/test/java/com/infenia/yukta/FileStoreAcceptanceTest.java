// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import com.infenia.yukta.service.workflow.store.FileWorkflowDefinitionStore;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** With the file store the application keeps workflow definitions on disk. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "yukta.session.store-type=file")
@SuppressWarnings("PMD.CommentRequired")
@NoArgsConstructor
class FileStoreAcceptanceTest {

  @Autowired private WorkflowDefinitionStore definitions;

  @DynamicPropertySource
  /* default */ static void baseDir(final DynamicPropertyRegistry registry) throws IOException {
    final String dir = Files.createTempDirectory("yukta-store").toString();
    registry.add("yukta.session.base-dir", () -> dir);
  }

  @Test
  void definitionsAreKeptOnDisk_withTheirVersions() {
    final WorkflowDefinition definition =
        new WorkflowDefinition(
            "w", "d", List.of(new Node("n", "CONSTANT_SOURCE", Map.of())), List.of());

    definitions.save("s", definition).block();

    assertThat(definitions).isInstanceOf(FileWorkflowDefinitionStore.class);
    assertThat(definitions.find("s", "w").block()).isEqualTo(definition);
    assertThat(definitions.revisions("s", "w").collectList().block()).hasSize(1);
  }
}
