// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ControlActor;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/** A named key is known as a key, so a supervisor with the same name cannot pass for it. */
@NoArgsConstructor
class ApiKeyFilterActorKindTest {

  private final AtomicReference<Object> kind = new AtomicReference<>();

  private void run(final MockEnvironment environment, final String key) {
    kind.set(null);
    new ApiKeyFilter(environment)
        .filter(
            MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/sessions").header("X-API-Key", key)),
            _ ->
                Mono.deferContextual(
                    ctx -> {
                      kind.set(ctx.getOrDefault(ControlActor.KIND_KEY, null));
                      return Mono.empty();
                    }))
        .block();
  }

  @Test
  void namedKeyIsKeyKind() {
    run(
        new MockEnvironment()
            .withProperty("yukta.api-keys[0].name", "ops")
            .withProperty("yukta.api-keys[0].key", "o-key")
            .withProperty("yukta.api-keys[0].role", "OPERATOR"),
        "o-key");

    assertThat(kind.get()).isEqualTo(ControlActor.Kind.KEY);
  }

  @Test
  void theSingleKeyNamesNoActorAndNoKind() {
    run(new MockEnvironment().withProperty("yukta.api-key", "secret"), "secret");

    assertThat(kind.get()).isNull();
  }
}
