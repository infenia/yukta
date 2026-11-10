// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.service.supervisor.agent.AgentCredentials;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Only the credential of a supervisor opens the agent API, and it opens nothing else. */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidUsingHardCodedIP",
  "PMD.TooManyStaticImports"
})
class AgentCredentialFilterTest {

  private final AgentCredentials credentials = new AgentCredentials();
  private final AgentCredentialFilter filter = new AgentCredentialFilter(credentials);
  private final WebFilterChain chain = mock(WebFilterChain.class);

  private MockServerWebExchange get(final String path, final String authorization) {
    final MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get(path);
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    return MockServerWebExchange.from(request);
  }

  @Test
  void validCredentialPassesAndNamesTheSupervisor() {
    when(chain.filter(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
    final String token = credentials.issue("a1", null).token();
    final MockServerWebExchange exchange = get("/api/agent/v1/self", "Bearer " + token);

    filter.filter(exchange, chain).block();

    verify(chain).filter(exchange);
    assertThat((String) exchange.getAttribute(AgentCredentialFilter.SUPERVISOR_ATTRIBUTE))
        .isEqualTo("a1");
    assertThat(exchange.getResponse().getHeaders().getFirst("Yukta-Supervisor-Contract"))
        .isEqualTo("1");
  }

  @Test
  void noCredentialHumanKeyOrRevokedOneIsRefused() {
    final String token = credentials.issue("a1", null).token();
    credentials.revoke("a1");
    for (final String authorization :
        new String[] {null, "Bearer some-human-key", "Basic abc", "Bearer " + token}) {
      final MockServerWebExchange exchange = get("/api/agent/v1/events", authorization);
      filter.filter(exchange, chain).block();
      assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    verify(chain, never()).filter(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void otherPathsAreLeftToTheKeyFilterAndAnAmbiguousAgentPathIsRefused() {
    when(chain.filter(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
    final MockServerWebExchange other = get("/api/executions", null);
    filter.filter(other, chain).block();
    verify(chain).filter(other);

    final MockServerWebExchange sneaky = get("/api/agent/v1/%2e%2e/self", null);
    filter.filter(sneaky, chain).block();
    assertThat(sneaky.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    final MockServerWebExchange elsewhere = get("/api/x/%2e%2e/y", null);
    filter.filter(elsewhere, chain).block();
    verify(chain).filter(elsewhere);
  }

  @Test
  void theAgentPathIsExactlyTheVersionedPrefix() {
    assertThat(AgentCredentialFilter.isAgentPath("/api/agent/v1")).isTrue();
    assertThat(AgentCredentialFilter.isAgentPath("/api/agent/v1/self")).isTrue();
    assertThat(AgentCredentialFilter.isAgentPath("/api/agent/v10")).isFalse();
    assertThat(AgentCredentialFilter.isAgentPath("/api/agents")).isFalse();
  }

  @Test
  void credentialTiedToAddressesIsRefusedFromAnotherOne() {
    final String token = credentials.issue("a1", null, java.util.Set.of("10.0.0.5")).token();
    // the mock exchange has no remote address, which is never an allowed one
    final MockServerWebExchange exchange = get("/api/agent/v1/self", "Bearer " + token);
    filter.filter(exchange, chain).block();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void credentialTiedToAddressesPassesFromThatAddress() {
    when(chain.filter(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
    final String token = credentials.issue("a1", null, java.util.Set.of("10.0.0.5")).token();
    final MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/agent/v1/self")
                .header("Authorization", "Bearer " + token)
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.5", 4000)));
    filter.filter(exchange, chain).block();
    assertThat(exchange.getResponse().getStatusCode()).isNull();
  }

  @Test
  void anAddressThatKeepsGuessingIsStopped() {
    System.setProperty("yukta.agent.failures-per-minute", "3");
    try {
      final AgentCredentialFilter strict = new AgentCredentialFilter(credentials);
      HttpStatus last = null;
      for (int i = 0; i < 6; i++) {
        final MockServerWebExchange exchange = get("/api/agent/v1/self", "Bearer wrong");
        strict.filter(exchange, chain).block();
        last = (HttpStatus) exchange.getResponse().getStatusCode();
      }
      assertThat(last).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    } finally {
      System.clearProperty("yukta.agent.failures-per-minute");
    }
  }

  @Test
  void credentialIsHeldToItsRequestRate() {
    when(chain.filter(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
    System.setProperty("yukta.agent.requests-per-minute", "2");
    try {
      final AgentCredentialFilter strict = new AgentCredentialFilter(credentials);
      final String token = credentials.issue("a1", null).token();
      HttpStatus last = null;
      for (int i = 0; i < 4; i++) {
        final MockServerWebExchange exchange = get("/api/agent/v1/self", "Bearer " + token);
        strict.filter(exchange, chain).block();
        last = (HttpStatus) exchange.getResponse().getStatusCode();
      }
      assertThat(last).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    } finally {
      System.clearProperty("yukta.agent.requests-per-minute");
    }
  }

  @Test
  void bodyOverTheLimitIsRefused() {
    final String token = credentials.issue("a1", null).token();
    final MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/agent/v1/directives")
                .header("Authorization", "Bearer " + token)
                .header("Content-Length", "1000000")
                .build());
    filter.filter(exchange, chain).block();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
  }
}
