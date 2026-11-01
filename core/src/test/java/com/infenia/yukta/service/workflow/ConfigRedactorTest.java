// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.session.SessionConfigResponse;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Literal secrets in a workflow definition are not handed out by the read APIs. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class ConfigRedactorTest {

  /** The mask the read APIs write. */
  private static final String MASK = ConfigRedactor.PLACEHOLDER;

  /** The plugins the redactor asks for secret fields. */
  private final PluginRegistry registry = mock(PluginRegistry.class);

  /** The redactor under test. */
  private final ConfigRedactor redactor = new ConfigRedactor(registry);

  private WorkflowDefinition workflow(final String type, final Map<String, Object> config) {
    return new WorkflowDefinition(
        "w", "d", List.of(new WorkflowDefinition.Node("n1", type, config)), List.of());
  }

  private Map<String, Object> redactedConfig(final String type, final Map<String, Object> config) {
    return redactor.redact(workflow(type, config)).nodes().getFirst().config();
  }

  private void typeWithSecretField(final String type, final String field) {
    final Plugin plugin = mock(Plugin.class);
    when(plugin.getConfigSchema())
        .thenReturn(
            List.of(
                ConfigField.optional(field, ConfigField.Type.SECRET, null, "secret"),
                ConfigField.optional("sql", ConfigField.Type.STRING, null, "statement")));
    when(registry.contains(type)).thenReturn(true);
    when(registry.get(type)).thenReturn(plugin);
  }

  @Test
  void redactingKeepsTheNodeGroup() {
    final WorkflowDefinition grouped =
        new WorkflowDefinition(
            "w", "d", List.of(new WorkflowDefinition.Node("n1", "X", Map.of(), "lint")), List.of());
    assertThat(redactor.redact(grouped).nodes().getFirst().group()).isEqualTo("lint");
  }

  @Test
  void literalValueInPluginSecretFieldIsMasked() {
    typeWithSecretField("SQL_QUERY", "dbCredential");

    final Map<String, Object> config =
        redactedConfig(
            "SQL_QUERY",
            Map.of("url", "jdbc:h2:mem:x", "dbCredential", "hunter2", "sql", "select 1"));

    assertThat(config)
        .containsEntry("dbCredential", MASK)
        .containsEntry("url", "jdbc:h2:mem:x")
        .containsEntry("sql", "select 1");
  }

  @Test
  void referencesAreNotSecretsAndStayAsTheyAre() {
    typeWithSecretField("SQL_QUERY", "dbCredential");

    assertThat(redactedConfig("SQL_QUERY", Map.of("dbCredential", "decrypted:db-pass")))
        .containsEntry("dbCredential", "decrypted:db-pass");
    assertThat(redactedConfig("SQL_QUERY", Map.of("dbCredential", "${env.DB_PASS}")))
        .containsEntry("dbCredential", "${env.DB_PASS}");
  }

  @Test
  void namesThatSoundLikeSecretsAreMaskedAtAnyDepthEvenForUnknownTypes() {
    final Map<String, Object> config =
        redactedConfig(
            "SOMETHING_ELSE",
            Map.of(
                "headers", Map.of("Authorization", "Bearer abc", "X-Trace", "t1"),
                "auth", List.of(Map.of("apiKey", "k1", "label", "main")),
                "password", "pw"));

    assertThat(config).containsEntry("password", MASK);
    assertThat(config.get("headers")).isEqualTo(Map.of("Authorization", MASK, "X-Trace", "t1"));
    assertThat(config.get("auth")).isEqualTo(List.of(Map.of("apiKey", MASK, "label", "main")));
  }

  @Test
  void everyLeafUnderSecretNamedKeyOrSecretFieldIsMasked() {
    typeWithSecretField("SQL_QUERY", "dbCredential");

    final Map<String, Object> config =
        redactedConfig(
            "SQL_QUERY",
            Map.of(
                "credentials", Map.of("value", "hunter2", "more", List.of("a", Map.of("b", "c"))),
                "dbCredential", Map.of("user", "u", "pass", "p"),
                "other", Map.of("value", "visible")));

    assertThat(config.get("credentials"))
        .isEqualTo(Map.of("value", MASK, "more", List.of(MASK, Map.of("b", MASK))));
    assertThat(config.get("dbCredential")).isEqualTo(Map.of("user", MASK, "pass", MASK));
    assertThat(config.get("other")).isEqualTo(Map.of("value", "visible"));
    assertThat(
            redactor.literalSecrets(
                workflow("SOMETHING_ELSE", Map.of("secrets", Map.of("DB", "pw")))))
        .containsExactly("n1.secrets.DB");
  }

  @Test
  void onlyTextIsMaskedAndTheMaskIsNotMaskedAgain() {
    assertThat(redactedConfig("X", Map.of("token", 12_345, "password", MASK)))
        .containsEntry("token", 12_345)
        .containsEntry("password", MASK);
  }

  @Test
  void anEmptyValueIsLeftAlone() {
    assertThat(redactedConfig("X", Map.of("password", ""))).containsEntry("password", "");
  }

  @Test
  void wholeSessionIsRedactedAndKeepsOtherFields() {
    final SessionConfigResponse session =
        new SessionConfigResponse(
            "s",
            "name",
            "desc",
            "me",
            Map.of("t", "v"),
            "/p",
            Map.of("w", workflow("X", Map.of("token", "t0p"))));

    final SessionConfigResponse redacted = redactor.redact(session);

    assertThat(redacted.name()).isEqualTo("name");
    assertThat(redacted.tags()).isEqualTo(Map.of("t", "v"));
    assertThat(redacted.workflows().get("w").nodes().getFirst().config())
        .containsEntry("token", MASK);
  }

  @Test
  void findsPlaceholdersThatCameBackInDefinition() {
    final WorkflowDefinition definition =
        workflow("X", Map.of("headers", Map.of("Authorization", MASK), "url", "http://x"));

    assertThat(ConfigRedactor.placeholders(definition)).containsExactly("n1.headers.Authorization");
    assertThat(ConfigRedactor.placeholders(workflow("X", Map.of("password", "real")))).isEmpty();
    assertThat(ConfigRedactor.placeholders(workflow("X", Map.of("tokens", List.of("ok", MASK)))))
        .containsExactly("n1.tokens.1");
  }
}
