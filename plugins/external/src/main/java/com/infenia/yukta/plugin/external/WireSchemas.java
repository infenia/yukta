// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.external;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The plugin wire schemas of plugin-api ({@code schema/plugin/v1}) and their validator. */
/* default */ final class WireSchemas {

  /** The JSON type of an object. */
  /* default */ static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

  private final JsonSchemaValidator validator = new DefaultJsonSchemaValidator();
  private final Map<String, Object> manifest;
  private final Map<String, Object> messageOut;

  /* default */ WireSchemas(final JsonMapper json) {
    this.manifest = load(json, "manifest");
    this.messageOut = load(json, "message-out");
  }

  /**
   * Why a manifest is invalid.
   *
   * @param doc the parsed manifest
   * @return the reason, or null when it is valid
   */
  /* default */ @Nullable String manifestRefusal(final Object doc) {
    return refusal(manifest, doc);
  }

  /**
   * Why one output object of a plugin is invalid.
   *
   * @param doc the parsed output line
   * @return the reason, or null when it is valid
   */
  /* default */ @Nullable String outputRefusal(final Object doc) {
    return refusal(messageOut, doc);
  }

  private @Nullable String refusal(final Map<String, Object> schema, final Object doc) {
    final JsonSchemaValidator.ValidationResponse r = validator.validate(schema, doc);
    return r.valid() ? null : r.errorMessage();
  }

  /** Reads a schema of plugin-api. */
  @SuppressFBWarnings(
      value = "OBL_UNSATISFIED_OBLIGATION",
      justification = "Jackson closes the stream it reads (AUTO_CLOSE_SOURCE is on by default)")
  private static Map<String, Object> load(final JsonMapper json, final String name) {
    final String path = "/schema/plugin/v1/" + name + ".schema.json";
    return json.readValue(
        Objects.requireNonNull(WireSchemas.class.getResourceAsStream(path), path), OBJECT);
  }
}
