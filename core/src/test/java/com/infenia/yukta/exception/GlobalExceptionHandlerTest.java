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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.api.ApiResponse;
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
  @SuppressWarnings("unchecked")
  void testHandleWebExchangeBindException() {
    BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "obj");
    result.addError(new FieldError("obj", "field", "rejected", false, null, null, "message"));
    WebExchangeBindException ex = new WebExchangeBindException(null, result);

    ServerWebExchange exchange = mock(ServerWebExchange.class);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(exchange.getRequest()).thenReturn(request);
    when(request.getPath())
        .thenReturn(org.springframework.http.server.RequestPath.parse("/api", "/"));

    StepVerifier.create(
            handler.handleWebExchangeBindException(
                ex, new HttpHeaders(), HttpStatus.BAD_REQUEST, exchange))
        .expectNextMatches(
            resp -> {
              ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
              return resp.getStatusCode() == HttpStatus.BAD_REQUEST
                  && body.status() == 400
                  && "Validation failed".equals(body.message())
                  && "/api".equals(body.path())
                  && body.errors().size() == 1
                  && "field".equals(body.errors().get(0).field());
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testCreateResponseEntity() {
    ServerWebExchange exchange = mock(ServerWebExchange.class);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(exchange.getRequest()).thenReturn(request);
    when(request.getPath())
        .thenReturn(org.springframework.http.server.RequestPath.parse("/create", "/"));

    // Case body not ApiResponse
    StepVerifier.create(
            handler.createResponseEntity(
                "error msg", new HttpHeaders(), HttpStatusCode.valueOf(404), exchange))
        .expectNextMatches(
            resp -> {
              ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
              return resp.getStatusCode().value() == 404
                  && body.status() == 404
                  && "error msg".equals(body.message())
                  && "/create".equals(body.path());
            })
        .verifyComplete();

    // Case body not ApiResponse and not String
    StepVerifier.create(
            handler.createResponseEntity(
                null, new HttpHeaders(), HttpStatusCode.valueOf(500), exchange))
        .expectNextMatches(
            resp -> {
              ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
              return resp.getStatusCode().value() == 500
                  && body.status() == 500
                  && "500 INTERNAL_SERVER_ERROR".equals(body.message());
            })
        .verifyComplete();

    // Case body IS ApiResponse
    ApiResponse<Object> existing = ApiResponse.success(200, "OK", "data");
    StepVerifier.create(
            handler.createResponseEntity(
                existing, new HttpHeaders(), HttpStatusCode.valueOf(200), exchange))
        .expectNextMatches(
            resp -> {
              ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
              return resp.getStatusCode().value() == 200 && body.equals(existing);
            })
        .verifyComplete();
  }

  @Test
  void testHandleConstraintViolation() {
    jakarta.validation.ConstraintViolation<?> violation =
        mock(jakarta.validation.ConstraintViolation.class);
    jakarta.validation.Path path = mock(jakarta.validation.Path.class);
    when(path.toString()).thenReturn("property");
    when(violation.getPropertyPath()).thenReturn(path);
    when(violation.getMessage()).thenReturn("violation message");

    ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath())
        .thenReturn(org.springframework.http.server.RequestPath.parse("/api", "/"));

    ResponseEntity<ApiResponse<Object>> resp = handler.handleConstraintViolation(ex, request);
    assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    ApiResponse<Object> body = resp.getBody();
    assert body != null;
    assertEquals(400, body.status());
    assertEquals("Constraint violation", body.message());
    assertEquals("/api", body.path());
    assertEquals(1, body.errors().size());
    assertEquals("property", body.errors().get(0).field());
    assertEquals("violation message", body.errors().get(0).message());
  }

  @Test
  void testHandleIllegalArgument() {
    IllegalArgumentException ex = new IllegalArgumentException("invalid arg");
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath())
        .thenReturn(org.springframework.http.server.RequestPath.parse("/arg", "/"));

    ResponseEntity<ApiResponse<Object>> resp = handler.handleIllegalArgument(ex, request);
    assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    ApiResponse<Object> body = resp.getBody();
    assert body != null;
    assertEquals("invalid arg", body.message());
    assertEquals("/arg", body.path());
  }

  @Test
  void testHandleIllegalState() {
    IllegalStateException ex = new IllegalStateException("bad state");
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath())
        .thenReturn(org.springframework.http.server.RequestPath.parse("/state", "/"));

    ResponseEntity<ApiResponse<Object>> resp = handler.handleIllegalState(ex, request);
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
    ApiResponse<Object> body = resp.getBody();
    assert body != null;
    assertEquals("bad state", body.message());
  }

  @Test
  void testHandleGenericException() {
    Exception ex = new Exception("fatal");
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath())
        .thenReturn(org.springframework.http.server.RequestPath.parse("/fatal", "/"));

    ResponseEntity<ApiResponse<Object>> resp = handler.handleGenericException(ex, request);
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
    ApiResponse<Object> body = resp.getBody();
    assert body != null;
    assertEquals("An unexpected error occurred: fatal", body.message());
    assertEquals("/fatal", body.path());
  }
}
