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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.infenia.yukta.model.api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.RequestPath;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
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
  void testHandleResourceNotFoundException() {
    ResourceNotFoundException ex = new ResourceNotFoundException("Session", "sess-123");
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath())
        .thenReturn(org.springframework.http.server.RequestPath.parse("/api/session", "/"));

    ResponseEntity<ApiResponse<Object>> resp = handler.handleResourceNotFound(ex, request);
    assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    ApiResponse<Object> body = resp.getBody();
    assert body != null;
    assertEquals(404, body.status());
    assertEquals("Session not found: 'sess-123'", body.message());
    assertEquals("/api/session", body.path());
    assertEquals(1, body.errors().size());
    assertEquals("session", body.errors().get(0).field());
  }

  @Test
  void testHandleValidationException() {
    ValidationException ex =
        new ValidationException(
            "Validation failed",
            java.util.List.of("Field A is required", "Field B must be positive"));
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath())
        .thenReturn(org.springframework.http.server.RequestPath.parse("/api/validate", "/"));

    ResponseEntity<ApiResponse<Object>> resp = handler.handleValidationException(ex, request);
    assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    ApiResponse<Object> body = resp.getBody();
    assert body != null;
    assertEquals(400, body.status());
    assertEquals("Validation failed", body.message());
    assertEquals("/api/validate", body.path());
    assertEquals(2, body.errors().size());
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

  @Test
  @SuppressWarnings("unchecked")
  void testHandleWebExchangeBindException_multipleErrors() {
    BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "obj");
    result.addError(new FieldError("obj", "field1", "message1"));
    result.addError(new FieldError("obj", "field2", "message2"));
    WebExchangeBindException ex = new WebExchangeBindException(null, result);

    ServerWebExchange exchange = mock(ServerWebExchange.class);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(exchange.getRequest()).thenReturn(request);
    when(request.getPath()).thenReturn(RequestPath.parse("/api/multi", "/"));

    StepVerifier.create(
            handler.handleWebExchangeBindException(
                ex, new HttpHeaders(), HttpStatus.BAD_REQUEST, exchange))
        .expectNextMatches(
            resp -> {
              ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
              return body.errors().size() == 2
                  && "field1".equals(body.errors().get(0).field())
                  && "field2".equals(body.errors().get(1).field());
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testHandleServerWebInputException_withUnrecognizedProperty() {
    UnrecognizedPropertyException unrecognizedEx = mock(UnrecognizedPropertyException.class);
    when(unrecognizedEx.getPropertyName()).thenReturn("extraField");

    ServerWebInputException ex = new ServerWebInputException("error", null, unrecognizedEx);

    ServerWebExchange exchange = mock(ServerWebExchange.class);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(exchange.getRequest()).thenReturn(request);
    when(request.getPath()).thenReturn(RequestPath.parse("/api/input", "/"));

    StepVerifier.create(
            handler.handleServerWebInputException(
                ex, new HttpHeaders(), HttpStatus.BAD_REQUEST, exchange))
        .expectNextMatches(
            resp -> {
              ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
              return resp.getStatusCode() == HttpStatus.BAD_REQUEST
                  && body.status() == 400
                  && "Invalid request body".equals(body.message())
                  && body.errors().size() == 1
                  && "extraField".equals(body.errors().get(0).field())
                  && "Unknown field: 'extraField'".equals(body.errors().get(0).message());
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testHandleServerWebInputException_nestedUnrecognizedProperty() {
    UnrecognizedPropertyException unrecognizedEx = mock(UnrecognizedPropertyException.class);
    when(unrecognizedEx.getPropertyName()).thenReturn("nestedField");

    RuntimeException middle = new RuntimeException("intermediate", unrecognizedEx);
    ServerWebInputException ex = new ServerWebInputException("error", null, middle);

    ServerWebExchange exchange = mock(ServerWebExchange.class);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(exchange.getRequest()).thenReturn(request);
    when(request.getPath()).thenReturn(RequestPath.parse("/api/nested", "/"));

    StepVerifier.create(
            handler.handleServerWebInputException(
                ex, new HttpHeaders(), HttpStatus.BAD_REQUEST, exchange))
        .expectNextMatches(
            resp -> {
              ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
              return "nestedField".equals(body.errors().get(0).field());
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testCreateResponseEntity_stringBody() {
    ServerWebExchange exchange = mock(ServerWebExchange.class);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(exchange.getRequest()).thenReturn(request);
    when(request.getPath()).thenReturn(RequestPath.parse("/test", "/"));

    StepVerifier.create(
            handler.createResponseEntity(
                "custom error message", new HttpHeaders(), HttpStatus.BAD_REQUEST, exchange))
        .expectNextMatches(
            resp -> {
              ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
              return "custom error message".equals(body.message())
                  && resp.getStatusCode() == HttpStatus.BAD_REQUEST;
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testCreateResponseEntity_nullBody() {
    ServerWebExchange exchange = mock(ServerWebExchange.class);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(exchange.getRequest()).thenReturn(request);
    when(request.getPath()).thenReturn(RequestPath.parse("/null", "/"));

    StepVerifier.create(
            handler.createResponseEntity(
                null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, exchange))
        .expectNextMatches(
            resp -> {
              ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
              return "500 INTERNAL_SERVER_ERROR".equals(body.message())
                  && resp.getStatusCode() == HttpStatus.INTERNAL_SERVER_ERROR;
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testCreateResponseEntity_alreadyApiResponse() {
    ApiResponse<String> existingResponse = ApiResponse.success(200, "Already formatted", "data");

    ServerWebExchange exchange = mock(ServerWebExchange.class);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(exchange.getRequest()).thenReturn(request);
    when(request.getPath()).thenReturn(RequestPath.parse("/api", "/"));

    StepVerifier.create(
            handler.createResponseEntity(
                existingResponse, new HttpHeaders(), HttpStatus.OK, exchange))
        .expectNextMatches(
            resp -> {
              ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
              return body.equals(existingResponse) && resp.getStatusCode() == HttpStatus.OK;
            })
        .verifyComplete();
  }

  @Test
  void testHandleResourceNotFoundException_withoutResourceTypeAndId() {
    ResourceNotFoundException ex = new ResourceNotFoundException("Custom message");
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/api/resource", "/"));

    ResponseEntity<ApiResponse<Object>> resp = handler.handleResourceNotFound(ex, request);
    assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    ApiResponse<Object> body = resp.getBody();
    assertNotNull(body);
    assertEquals("Custom message", body.message());
    assertEquals(0, body.errors().size());
  }

  @Test
  void testHandleResourceNotFoundException_withResourceTypeOnly() {
    ResourceNotFoundException ex = new ResourceNotFoundException("Workflow", null);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/workflows", "/"));

    ResponseEntity<ApiResponse<Object>> resp = handler.handleResourceNotFound(ex, request);
    assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    ApiResponse<Object> body = resp.getBody();
    assertNotNull(body);
    assertEquals(404, body.status());
    assertEquals(0, body.errors().size());
  }

  @Test
  void testHandleValidationException_singleError() {
    ValidationException ex = new ValidationException("Validation failed");
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/validate", "/"));

    ResponseEntity<ApiResponse<Object>> resp = handler.handleValidationException(ex, request);
    assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    ApiResponse<Object> body = resp.getBody();
    assertNotNull(body);
    assertEquals(400, body.status());
    assertEquals("Validation failed", body.message());
    assertEquals(1, body.errors().size());
    assertEquals("validation", body.errors().get(0).field());
  }

  @Test
  void testHandleValidationException_multipleErrors() {
    ValidationException ex =
        new ValidationException("Validation failed", List.of("Error 1", "Error 2", "Error 3"));
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/validate", "/"));

    ResponseEntity<ApiResponse<Object>> resp = handler.handleValidationException(ex, request);
    assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    ApiResponse<Object> body = resp.getBody();
    assertNotNull(body);
    assertEquals(3, body.errors().size());
    assertThat(body.errors())
        .allMatch(err -> "validation".equals(err.field()))
        .extracting(ApiResponse.FieldError::message)
        .containsExactly("Error 1", "Error 2", "Error 3");
  }

  @Test
  void testHandleConstraintViolation_multipleViolations() {
    jakarta.validation.ConstraintViolation<?> violation1 =
        mock(jakarta.validation.ConstraintViolation.class);
    jakarta.validation.ConstraintViolation<?> violation2 =
        mock(jakarta.validation.ConstraintViolation.class);
    jakarta.validation.Path path1 = mock(jakarta.validation.Path.class);
    jakarta.validation.Path path2 = mock(jakarta.validation.Path.class);

    when(path1.toString()).thenReturn("field1");
    when(path2.toString()).thenReturn("field2");
    when(violation1.getPropertyPath()).thenReturn(path1);
    when(violation2.getPropertyPath()).thenReturn(path2);
    when(violation1.getMessage()).thenReturn("message1");
    when(violation2.getMessage()).thenReturn("message2");

    ConstraintViolationException ex =
        new ConstraintViolationException(Set.of(violation1, violation2));
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/api", "/"));

    ResponseEntity<ApiResponse<Object>> resp = handler.handleConstraintViolation(ex, request);
    assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    ApiResponse<Object> body = resp.getBody();
    assertNotNull(body);
    assertEquals(2, body.errors().size());
    assertEquals("Constraint violation", body.message());
  }

  @Test
  void testHandleUnrecognizedProperty() {
    UnrecognizedPropertyException ex = mock(UnrecognizedPropertyException.class);
    when(ex.getPropertyName()).thenReturn("unknownProperty");

    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/api/body", "/"));

    ResponseEntity<ApiResponse<Object>> resp = handler.handleUnrecognizedProperty(ex, request);
    assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    ApiResponse<Object> body = resp.getBody();
    assertNotNull(body);
    assertEquals(400, body.status());
    assertEquals("Invalid request body", body.message());
    assertEquals(1, body.errors().size());
    assertEquals("unknownProperty", body.errors().get(0).field());
    assertEquals("Unknown field: 'unknownProperty'", body.errors().get(0).message());
  }

  @Test
  void testHandleIllegalArgument_withMessage() {
    IllegalArgumentException ex = new IllegalArgumentException("argument was invalid");
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/args", "/"));

    ResponseEntity<ApiResponse<Object>> resp = handler.handleIllegalArgument(ex, request);
    assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    ApiResponse<Object> body = resp.getBody();
    assertNotNull(body);
    assertEquals(400, body.status());
    assertEquals("Bad Request", body.error());
    assertEquals("argument was invalid", body.message());
    assertEquals("/args", body.path());
    assertEquals(0, body.errors().size());
  }

  @Test
  void testHandleIllegalArgument_emptyMessage() {
    IllegalArgumentException ex = new IllegalArgumentException();
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/args", "/"));

    ResponseEntity<ApiResponse<Object>> resp = handler.handleIllegalArgument(ex, request);
    assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    ApiResponse<Object> body = resp.getBody();
    assertNotNull(body);
    assertEquals(400, body.status());
  }

  @Test
  void testHandleIllegalState_withMessage() {
    IllegalStateException ex = new IllegalStateException("system in invalid state");
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/state", "/"));

    ResponseEntity<ApiResponse<Object>> resp = handler.handleIllegalState(ex, request);
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
    ApiResponse<Object> body = resp.getBody();
    assertNotNull(body);
    assertEquals(500, body.status());
    assertEquals("Internal Server Error", body.error());
    assertEquals("system in invalid state", body.message());
  }

  @Test
  void testHandleIllegalState_emptyMessage() {
    IllegalStateException ex = new IllegalStateException();
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/state", "/"));

    ResponseEntity<ApiResponse<Object>> resp = handler.handleIllegalState(ex, request);
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
    ApiResponse<Object> body = resp.getBody();
    assertNotNull(body);
    assertEquals(500, body.status());
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void testHandleGenericException_logsErrorWithCause(CapturedOutput output) {
    Exception cause = new RuntimeException("root cause");
    Exception ex = new Exception("wrapper", cause);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/error", "/"));

    ResponseEntity<ApiResponse<Object>> resp = handler.handleGenericException(ex, request);
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
    assertThat(output.getAll()).contains("Unhandled exception occurred");
  }

  @Test
  void testHandleGenericException_nullMessage() {
    Exception ex = new Exception();
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/error", "/"));

    ResponseEntity<ApiResponse<Object>> resp = handler.handleGenericException(ex, request);
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
    ApiResponse<Object> body = resp.getBody();
    assertNotNull(body);
    assertEquals(500, body.status());
    assertEquals("An unexpected error occurred: null", body.message());
  }

  @Test
  void testHandleResourceNotFound_errorFieldLowercase() {
    ResourceNotFoundException ex = new ResourceNotFoundException("Session", "test-id");
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/sessions", "/"));

    ResponseEntity<ApiResponse<Object>> resp = handler.handleResourceNotFound(ex, request);
    ApiResponse<Object> body = resp.getBody();
    assertNotNull(body);
    assertEquals(1, body.errors().size());
    assertEquals("session", body.errors().get(0).field());
  }

  @Test
  void testConstructor() {
    GlobalExceptionHandler handlerInstance = new GlobalExceptionHandler();
    assertNotNull(handlerInstance);
  }

  @Test
  @SuppressWarnings("unchecked")
  void testHandleServerWebInputException_unrecognizedIsNull() {
    ServerWebInputException ex = new ServerWebInputException("error", null, null);

    ServerWebExchange exchange = mock(ServerWebExchange.class);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(exchange.getRequest()).thenReturn(request);
    when(request.getPath()).thenReturn(RequestPath.parse("/api/input", "/"));

    StepVerifier.create(
            handler.handleServerWebInputException(
                ex, new HttpHeaders(), HttpStatus.BAD_REQUEST, exchange))
        .expectNextMatches(
            resp -> {
              ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
              return "unknown".equals(body.errors().get(0).field());
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testHandleServerWebInputException_propertyNameIsNull() {
    UnrecognizedPropertyException unrecognizedEx = mock(UnrecognizedPropertyException.class);
    when(unrecognizedEx.getPropertyName()).thenReturn(null);

    ServerWebInputException ex = new ServerWebInputException("error", null, unrecognizedEx);

    ServerWebExchange exchange = mock(ServerWebExchange.class);
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(exchange.getRequest()).thenReturn(request);
    when(request.getPath()).thenReturn(RequestPath.parse("/api/input", "/"));

    StepVerifier.create(
            handler.handleServerWebInputException(
                ex, new HttpHeaders(), HttpStatus.BAD_REQUEST, exchange))
        .expectNextMatches(
            resp -> {
              ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
              return "unknown".equals(body.errors().get(0).field());
            })
        .verifyComplete();
  }
}
