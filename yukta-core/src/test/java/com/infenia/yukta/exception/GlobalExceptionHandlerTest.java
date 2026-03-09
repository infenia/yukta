/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.exception;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import reactor.test.StepVerifier;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void testHandleWebExchangeBindException() {
    BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "obj");
    result.addError(new FieldError("obj", "field", "rejected", false, null, null, "message"));
    WebExchangeBindException ex = new WebExchangeBindException(null, result);

    ServerWebExchange exchange = mock(ServerWebExchange.class);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(exchange.getRequest()).thenReturn(request);
    when(request.getPath()).thenReturn(org.springframework.http.server.RequestPath.parse("/", "/"));

    StepVerifier.create(
            handler.handleWebExchangeBindException(
                ex, new HttpHeaders(), HttpStatus.BAD_REQUEST, exchange))
        .expectNextMatches(resp -> resp.getStatusCode() == HttpStatus.BAD_REQUEST)
        .verifyComplete();
  }

  @Test
  void testCreateResponseEntity() {
    ServerWebExchange exchange = mock(ServerWebExchange.class);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(exchange.getRequest()).thenReturn(request);
    when(request.getPath()).thenReturn(org.springframework.http.server.RequestPath.parse("/", "/"));

    // Case body not ApiResponse
    StepVerifier.create(
            handler.createResponseEntity(
                "error msg", new HttpHeaders(), HttpStatusCode.valueOf(400), exchange))
        .expectNextMatches(resp -> resp.getStatusCode().value() == 400)
        .verifyComplete();

    // Case body not ApiResponse and not String
    StepVerifier.create(
            handler.createResponseEntity(
                null, new HttpHeaders(), HttpStatusCode.valueOf(500), exchange))
        .expectNextMatches(resp -> resp.getStatusCode().value() == 500)
        .verifyComplete();
  }

  @Test
  void testHandleConstraintViolation() {
    jakarta.validation.ConstraintViolation<?> violation =
        mock(jakarta.validation.ConstraintViolation.class);
    when(violation.getPropertyPath()).thenReturn(mock(jakarta.validation.Path.class));
    when(violation.getMessage()).thenReturn("violation");

    ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(org.springframework.http.server.RequestPath.parse("/", "/"));

    ResponseEntity<ApiResponse<Object>> resp = handler.handleConstraintViolation(ex, request);
    assert resp.getStatusCode() == HttpStatus.BAD_REQUEST;
  }

  @Test
  void testHandleIllegalArgument() {
    IllegalArgumentException ex = new IllegalArgumentException("invalid arg");
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(org.springframework.http.server.RequestPath.parse("/", "/"));

    ResponseEntity<ApiResponse<Object>> resp = handler.handleIllegalArgument(ex, request);
    assert resp.getStatusCode() == HttpStatus.BAD_REQUEST;
  }

  @Test
  void testHandleIllegalState() {
    IllegalStateException ex = new IllegalStateException("bad state");
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(org.springframework.http.server.RequestPath.parse("/", "/"));

    ResponseEntity<ApiResponse<Object>> resp = handler.handleIllegalState(ex, request);
    assert resp.getStatusCode() == HttpStatus.INTERNAL_SERVER_ERROR;
  }

  @Test
  void testHandleGenericException() {
    Exception ex = new Exception("fatal");
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(org.springframework.http.server.RequestPath.parse("/", "/"));

    ResponseEntity<ApiResponse<Object>> resp = handler.handleGenericException(ex, request);
    assert resp.getStatusCode() == HttpStatus.INTERNAL_SERVER_ERROR;
  }
}
