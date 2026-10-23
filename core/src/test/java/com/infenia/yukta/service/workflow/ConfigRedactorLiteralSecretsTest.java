// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** A definition that still holds a literal secret is found, wherever it sits. */
@NoArgsConstructor
class ConfigRedactorLiteralSecretsTest {

  /** The plugins the redactor asks for secret fields. */
  private final PluginRegistry registry = mock(PluginRegistry.class);

  /** The redactor under test. */
  private final ConfigRedactor redactor = new ConfigRedactor(registry);

  private List<String> literalSecrets(final Map<String, Object> config) {
    final Plugin plugin = mock(Plugin.class);
    when(plugin.getConfigSchema())
        .thenReturn(
            List.of(ConfigField.optional("dbCredential", ConfigField.Type.SECRET, null, "secret")));
    when(registry.contains("SQL_QUERY")).thenReturn(true);
    when(registry.get("SQL_QUERY")).thenReturn(plugin);
    return redactor.literalSecrets(
        new WorkflowDefinition(
            "w", "d", List.of(new WorkflowDefinition.Node("n1", "SQL_QUERY", config)), List.of()));
  }

  @Test
  void namesPathsOfLiteralsAtAnyDepthAndInLists() {
    assertThat(
            literalSecrets(
                Map.of(
                    "dbCredential",
                    "hunter2",
                    "headers",
                    Map.of("Authorization", "Bearer abc", "Accept", "text/plain"),
                    "tokens",
                    List.of("t1", "t2"),
                    "sql",
                    "select 1")))
        .containsExactlyInAnyOrder(
            "n1.dbCredential", "n1.headers.Authorization", "n1.tokens.0", "n1.tokens.1");
  }

  @Test
  void ignoresReferencesPlainFieldsAndValuesThatAreNotText() {
    assertThat(
            literalSecrets(
                Map.of(
                    "dbCredential", "decrypted:db-pass",
                    "password", "${env.DB_PASS}",
                    "apiKey", 42,
                    "sql", "select 1")))
        .isEmpty();
  }
}
