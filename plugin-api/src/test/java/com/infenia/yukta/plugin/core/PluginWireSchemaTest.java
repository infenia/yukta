// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates the sample documents in {@code src/test/resources/schema/plugin/v1} against the plugin
 * wire schemas (manifest, message envelope in and out): each file under {@code valid} must pass and
 * each under {@code invalid} must fail. The file name's prefix names the schema.
 */
class PluginWireSchemaTest {

  private static final String DIR = "/schema/plugin/v1/";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator();
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  @ParameterizedTest(name = "{0}")
  @MethodSource("validSamples")
  void validSample_passes(final String name) throws IOException, URISyntaxException {
    final JsonSchemaValidator.ValidationResponse result = validate("valid", name);
    assertThat(result.valid()).as(result.errorMessage()).isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidSamples")
  void invalidSample_fails(final String name) throws IOException, URISyntaxException {
    assertThat(validate("invalid", name).valid()).isFalse();
  }

  /* default */ static Stream<String> validSamples() throws IOException, URISyntaxException {
    return samples("valid");
  }

  /* default */ static Stream<String> invalidSamples() throws IOException, URISyntaxException {
    return samples("invalid");
  }

  private static Stream<String> samples(final String kind) throws IOException, URISyntaxException {
    try (Stream<Path> files = Files.list(resource(kind))) {
      return files.map(f -> String.valueOf(f.getFileName())).sorted().toList().stream();
    }
  }

  private static JsonSchemaValidator.ValidationResponse validate(
      final String kind, final String name) throws IOException, URISyntaxException {
    final Map<String, Object> document =
        JSON.readValue(Files.readString(resource(kind).resolve(name)), MAP);
    return VALIDATOR.validate(schemaFor(name), document);
  }

  /** The schema a sample is for: {@code manifest-*.json}, {@code message-in-*}, ... */
  private static Map<String, Object> schemaFor(final String sample) throws IOException {
    final String prefix =
        Stream.of("manifest", "message-in", "message-out")
            .filter(sample::startsWith)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("no schema for " + sample));
    try (var in =
        Objects.requireNonNull(
            PluginWireSchemaTest.class.getResourceAsStream(DIR + prefix + ".schema.json"))) {
      return JSON.readValue(in, MAP);
    }
  }

  private static Path resource(final String kind) throws URISyntaxException {
    return Path.of(
        Objects.requireNonNull(PluginWireSchemaTest.class.getResource(DIR + kind)).toURI());
  }
}
