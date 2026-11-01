// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import static com.infenia.yukta.plugin.input.InputFixtures.NOW;
import static com.infenia.yukta.plugin.input.InputFixtures.request;
import static com.infenia.yukta.plugin.input.InputFixtures.spec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.TooManyStaticImports")
class InputRequestTest {

  private static InputRequest with(final String id, final long version, final String advice) {
    return new InputRequest(
        id,
        "k",
        "s",
        "w",
        "e",
        "n",
        spec(),
        InputState.OPEN,
        version,
        "t",
        null,
        advice,
        null,
        NOW,
        NOW);
  }

  @Test
  void validRequestKeepsItsParts() {
    final InputRequest request = request();
    assertThat(request.id()).isEqualTo("r1");
    assertThat(request.targets()).containsExactly("supervisor:*", "role:OPERATOR");
    assertThat(request.state()).isEqualTo(InputState.OPEN);
  }

  @Test
  void idsAndVersionAreChecked() {
    assertThatThrownBy(() -> with(" ", 0, null)).hasMessage("id must not be blank");
    assertThatThrownBy(() -> with("r", -1, null)).hasMessage("version must not be negative");
    assertThatThrownBy(() -> with("r", 0, "x".repeat(9000)))
        .hasMessage("advice is longer than 8192 bytes");
  }

  @Test
  void missingTargetsMeanNone() {
    assertThat(with("r", 0, null).targets()).isEmpty();
    assertThat(with("r", 0, null).deadline()).isNull();
  }

  @Test
  void targetsAreCopied() {
    final List<String> targets = new ArrayList<>(List.of("role:ADMIN"));
    final InputRequest request =
        new InputRequest(
            "r",
            "k",
            "s",
            "w",
            "e",
            "n",
            spec(),
            InputState.OPEN,
            0,
            "t",
            targets,
            null,
            null,
            NOW,
            NOW);
    targets.add("role:OPERATOR");
    assertThat(request.targets()).containsExactly("role:ADMIN");
  }

  @Test
  void everyRequiredPartIsChecked() {
    final InputRequest ok = request();
    assertThatThrownBy(
            () ->
                new InputRequest(
                    "r",
                    "k",
                    "s",
                    "w",
                    "e",
                    "n",
                    null,
                    InputState.OPEN,
                    0,
                    "t",
                    null,
                    null,
                    null,
                    NOW,
                    NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new InputRequest(
                    "r", "k", "s", "w", "e", "n", ok.spec(), null, 0, "t", null, null, null, NOW,
                    NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new InputRequest(
                    "r",
                    "k",
                    "s",
                    "w",
                    "e",
                    "n",
                    ok.spec(),
                    InputState.OPEN,
                    0,
                    "t",
                    null,
                    null,
                    null,
                    null,
                    NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new InputRequest(
                    "r",
                    "k",
                    "s",
                    "w",
                    "e",
                    "n",
                    ok.spec(),
                    InputState.OPEN,
                    0,
                    "t",
                    null,
                    null,
                    null,
                    NOW,
                    null))
        .isInstanceOf(NullPointerException.class);
    for (final String blank :
        new String[] {"key", "session", "workflow", "execution", "node", "tier"}) {
      final InputRequest bad =
          switch (blank) {
            case "key" -> build(" ", "s", "w", "e", "n", "t");
            case "session" -> build("k", " ", "w", "e", "n", "t");
            case "workflow" -> build("k", "s", " ", "e", "n", "t");
            case "execution" -> build("k", "s", "w", " ", "n", "t");
            case "node" -> build("k", "s", "w", "e", " ", "t");
            default -> build("k", "s", "w", "e", "n", " ");
          };
      assertThat(bad).isNull();
    }
  }

  private static InputRequest build(
      final String key,
      final String session,
      final String workflow,
      final String execution,
      final String node,
      final String tier) {
    try {
      return new InputRequest(
          "r",
          key,
          session,
          workflow,
          execution,
          node,
          spec(),
          InputState.OPEN,
          0,
          tier,
          null,
          null,
          null,
          NOW,
          NOW);
    } catch (IllegalArgumentException expected) {
      return null;
    }
  }
}
