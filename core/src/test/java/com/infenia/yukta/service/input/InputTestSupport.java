// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputOption;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.TimeoutAction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.experimental.UtilityClass;

/** Builders shared by the input broker tests. */
@UtilityClass
public class InputTestSupport {

  /** A run and node. */
  public static InputIdentity who(final String execution, final String node) {
    return new InputIdentity("session", "workflow", execution, node);
  }

  /** A text question with a ten minute ttl that fails at its deadline. */
  public static InputRequestSpec spec(final String key) {
    return spec(key, Duration.ofMinutes(10), TimeoutAction.FAIL, null);
  }

  /** An approval, so a default decision needs no option. */
  public static InputRequestSpec spec(
      final String key, final Duration ttl, final TimeoutAction onTimeout, final String dflt) {
    return new InputRequestSpec(
        key,
        onTimeout == TimeoutAction.USE_DEFAULT ? InputKind.APPROVAL : InputKind.TEXT,
        "Which way?",
        Map.of(),
        List.of(),
        null,
        InputRisk.LOW,
        Audience.CURRENT_TIER,
        ttl,
        onTimeout,
        dflt,
        null,
        "TEST");
  }

  /** A question of any kind, risk and audience. */
  public static InputRequestSpec spec(
      final String key,
      final InputKind kind,
      final InputRisk risk,
      final Audience audience,
      final List<InputOption> options,
      final String schema,
      final Map<String, Object> context) {
    return new InputRequestSpec(
        key,
        kind,
        "Which way?",
        context,
        options,
        schema,
        risk,
        audience,
        Duration.ofMinutes(10),
        TimeoutAction.FAIL,
        null,
        null,
        "TEST");
  }

  /** An open request of the run {@code exec} in session {@code session}, on the default tier. */
  public static InputRequest request(final String id, final InputRequestSpec spec) {
    final Instant now = Instant.parse("2026-11-02T10:00:00Z");
    return new InputRequest(
        id,
        spec.key(),
        "session",
        "workflow",
        "exec",
        "node",
        spec,
        InputState.OPEN,
        0,
        InputBroker.DEFAULT_TIER,
        List.of(),
        null,
        now.plus(spec.ttl()),
        now,
        now);
  }

  /** The default limits. */
  public static YuktaProperties.Input limits() {
    return new YuktaProperties.Input(
        Duration.ofHours(1), Duration.ofHours(24), 5, 50, 100, Duration.ofSeconds(1), List.of());
  }

  /** A clock a test moves by hand. */
  public static final class MutableClock extends Clock {

    /** The current time. */
    private final AtomicReference<Instant> now =
        new AtomicReference<>(Instant.parse("2026-11-02T10:00:00Z"));

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }

    /** Moves the clock forward. */
    public void advance(final Duration by) {
      now.updateAndGet(current -> current.plus(by));
    }
  }
}
