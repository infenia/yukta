// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.ConfigField.Type;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

/**
 * The settings every supervisor type takes next to its own, read by the server and not by the
 * supervisor. This is the one list of those keys.
 */
@UtilityClass
public class SupervisorCommonSettings {

  /** The common settings as a schema, for forms and validation. */
  public static final List<ConfigField> SCHEMA =
      List.of(
          new ConfigField(
              SupervisorCapabilities.ALLOWED_COMMANDS,
              Type.LIST,
              false,
              null,
              SupervisorCapabilities.commandNames(),
              "Command types the supervisor may send; unset takes the type's default"),
          ConfigField.optional(
              SupervisorCapabilities.CAN_ANSWER,
              Type.BOOLEAN,
              false,
              "Whether it may answer input requests"),
          ConfigField.choice(
              SupervisorCapabilities.MAX_RISK,
              false,
              "LOW",
              "The highest risk of an input request it may answer",
              "LOW",
              "MEDIUM",
              "HIGH"),
          ConfigField.optional(
              SupervisorCapabilities.OBSERVE_INTERNAL,
              Type.BOOLEAN,
              false,
              "Whether it sees events of internal sessions its scope names"));

  /** The keys of {@link #SCHEMA}. */
  public static final Set<String> KEYS =
      SCHEMA.stream().map(ConfigField::name).collect(Collectors.toUnmodifiableSet());
}
