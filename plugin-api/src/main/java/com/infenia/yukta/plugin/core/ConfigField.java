// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

import java.util.List;

/**
 * One configuration parameter of a plugin, so a UI can render a form instead of a raw JSON editor.
 *
 * @param name the key in the node configuration map
 * @param type how the value is edited
 * @param required whether the plugin rejects a configuration without it
 * @param defaultValue the value used when the key is absent, or {@code null}
 * @param options the allowed values for {@link Type#ENUM}, otherwise empty
 * @param description one-line help text
 */
public record ConfigField(
    String name,
    Type type,
    boolean required,
    Object defaultValue,
    List<String> options,
    String description) {

  /** How a field is edited. */
  public enum Type {
    /** Free text; variable expressions are allowed. */
    STRING,
    /** A number. */
    NUMBER,
    /** A true/false switch. */
    BOOLEAN,
    /** One of {@link ConfigField#options()}. */
    ENUM,
    /** A list of values. */
    LIST,
    /** A map of string keys to values. */
    MAP,
    /** An expression (SpEL or a template) shown in a monospace editor. */
    EXPRESSION,
    /** A credential; the value should be a {@code decrypted:<key>} reference. */
    SECRET
  }

  /** Compact constructor to ensure immutability. */
  public ConfigField {
    options = options == null ? List.of() : List.copyOf(options);
  }

  /**
   * A required field.
   *
   * @param name the configuration key
   * @param type the edit type
   * @param description one-line help text
   * @return the field
   */
  public static ConfigField required(final String name, final Type type, final String description) {
    return new ConfigField(name, type, true, null, List.of(), description);
  }

  /**
   * An optional field.
   *
   * @param name the configuration key
   * @param type the edit type
   * @param defaultValue the value used when absent, or {@code null}
   * @param description one-line help text
   * @return the field
   */
  public static ConfigField optional(
      final String name, final Type type, final Object defaultValue, final String description) {
    return new ConfigField(name, type, false, defaultValue, List.of(), description);
  }

  /**
   * A field restricted to a set of values.
   *
   * @param name the configuration key
   * @param required whether the field is required
   * @param defaultValue the value used when absent, or {@code null}
   * @param description one-line help text
   * @param options the allowed values
   * @return the field
   */
  public static ConfigField choice(
      final String name,
      final boolean required,
      final String defaultValue,
      final String description,
      final String... options) {
    return new ConfigField(name, Type.ENUM, required, defaultValue, List.of(options), description);
  }
}
