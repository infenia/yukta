// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ControlActor;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** A named key on the MCP endpoint is known as a key. */
@NoArgsConstructor
class McpEndpointFilterActorKindTest {

  @Test
  void namedKeyIsKeyKind() {
    final MockEnvironment environment =
        new MockEnvironment()
            .withProperty("yukta.api-keys[0].name", "bot")
            .withProperty("yukta.api-keys[0].key", "k")
            .withProperty("yukta.api-keys[0].role", "ADMIN");
    final AtomicReference<Object> kind = new AtomicReference<>();

    new McpEndpointFilter(environment)
        .filter(
            MockServerWebExchange.from(MockServerHttpRequest.post("/sse").header("X-API-Key", "k")),
            (ServerWebExchange exchange) ->
                Mono.deferContextual(
                    ctx -> {
                      kind.set(ctx.getOrDefault(ControlActor.KIND_KEY, null));
                      return Mono.empty();
                    }))
        .block();

    assertThat(kind.get()).isEqualTo(ControlActor.Kind.KEY);
  }
}
