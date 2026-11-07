// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.ConfigField.Type;
import com.infenia.yukta.service.supervisor.SettingsValidationException.Issue;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Settings are checked against the type's schema and the common one. */
@NoArgsConstructor
class SupervisorSettingsValidatorTest {

  private static final String URL = "url";
  private static final String VERBOSE = "verbose";
  private static final String MODE = "mode";
  private static final String TOKEN = "token";
  private static final String SETTINGS_URL = "settings.url";

  private static final List<ConfigField> SCHEMA =
      List.of(
          ConfigField.required(URL, Type.STRING, "where"),
          ConfigField.optional("retries", Type.NUMBER, 3, "how often"),
          ConfigField.optional(VERBOSE, Type.BOOLEAN, false, "chatty"),
          ConfigField.choice(MODE, false, "A", "how", "A", "B"),
          ConfigField.optional("tags", Type.LIST, null, "tags"),
          ConfigField.optional("headers", Type.MAP, null, "headers"),
          ConfigField.optional(TOKEN, Type.SECRET, null, "credential"));

  private static List<Issue> issues(final Map<String, Object> settings) {
    try {
      SupervisorSettingsValidator.check(settings, SCHEMA);
      return List.of();
    } catch (SettingsValidationException e) {
      return e.getIssues();
    }
  }

  /* default */
  @Nested
  @NoArgsConstructor
  class KeyTests {

    @Test
    void validSettingsPassWithCommonKeys() {
      assertThatCode(
              () ->
                  SupervisorSettingsValidator.check(
                      Map.of(
                          URL,
                          "http://x",
                          "retries",
                          2,
                          VERBOSE,
                          "true",
                          MODE,
                          "b",
                          "canAnswer",
                          true,
                          "maxRisk",
                          "MEDIUM",
                          "observeInternal",
                          false,
                          "allowedCommands",
                          List.of("PauseWorkflowCommand")),
                      SCHEMA))
          .doesNotThrowAnyException();
    }

    @Test
    void relaxedKeyNamesAreRefused() {
      assertThat(issues(Map.of(URL, "u", "allowed-commands", List.of())))
          .containsExactly(new Issue("settings.allowed-commands", "is not a setting of this type"));
    }

    @Test
    void unknownKeyIsReportedAsSettingsField() {
      assertThat(issues(Map.of(URL, "u", "bogus", 1)))
          .containsExactly(new Issue("settings.bogus", "is not a setting of this type"));
    }

    @Test
    void missingRequiredKeyAndNullValueAreReported() {
      assertThat(issues(Map.of())).containsExactly(new Issue(SETTINGS_URL, "is required"));
      final Map<String, Object> nulled = Collections.singletonMap(URL, null);
      assertThat(issues(nulled)).containsExactly(new Issue(SETTINGS_URL, "is required"));
    }

    @Test
    void secretValueIsNeverQuoted() {
      assertThatThrownBy(
              () ->
                  SupervisorSettingsValidator.check(
                      Map.of(URL, "u", TOKEN, List.of("hunter2")), SCHEMA))
          .isInstanceOf(SettingsValidationException.class)
          .hasMessageNotContaining("hunter2");
    }

    @Test
    void exceptionIsAnIllegalArgumentExceptionListingEveryIssue() {
      assertThatThrownBy(() -> SupervisorSettingsValidator.check(Map.of("a", 1, "b", 2), SCHEMA))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("settings.a")
          .hasMessageContaining("settings.b")
          .hasMessageContaining(SETTINGS_URL);
    }
  }

  /* default */
  @Nested
  @NoArgsConstructor
  class ValueTests {

    @Test
    void mistypedValuesAreReportedPerField() {
      final List<Issue> found =
          issues(
              Map.of(
                  URL,
                  5,
                  "retries",
                  "x",
                  VERBOSE,
                  "maybe",
                  MODE,
                  "C",
                  "tags",
                  "a",
                  "headers",
                  List.of(),
                  TOKEN,
                  true,
                  "maxRisk",
                  "EXTREME",
                  "allowedCommands",
                  List.of("NoSuchCommand")));

      assertThat(found)
          .extracting(Issue::field)
          .containsExactlyInAnyOrder(
              "settings.retries",
              "settings.verbose",
              "settings.mode",
              "settings.tags",
              "settings.headers",
              "settings.token",
              "settings.maxRisk",
              "settings.allowedCommands");
      // a number is accepted as text (a keep-alive of -1), so "url": 5 is not an error
    }

    @Test
    void textFieldsRefuseValuesThatAreNotTextOrNumbers() {
      assertThat(issues(Map.of(URL, List.of("a"))))
          .containsExactly(new Issue(SETTINGS_URL, "must be text"));
    }

    @Test
    void booleansAcceptSwitchValuesInAnyCaseAndRefuseOtherKinds() {
      assertThat(issues(Map.of(URL, "u", VERBOSE, true))).isEmpty();
      assertThat(issues(Map.of(URL, "u", VERBOSE, "FALSE"))).isEmpty();
      assertThat(issues(Map.of(URL, "u", VERBOSE, "false"))).isEmpty();
      assertThat(issues(Map.of(URL, "u", VERBOSE, 1)))
          .containsExactly(new Issue("settings.verbose", "must be true or false"));
    }

    @Test
    void secretsAcceptTextOrObjectsOnly() {
      assertThat(issues(Map.of(URL, "u", TOKEN, "hunter2"))).isEmpty();
      assertThat(issues(Map.of(URL, "u", TOKEN, Map.of("ref", "decrypted:key")))).isEmpty();
      assertThat(issues(Map.of(URL, "u", TOKEN, 5)))
          .containsExactly(new Issue("settings.token", "must be text"));
    }

    @Test
    void choicesRefuseValuesThatAreNotText() {
      assertThat(issues(Map.of(URL, "u", MODE, 3)))
          .containsExactly(new Issue("settings.mode", "must be one of [A, B]"));
    }

    @Test
    void choiceWithoutDeclaredOptionsAcceptsAnyText() {
      assertThatCode(
              () ->
                  SupervisorSettingsValidator.check(
                      Map.of("free", "anything"),
                      List.of(ConfigField.choice("free", false, null, "any"))))
          .doesNotThrowAnyException();
    }

    @Test
    void listOfChoicesRefusesItemsThatAreNotNamedOptions() {
      assertThat(issues(Map.of(URL, "u", "allowedCommands", List.of(5))))
          .extracting(Issue::field)
          .containsExactly("settings.allowedCommands");
    }
  }
}
