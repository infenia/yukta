// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class McpErrorFilterTest {

  // A logback appender, not CapturedOutput: PIT's minion owns stdout, so captured output is empty.
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(McpErrorFilter.class);

  @BeforeEach
  void attach() {
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void detach() {
    logger.detachAppender(logs);
  }

  private String logged() {
    return logs.list.stream()
        .map(e -> e.getFormattedMessage() + " " + e.getKeyValuePairs())
        .reduce("", (a, b) -> a + b + "\n");
  }

  private static final String VALID = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

  private final AtomicReference<String> seenBody = new AtomicReference<>();

  private MockServerWebExchange run(
      final MockServerHttpRequest request,
      final Function<ServerWebExchange, Mono<Void>> downstream) {
    final MockServerWebExchange exchange = MockServerWebExchange.from(request);
    new McpErrorFilter().filter(exchange, downstream::apply).block();
    return exchange;
  }

  /** A downstream handler that records the body it was given and answers 200. */
  private Mono<Void> reads(final ServerWebExchange exchange) {
    return DataBufferUtils.join(exchange.getRequest().getBody())
        .map(buffer -> {
          final byte[] bytes = new byte[buffer.readableByteCount()];
          buffer.read(bytes);
          DataBufferUtils.release(buffer);
          seenBody.set(new String(bytes, StandardCharsets.UTF_8));
          return bytes;
        })
        .defaultIfEmpty(new byte[0])
        .then();
  }

  private static Function<ServerWebExchange, Mono<Void>> answers(
      final HttpStatus status, final String body) {
    return exchange -> {
      exchange.getResponse().setStatusCode(status);
      return exchange
          .getResponse()
          .writeWith(
              Mono.just(
                  exchange
                      .getResponse()
                      .bufferFactory()
                      .wrap(body.getBytes(StandardCharsets.UTF_8))));
    };
  }

  private static MockServerHttpRequest post(final String body) {
    return MockServerHttpRequest.post("/mcp").body(body);
  }

  @Test
  void otherPathsGoStraightThrough() {
    final var exchange =
        run(MockServerHttpRequest.post("/api/x").body("not a message"), this::reads);

    assertThat(seenBody.get()).isEqualTo("not a message");
    assertThat(exchange.getResponse().getStatusCode()).isNull();
  }

  @Test
  void getIsNotReadButItsErrorsAreStillGuarded() {
    final var exchange =
        run(
            MockServerHttpRequest.get("/mcp").build(),
            answers(HttpStatus.INTERNAL_SERVER_ERROR, "{\"stackTrace\":[]}"));

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(exchange.getResponse().getBodyAsString().block()).contains("Invalid request");
  }

  @Test
  void messageObjectOrBatchReachesTransportWithItsBody() {
    run(post(VALID), this::reads);
    assertThat(seenBody.get()).isEqualTo(VALID);

    run(post("[" + VALID + "]"), this::reads);
    assertThat(seenBody.get()).isEqualTo("[" + VALID + "]");
  }

  @Test
  void emptyOrNonJsonBodyIsLeftToTransport() {
    run(post(""), this::reads);
    assertThat(seenBody.get()).isEmpty();

    run(post("{{ not json"), this::reads);
    assertThat(seenBody.get()).isEqualTo("{{ not json");
  }

  @Test
  void bodyThatIsNotMessageIsBadRequest() {
    for (final String body : new String[] {"{\"x\":1}", "{\"jsonrpc\":\"1.0\"}", "5"}) {
      seenBody.set(null);
      final var exchange = run(post(body), this::reads);

      assertThat(seenBody.get()).isNull();
      assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(exchange.getResponse().getBodyAsString().block()).contains("Invalid request");
    }
  }

  @Test
  void oversizedBodyIsRefusedWith413() {
    final var exchange = run(post("x".repeat((4 << 20) + 1)), this::reads);

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    assertThat(exchange.getResponse().getBodyAsString().block()).contains("Request too large");
  }

  @Test
  void errorBodyWithStackTraceIsReplaced() {
    final var exchange =
        run(post(VALID), answers(HttpStatus.INTERNAL_SERVER_ERROR, "{\"stackTrace\":[\"at X\"]}"));

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(exchange.getResponse().getBodyAsString().block())
        .contains("Invalid request")
        .doesNotContain("stackTrace");
  }

  @Test
  void errorBodyWithoutStackTraceAndSuccessAreLeftAlone() {
    final var failed = run(post(VALID), answers(HttpStatus.BAD_REQUEST, "{\"error\":\"nope\"}"));
    assertThat(failed.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(failed.getResponse().getBodyAsString().block()).isEqualTo("{\"error\":\"nope\"}");

    final var ok = run(post(VALID), answers(HttpStatus.OK, "{\"result\":{}}"));
    assertThat(ok.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(ok.getResponse().getBodyAsString().block()).isEqualTo("{\"result\":{}}");
  }

  @Test
  void responseWithoutStatusIsPassedThrough() {
    final var exchange =
        run(
            post(VALID),
            e ->
                e.getResponse()
                    .writeWith(Mono.just(e.getResponse().bufferFactory().wrap(new byte[] {'o'}))));

    assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo("o");
  }

  @Test
  void toolCallIsLoggedWithItsNameStatusAndDuration() {
    run(
        post(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"list_sessions\"}}"),
        answers(HttpStatus.OK, "{\"result\":{}}"));

    assertThat(logged()).contains("MCP tool call", "list_sessions", "status=\"200\"");
  }

  @Test
  void callWithoutStatusIsLoggedAsZero() {
    run(
        post(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"quiet_tool\"}}"),
        this::reads);

    assertThat(logged()).contains("quiet_tool", "status=\"0\"");
  }

  @Test
  void otherMessagesAndNonJsonBodiesAreNotLogged() {
    run(post(VALID), this::reads);
    run(post("not json"), this::reads);

    assertThat(logged()).doesNotContain("MCP tool call");
  }
}
