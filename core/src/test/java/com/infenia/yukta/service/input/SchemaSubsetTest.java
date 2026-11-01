// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** The part of JSON Schema that a response schema may use. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
class SchemaSubsetTest {

  private static final String PERSON =
      """
      {"type":"object","required":["name"],
       "properties":{"name":{"type":"string"},"age":{"type":"integer"},
                     "tags":{"type":"array","items":{"type":"string"}},
                     "level":{"enum":["low","high"]}},
       "additionalProperties":false}
      """;

  @Test
  void dataThatFitsHasNoViolations() {
    assertThat(
            SchemaSubset.violations(
                PERSON, Map.of("name", "a", "age", 3, "tags", List.of("x", "y"), "level", "low")))
        .isEmpty();
  }

  @Test
  void missingRequiredFieldIsNamed() {
    assertThat(SchemaSubset.violations(PERSON, Map.of("age", 3)))
        .containsExactly("$.name: required");
  }

  @Test
  void wrongTypeIsNamedWithoutRepeatingTheValue() {
    assertThat(SchemaSubset.violations(PERSON, Map.of("name", "a", "age", "secret-token")))
        .containsExactly("$.age: expected integer")
        .noneMatch(message -> message.contains("secret-token"));
  }

  @Test
  void anUnknownFieldIsRefusedWhenTheSchemaIsClosed() {
    assertThat(SchemaSubset.violations(PERSON, Map.of("name", "a", "extra", true)))
        .containsExactly("$.extra: not allowed");
  }

  @Test
  void anUnknownFieldPassesWhenTheSchemaIsOpen() {
    assertThat(
            SchemaSubset.violations(
                "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}",
                Map.of("a", "x", "b", 1)))
        .isEmpty();
  }

  @Test
  void valueOutsideTheEnumIsNamed() {
    assertThat(SchemaSubset.violations(PERSON, Map.of("name", "a", "level", "mid")))
        .containsExactly("$.level: not one of the allowed values");
  }

  @Test
  void itemsAreCheckedWithTheirIndex() {
    assertThat(SchemaSubset.violations(PERSON, Map.of("name", "a", "tags", List.of("x", 2))))
        .containsExactly("$.tags[1]: expected string");
  }

  @Test
  void everyTypeKeywordIsUnderstood() {
    final String schema =
        """
        {"type":"object","properties":{
          "o":{"type":"object"},"a":{"type":"array"},"s":{"type":"string"},
          "n":{"type":"number"},"i":{"type":"integer"},"b":{"type":"boolean"},
          "u":{"type":"unknown-type"}}}
        """;

    assertThat(
            SchemaSubset.violations(
                schema,
                Map.of(
                    "o", Map.of(), "a", List.of(), "s", "x", "n", 1.5, "i", 2, "b", true, "u",
                    "any")))
        .isEmpty();
    assertThat(
            SchemaSubset.violations(
                schema, Map.of("o", "x", "a", "x", "s", 1, "n", "x", "i", 1.5, "b", "x")))
        .hasSize(6);
  }

  @Test
  void nullIsTypeToo() {
    final String schema = "{\"properties\":{\"z\":{\"type\":\"null\"}}}";

    assertThat(SchemaSubset.violations(schema, Collections.singletonMap("z", null))).isEmpty();
    assertThat(SchemaSubset.violations(schema, Map.of("z", 1)))
        .containsExactly("$.z: expected null");
  }

  @Test
  void valueOfTheWrongTypeIsNotCheckedAgainstTheRest() {
    assertThat(
            SchemaSubset.violations(
                "{\"properties\":{\"a\":{\"type\":\"object\",\"required\":[\"x\"]}}}",
                Map.of("a", "not-an-object")))
        .containsExactly("$.a: expected object");
  }

  @Test
  void keywordsOfTheWrongShapeAreIgnored() {
    assertThat(
            SchemaSubset.violations(
                "{\"type\":5,\"enum\":\"x\",\"required\":\"x\",\"properties\":[],"
                    + "\"additionalProperties\":\"no\"}",
                Map.of("a", 1)))
        .isEmpty();
    assertThat(
            SchemaSubset.violations(
                "{\"properties\":{\"l\":{\"items\":1}},\"required\":[1],"
                    + "\"additionalProperties\":true}",
                Map.of("l", List.of("x"), "other", 2)))
        .isEmpty();
  }
}
