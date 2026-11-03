// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.AnswerInputCommand;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputOption;
import com.infenia.yukta.plugin.input.InputRejectedException;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.TimeoutAction;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/**
 * Throws random, oversized, mistyped and deeply nested input at the validation of an input request
 * spec, of an answer command and of the response schema check. Whatever the input: nothing but an
 * {@link IllegalArgumentException} or an {@link InputRejectedException} comes out (never a stack
 * overflow or a parser error), and each case ends within a time limit (requirement R7 of
 * ASSURANCE.md: input is bounded in size, time and shape).
 *
 * <p>Cases come from a seeded {@link Random}; a failure prints the seed and the case number. Replay
 * with {@code -Dyukta.fuzz.seed=<seed>}. The nightly run raises the count and picks a random seed
 * the same way as {@code BoundaryFuzzAcceptanceTest}: {@code -PfuzzTries=...} and {@code
 * -PfuzzSeed=random}.
 */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidCatchingGenericException",
  "PMD.UseConcurrentHashMap",
  "PMD.AvoidInstantiatingObjectsInLoops",
  "PMD.UnitTestShouldIncludeAssert",
  "PMD.InsufficientStringBufferDeclaration"
})
class InputRequestFuzzTest {

  private static final int TRIES = Integer.getInteger("yukta.fuzz.tries", 300);

  private static final long SEED = chosenSeed();

  private static final Duration LIMIT = Duration.ofSeconds(5);

  private final Random random = new Random(SEED);

  private static long chosenSeed() {
    final String configured = System.getProperty("yukta.fuzz.seed", "20261103");
    return "random".equals(configured) ? System.nanoTime() : Long.parseLong(configured);
  }

