// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Tests for the helper that plugins share to ask for input. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class InputAskTest {

  @Test
  void ask_failsClearlyWithoutAGateway() {
    StepVerifier.create(InputAsk.ask(() -> null, InputFixtures.spec()))
        .verifyErrorSatisfies(
            e ->
                assertThat(e)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(InputAsk.NO_GATEWAY));
  }

  @Test
  void ask_findsTheGatewayAtUseTime() {
    final InputGateway[] holder = new InputGateway[1];
    final Mono<InputResponse> ask = InputAsk.ask(() -> holder[0], InputFixtures.spec());
    holder[0] = spec -> Mono.error(new InputExpiredException("r", "late"));
    StepVerifier.create(ask).verifyError(InputExpiredException.class);
  }

  @Test
  void isTimeout_onlyForExpiry() {
    assertThat(InputAsk.isTimeout(new InputExpiredException("r", "x"))).isTrue();
    assertThat(InputAsk.isTimeout(new InputCancelledException("r", "x"))).isFalse();
    assertThat(InputAsk.isTimeout(new IllegalStateException())).isFalse();
  }

  @Test
  void kindAndRisk_areCaseInsensitiveWithFallback() {
    assertThat(InputAsk.kind(null, InputKind.TEXT)).isEqualTo(InputKind.TEXT);
    assertThat(InputAsk.kind(" choice ", InputKind.TEXT)).isEqualTo(InputKind.CHOICE);
    assertThat(InputAsk.risk("high", InputRisk.LOW)).isEqualTo(InputRisk.HIGH);
    assertThatThrownBy(() -> InputAsk.risk("severe", InputRisk.LOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("risk must be one of");
  }

  @Test
  void options_acceptIdsAndLabelledMaps() {
    assertThat(InputAsk.options(null)).isEmpty();
    assertThat(InputAsk.options(List.of("a", Map.of("id", "b", "label", "Bee"))))
        .containsExactly(new InputOption("a"), new InputOption("b", "Bee"));
    assertThatThrownBy(() -> InputAsk.options("a")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> InputAsk.options(List.of(5)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> InputAsk.options(List.of(Map.of("label", "x"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void audience_defaultsToTheCurrentTierAndChecksSelectors() {
    assertThat(InputAsk.audience(null)).isEqualTo(Audience.CURRENT_TIER);
    assertThat(InputAsk.audience(List.of("role:ADMIN")).selectors()).containsExactly("role:ADMIN");
    assertThatThrownBy(() -> InputAsk.audience("role:ADMIN"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> InputAsk.audience(List.of("everyone")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void clean_dropsControlCharactersAndCutsOnACharacterBoundary() {
    final String bidi = new String(Character.toChars(0x202E));
    assertThat(InputAsk.clean(null, 10)).isEmpty();
    assertThat(InputAsk.clean("a\u0000b" + bidi + "c\td\ne", 100)).isEqualTo("abc\td\ne");
    assertThat(InputAsk.clean("  padded  ", 100)).isEqualTo("padded");
    assertThat(InputAsk.clean("abcdef", 3)).isEqualTo("abc");
    // each é is two bytes: three bytes hold one of them
    assertThat(InputAsk.clean("ééé", 3)).isEqualTo("é");
    // an emoji (four bytes) is kept whole or dropped, never split
    assertThat(InputAsk.clean("a😀", 4)).isEqualTo("a");
    assertThat(InputAsk.clean("a😀", 5)).isEqualTo("a😀");
  }

  @Test
  void signalContextKeyIsTheOneTheBrokerUses() {
    assertThat(InputAsk.SIGNAL_CONTEXT_KEY).isEqualTo("signal");
  }
}
