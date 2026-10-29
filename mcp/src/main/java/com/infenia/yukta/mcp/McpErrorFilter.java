// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import java.nio.charset.StandardCharsets;
import org.reactivestreams.Publisher;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes the MCP endpoint answer a request it cannot handle as a client error without internals: the
 * SDK fails a body that is not a valid JSON-RPC message, an unknown method or an oversized body
 * with a 500, or returns the serialized {@code McpError} with its stack trace. This filter turns a
 * failure of the transport into a JSON-RPC error with 400 (413 for an oversized body), and replaces
 * an error body that holds a stack trace. Only error responses are buffered; a stream is passed
 * through.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class McpErrorFilter implements WebFilter {

  /** Path of the MCP endpoint, after {@link McpEndpointFilter} rewrote the legacy name. */
  private static final String ENDPOINT = "/mcp";

  /** Marks a serialized Java exception. */
  private static final String STACK_TRACE = "\"stackTrace\"";

  /** The JSON-RPC error a client gets for a request that is not a valid message. */
  private static final String INVALID_REQUEST =
      "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,"
          + "\"message\":\"Invalid request\"}}";

  /** The JSON-RPC error for a body over the size limit. */
  private static final String TOO_LARGE =
      "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,"
          + "\"message\":\"Request too large\"}}";

  /** Largest body read to check its shape; a larger one is refused with 413. */
  private static final int MAX_BODY_BYTES = 4 << 20;

  /** Reads the body to check that it is a JSON-RPC message. */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
    if (!ENDPOINT.equals(exchange.getRequest().getPath().value())) {
      return chain.filter(exchange);
    }
    final ServerHttpResponse original = exchange.getResponse();
    final ServerHttpResponse guarded =
        new ServerHttpResponseDecorator(original) {
          @Override
          public Mono<Void> writeWith(final Publisher<? extends DataBuffer> body) {
            final HttpStatusCode status = getStatusCode();
            if (status == null || !status.isError()) {
              return super.writeWith(body);
            }
            return DataBufferUtils.join(body)
                .flatMap(
                    joined -> {
                      final byte[] bytes = new byte[joined.readableByteCount()];
                      joined.read(bytes);
                      DataBufferUtils.release(joined);
                      final String text = new String(bytes, StandardCharsets.UTF_8);
                      return text.contains(STACK_TRACE)
                          ? replace(this, HttpStatus.BAD_REQUEST, INVALID_REQUEST)
                          : super.writeWith(Mono.just(bufferFactory().wrap(bytes)));
                    });
          }
        };
    final ServerWebExchange next = exchange.mutate().response(guarded).build();
    if (!HttpMethod.POST.equals(exchange.getRequest().getMethod())) {
      return chain.filter(next);
    }
    return DataBufferUtils.join(exchange.getRequest().getBody(), MAX_BODY_BYTES)
        .map(
            buffer -> {
              final byte[] bytes = new byte[buffer.readableByteCount()];
              buffer.read(bytes);
              DataBufferUtils.release(buffer);
              return bytes;
            })
        .defaultIfEmpty(new byte[0])
        .flatMap(
            bytes ->
                isMessage(bytes)
                    ? chain.filter(withBody(next, bytes))
                    : replace(guarded, HttpStatus.BAD_REQUEST, INVALID_REQUEST))
        .onErrorResume(
            DataBufferLimitException.class,
            _ -> replace(guarded, HttpStatus.CONTENT_TOO_LARGE, TOO_LARGE));
  }

  /** Whether the body is a JSON-RPC message (an object with {@code jsonrpc: "2.0"}) or a batch. */
  private static boolean isMessage(final byte[] bytes) {
    if (bytes.length == 0) {
      return true; // nothing to judge; the transport answers an empty body itself
    }
    try {
      final JsonNode root = JSON.readTree(bytes);
      return root.isArray() || root.isObject() && "2.0".equals(root.path("jsonrpc").asString(""));
    } catch (JacksonException e) {
      return true; // not JSON: the SDK answers that with its own error, cleaned below
    }
  }

  private static ServerWebExchange withBody(final ServerWebExchange exchange, final byte[] bytes) {
    final ServerHttpRequest request =
        new ServerHttpRequestDecorator(exchange.getRequest()) {
          @Override
          public Flux<DataBuffer> getBody() {
            return Flux.just(exchange.getResponse().bufferFactory().wrap(bytes));
          }
        };
    return exchange.mutate().request(request).build();
  }

  private static Mono<Void> replace(
      final ServerHttpResponse response, final HttpStatus status, final String json) {
    final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    response.getHeaders().setContentLength(bytes.length);
    return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
  }
}
