// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow;

import com.infenia.yukta.model.session.SessionConfigResponse;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Hides literal secrets in workflow definitions that the read APIs hand out.
 *
 * <p>A value is masked when it is a literal, non-empty text under a secret key: a field the node's
 * plugin declares as {@link ConfigField.Type#SECRET}, or any key at any depth whose name says it
 * holds a secret (password, token, API key, authorization, credential, secret). A reference, such
 * as {@code decrypted:db-pass} or {@code ${env.X}}, is not a secret and is left as it is, so the
 * way to keep a secret out of a definition is a reference. A definition that still holds a literal
 * secret is refused by {@link #literalSecrets}.
 *
 * <p>The mask is not a value to save: {@link #placeholders} finds one that came back in a
 * definition, so that a client that read a masked definition and sends it back cannot overwrite the
 * real secret with it.
 */
@Component
@RequiredArgsConstructor
@SuppressWarnings("PMD.UseConcurrentHashMap")
public class ConfigRedactor {

  /** What replaces a secret in a definition that is handed out. */
  public static final String PLACEHOLDER = "******";

  /** Names that say a value is a secret. */
  private static final Pattern SECRET_NAME =
      Pattern.compile(
          ".*(password|passwd|secret|token|api[-_]?key|authorization|credential).*",
          Pattern.CASE_INSENSITIVE);

  /** The plugins, to find which fields of a node are secret. */
  private final PluginRegistry plugins;

  /**
   * Masks the literal secrets of a workflow.
   *
   * @param workflow the stored definition
   * @return a copy for handing out
   */
  public WorkflowDefinition redact(final WorkflowDefinition workflow) {
    return new WorkflowDefinition(
        workflow.workflowId(),
        workflow.description(),
        workflow.nodes().stream()
            .map(
                node ->
                    new WorkflowDefinition.Node(
                        node.nodeId(), node.type(), redactNode(node), node.group()))
            .toList(),
        workflow.edges());
  }

  /**
   * Masks the literal secrets of every workflow of a session.
   *
   * @param session the stored configuration
   * @return a copy for handing out
   */
  public SessionConfigResponse redact(final SessionConfigResponse session) {
    final Map<String, WorkflowDefinition> workflows = new LinkedHashMap<>();
    session.workflows().forEach((id, workflow) -> workflows.put(id, redact(workflow)));
    return new SessionConfigResponse(
        session.sessionId(),
        session.name(),
        session.description(),
        session.initiator(),
        session.tags(),
        session.projectPath(),
        workflows);
  }

  /**
   * Finds masks in a definition that is about to be saved.
   *
   * @param workflow the definition
   * @return the paths ({@code node.key.key}) that hold the mask, empty if none does
   */
  public static List<String> placeholders(final WorkflowDefinition workflow) {
    final List<String> found = new ArrayList<>();
    workflow.nodes().forEach(node -> collect(node.nodeId(), node.config(), found));
    return found;
  }

  /**
   * Finds literal secrets in a definition that is about to be saved or run.
   *
   * @param workflow the definition
   * @return the paths ({@code node.key.key}) that hold a literal secret, empty if none does
   */
  public List<String> literalSecrets(final WorkflowDefinition workflow) {
    final List<String> found = new ArrayList<>();
    for (final WorkflowDefinition.Node node : workflow.nodes()) {
      final Set<String> secretFields = secretFields(node.type());
      node.config()
          .forEach(
              (key, value) ->
                  collectLiterals(
                      node.nodeId() + "." + key,
                      value,
                      secretFields.contains(key) || isSecretName(key),
                      found));
    }
    return found;
  }

  /**
   * Tells whether a node config holds a credential, as a literal or as a reference: a field the
   * plugin declares as secret, or a key at any depth whose name says it holds one.
   *
   * @param schema the config fields of the node's plugin
   * @param config the node config
   * @return true if the node handles a secret
   */
  public static boolean hasSecretConfig(
      final List<ConfigField> schema, final Map<String, Object> config) {
    final Set<String> declared =
        schema.stream()
            .filter(field -> field.type() == ConfigField.Type.SECRET)
            .map(ConfigField::name)
            .collect(Collectors.toSet());
    return config.keySet().stream().anyMatch(declared::contains) || hasSecretKey(config);
  }

  private static boolean hasSecretKey(final Object value) {
    if (value instanceof final Map<?, ?> map) {
      return map.entrySet().stream()
          .anyMatch(e -> isSecretName(String.valueOf(e.getKey())) || hasSecretKey(e.getValue()));
    }
    return value instanceof final List<?> list
        && list.stream().anyMatch(ConfigRedactor::hasSecretKey);
  }

  private static void collectLiterals(
      final String path, final Object value, final boolean secret, final List<String> found) {
    if (value instanceof final Map<?, ?> map) {
      map.forEach(
          (key, inner) ->
              collectLiterals(
                  path + "." + key, inner, secret || isSecretName(String.valueOf(key)), found));
    } else if (value instanceof final List<?> list) {
      for (int i = 0; i < list.size(); i++) {
        collectLiterals(path + "." + i, list.get(i), secret, found);
      }
    } else if (secret && value instanceof final String text && isLiteral(text)) {
      found.add(path);
    }
  }

  private Map<String, Object> redactNode(final WorkflowDefinition.Node node) {
    final Set<String> secretFields = secretFields(node.type());
    final Map<String, Object> redacted = new LinkedHashMap<>();
    node.config()
        .forEach(
            (key, value) ->
                redacted.put(
                    key, redactValue(value, secretFields.contains(key) || isSecretName(key))));
    return redacted;
  }

  private Set<String> secretFields(final String type) {
    if (!plugins.contains(type)) {
      return Set.of();
    }
    return plugins.get(type).getConfigSchema().stream()
        .filter(field -> field.type() == ConfigField.Type.SECRET)
        .map(ConfigField::name)
        .collect(Collectors.toSet());
  }

  private Object redactValue(final Object value, final boolean secret) {
    if (value instanceof final Map<?, ?> map) {
      final Map<String, Object> redacted = new LinkedHashMap<>();
      map.forEach(
          (key, inner) ->
              redacted.put(
                  String.valueOf(key),
                  redactValue(inner, secret || isSecretName(String.valueOf(key)))));
      return redacted;
    }
    if (value instanceof final List<?> list) {
      return list.stream().map(inner -> redactValue(inner, secret)).toList();
    }
    return secret && value instanceof final String text && isLiteral(text) ? PLACEHOLDER : value;
  }

  private static void collect(final String path, final Object value, final List<String> found) {
    if (value instanceof final Map<?, ?> map) {
      map.forEach((key, inner) -> collect(path + "." + key, inner, found));
    } else if (value instanceof final List<?> list) {
      for (int i = 0; i < list.size(); i++) {
        collect(path + "." + i, list.get(i), found);
      }
    } else if (PLACEHOLDER.equals(value)) {
      found.add(path);
    }
  }

  private static boolean isSecretName(final String key) {
    return SECRET_NAME.matcher(key).matches();
  }

  private static boolean isLiteral(final String text) {
    return !text.isEmpty()
        && !text.startsWith("decrypted:")
        && !text.contains("${")
        && !PLACEHOLDER.equals(text);
  }
}