  private String text() {
    final int length =
        switch (random.nextInt(6)) {
          case 0 -> 0;
          case 1 -> 1 + random.nextInt(8);
          case 2 -> random.nextInt(300);
          case 3 -> 60_000 + random.nextInt(60_000);
          default -> random.nextInt(40);
        };
    final StringBuilder out = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      out.appendCodePoint(
          switch (random.nextInt(5)) {
            case 0 -> random.nextInt(32); // control characters, NUL
            case 1 -> 0x80 + random.nextInt(0x700);
            case 2 -> 0x1F300 + random.nextInt(0x300); // emoji
            default -> 'a' + random.nextInt(26);
          });
    }
    return out.toString();
  }

  private String maybeText() {
    return random.nextInt(4) == 0 ? null : text();
  }

  /** A JSON-like value; {@code depth} may reach a few thousand, which parsers must refuse. */
  private Object value(final int depth) {
    final int pick = depth <= 0 ? random.nextInt(5) : random.nextInt(7);
    return switch (pick) {
      case 0 -> text();
      case 1 -> random.nextInt();
      case 2 -> random.nextDouble();
      case 3 -> random.nextBoolean();
      case 4 -> null;
      case 5 -> {
        final List<Object> list = new ArrayList<>();
        for (int i = random.nextInt(4); i > 0; i--) {
          list.add(value(depth - 1));
        }
        yield list;
      }
      default -> map(depth - 1);
    };
  }

  private Map<String, Object> map(final int depth) {
    final Map<String, Object> map = new LinkedHashMap<>();
    for (int i = random.nextInt(4); i > 0; i--) {
      map.put(random.nextBoolean() ? text() : "k" + i, value(depth));
    }
    return map;
  }

  private static Map<String, Object> nested(final int depth) {
    Map<String, Object> inner = new LinkedHashMap<>();
    inner.put("x", 1);
    for (int i = 0; i < depth; i++) {
      final Map<String, Object> outer = new LinkedHashMap<>();
      outer.put("n", inner);
      inner = outer;
    }
    return inner;
  }

  private static String nestedSchema(final int depth) {
    final StringBuilder out = new StringBuilder();
    for (int i = 0; i < depth; i++) {
      out.append("{\"type\":\"object\",\"properties\":{\"n\":");
    }
    out.append("{\"type\":\"integer\"}");
    for (int i = 0; i < depth; i++) {
      out.append("}}");
    }
    return out.toString();
  }

  /** Runs one case: only the allowed exceptions, within the limit. */
  private void attempt(final String what, final int number, final Runnable action) {
    final long started = System.nanoTime();
    try {
      action.run();
    } catch (final IllegalArgumentException | InputRejectedException expected) {
      // a clean refusal
    } catch (final RuntimeException | StackOverflowError unexpected) {
      throw new AssertionError(
          what + ": case " + number + " (seed " + SEED + ") ended with " + unexpected, unexpected);
    }
    assertThat(Duration.ofNanos(System.nanoTime() - started))
        .as("%s: case %d (seed %d)", what, number, SEED)
        .isLessThan(LIMIT);
  }

  private InputRequestSpec spec(final InputKind kind, final String schema) {
    return new InputRequestSpec(
        "key",
        kind,
        "Deploy?",
        Map.of(),
        kind == InputKind.CHOICE ? List.of(new InputOption("a"), new InputOption("b")) : List.of(),
        schema,
        InputRisk.LOW,
        Audience.CURRENT_TIER,
        Duration.ofMinutes(10),
        TimeoutAction.FAIL,
        null,
        null,
        "FUZZ");
  }

  private static InputRequest request(final InputRequestSpec spec) {
    final Instant now = Instant.parse("2026-11-02T10:00:00Z");
    return new InputRequest(
        "r1",
        "key",
        "s",
        "w",
        "e",
        "n",
        spec,
        InputState.OPEN,
        0,
        "default",
        List.of(),
        null,
        null,
        now,
        now);
  }

  @Test
  void specsWithRandomPartsAreRefusedCleanly() {
    final InputKind[] kinds = InputKind.values();
    for (int i = 0; i < TRIES; i++) {
      attempt(
          "spec",
          i,
          () ->
              new InputRequestSpec(
                  maybeText(),
                  kinds[random.nextInt(kinds.length)],
                  maybeText(),
                  map(random.nextInt(6)),
                  List.of(new InputOption(text(), maybeText())),
                  maybeText(),
                  InputRisk.values()[random.nextInt(InputRisk.values().length)],
                  Audience.CURRENT_TIER,
                  Duration.ofSeconds(random.nextInt(200_000) - 100),
                  TimeoutAction.FAIL,
                  maybeText(),
                  maybeText(),
                  maybeText()));
    }
  }

  @Test
  void answerCommandsWithRandomPartsAreRefusedCleanly() {
    for (int i = 0; i < TRIES; i++) {
      attempt(
          "answer command",
          i,
          () ->
              new AnswerInputCommand(
                  maybeText(),
                  maybeText(),
                  random.nextBoolean() ? null : random.nextLong(),
                  maybeText(),
                  maybeText(),
                  map(random.nextInt(8)),
                  maybeText(),
                  random.nextBoolean() ? null : random.nextDouble() * 3 - 1));
    }
  }

  @Test
  void answerDataIsCheckedAgainstRandomSchemasWithoutSurprises() {
    for (int i = 0; i < TRIES; i++) {
      final String schema =
          random.nextBoolean() ? nestedSchema(random.nextInt(50)) : "{\"type\":\"object\"}";
      attempt(
          "schema check",
          i,
          () ->
              AnswerShape.check(
                  request(spec(InputKind.STRUCTURED, schema)),
                  new AnswerInputCommand(
                      "e", "r1", null, "ok", null, map(random.nextInt(6)), null, null)));
    }
  }

  @Test
  void schemaBombsAndDeepAnswersAreRefusedOrCheckedWithinTheLimit() {
    for (final int depth : new int[] {10, 100, 500, 1500, 5000, 20_000}) {
      attempt("deep schema " + depth, depth, () -> spec(InputKind.STRUCTURED, nestedSchema(depth)));
      attempt(
          "deep answer " + depth,
          depth,
          () ->
              AnswerShape.check(
                  request(spec(InputKind.STRUCTURED, nestedSchema(20))),
                  new AnswerInputCommand("e", "r1", null, "ok", null, nested(depth), null, null)));
    }
  }
}
