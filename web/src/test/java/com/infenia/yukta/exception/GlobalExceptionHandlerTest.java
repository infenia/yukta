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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.infenia.yukta.model.api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Set;
import lombok.NoArgsConstructor;
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

/** Tests for GlobalExceptionHandler. */
@NoArgsConstructor
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.TooManyMethods"})
class GlobalExceptionHandlerTest {

  /** Validation failed message constant. */
  private static final String VALIDATION_FAILED = "Validation failed";

  /** Constraint violation message constant. */
  private static final String CONSTRAINT_VIOLATION = "Constraint violation";

  /** Invalid request body message constant. */
  private static final String INVALID_REQUEST_BODY = "Invalid request body";

  /** API path constant. */
  private static final String API_PATH = "/api";

  /** Unchecked warning suppression constant. */
  private static final String UNCHECKED_SUPPRESSION = "unchecked";

  /** Object name constant. */
  private static final String OBJ = "obj";

  /** Error message constant. */
  private static final String ERROR = "error";

  /** Exception handler for testing. */
  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  @SuppressWarnings(UNCHECKED_SUPPRESSION)
  void testHandleWebExchangeBindException() {
    final BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), OBJ);
    result.addError(new FieldError(OBJ, "field", "rejected", false, null, null, "message"));
    final WebExchangeBindException exception = new WebExchangeBindException(null, result);

    final ServerWebExchange exchange = mock(ServerWebExchange.class);
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(exchange.getRequest()).thenReturn(request);
    when(request.getPath()).thenReturn(RequestPath.parse(API_PATH, "/"));

    StepVerifier.create(
            handler.handleWebExchangeBindException(
                exception, new HttpHeaders(), HttpStatus.BAD_REQUEST, exchange))
        .expectNextMatches(
            resp -> {
              final ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
              return resp.getStatusCode() == HttpStatus.BAD_REQUEST
                  && body.status() == 400
                  && VALIDATION_FAILED.equals(body.message())
                  && API_PATH.equals(body.path())
                  && body.errors().size() == 1
                  && "field".equals(body.errors().get(0).field());
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings(UNCHECKED_SUPPRESSION)
  void testCreateResponseEntity() {
    final ServerWebExchange exchange = mock(ServerWebExchange.class);
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(exchange.getRequest()).thenReturn(request);
    when(request.getPath()).thenReturn(RequestPath.parse("/create", "/"));

    // Case body not ApiResponse
    StepVerifier.create(
            handler.createResponseEntity(
                "error msg", new HttpHeaders(), HttpStatusCode.valueOf(404), exchange))
        .expectNextMatches(
            resp -> {
              final ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
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
              final ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
              return resp.getStatusCode().value() == 500
                  && body.status() == 500
                  && "500 INTERNAL_SERVER_ERROR".equals(body.message());
            })
        .verifyComplete();

    // Case body IS ApiResponse
    final ApiResponse<Object> existing = ApiResponse.success(200, "OK", "data");
    StepVerifier.create(
            handler.createResponseEntity(
                existing, new HttpHeaders(), HttpStatusCode.valueOf(200), exchange))
        .expectNextMatches(
            resp -> {
              final ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
              return resp.getStatusCode().value() == 200 && body.equals(existing);
            })
        .verifyComplete();
  }

  @Test
  void testHandleConstraintViolation() {
    final jakarta.validation.ConstraintViolation<?> violation =
        mock(jakarta.validation.ConstraintViolation.class);
    final jakarta.validation.Path path = mock(jakarta.validation.Path.class);
    when(path.toString()).thenReturn("property");
    when(violation.getPropertyPath()).thenReturn(path);
    when(violation.getMessage()).thenReturn("violation message");

    final ConstraintViolationException exception =
        new ConstraintViolationException(Set.of(violation));
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse(API_PATH, "/"));

    final ResponseEntity<ApiResponse<Object>> resp =
        handler.handleConstraintViolation(exception, request);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    final ApiResponse<Object> body = resp.getBody();
    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo(400);
    assertThat(body.message()).isEqualTo(CONSTRAINT_VIOLATION);
    assertThat(body.path()).isEqualTo(API_PATH);
    assertThat(body.errors()).hasSize(1);
    assertThat(body.errors().get(0).field()).isEqualTo("property");
    assertThat(body.errors().get(0).message()).isEqualTo("violation message");
  }

  @Test
  void testHandleResourceNotFoundException() {
    final ResourceNotFoundException exception =
        new ResourceNotFoundException("Session", "sess-123");
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/api/session", "/"));

    final ResponseEntity<ApiResponse<Object>> resp =
        handler.handleResourceNotFound(exception, request);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    final ApiResponse<Object> body = resp.getBody();
    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo(404);
    assertThat(body.message()).isEqualTo("Session not found: 'sess-123'");
    assertThat(body.path()).isEqualTo("/api/session");
    assertThat(body.errors()).hasSize(1);
    assertThat(body.errors().get(0).field()).isEqualTo("session");
  }

  @Test
  void testHandleValidationException() {
    final ValidationException exception =
        new ValidationException(
            VALIDATION_FAILED, List.of("Field A is required", "Field B must be positive"));
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/api/validate", "/"));

    final ResponseEntity<ApiResponse<Object>> resp =
        handler.handleValidationException(exception, request);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    final ApiResponse<Object> body = resp.getBody();
    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo(400);
    assertThat(body.message()).isEqualTo(VALIDATION_FAILED);
    assertThat(body.path()).isEqualTo("/api/validate");
    assertThat(body.errors()).hasSize(2);
  }

  @Test
  void testHandleIllegalArgument() {
    final IllegalArgumentException exception = new IllegalArgumentException("invalid arg");
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/arg", "/"));

    final ResponseEntity<ApiResponse<Object>> resp =
        handler.handleIllegalArgument(exception, request);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    final ApiResponse<Object> body = resp.getBody();
    assertThat(body).isNotNull();
    assertThat(body.message()).isEqualTo("invalid arg");
    assertThat(body.path()).isEqualTo("/arg");
  }

  @Test
  void testHandleIllegalState() {
    final IllegalStateException exception = new IllegalStateException("bad state");
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/state", "/"));

    final ResponseEntity<ApiResponse<Object>> resp = handler.handleIllegalState(exception, request);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    final ApiResponse<Object> body = resp.getBody();
    assertThat(body).isNotNull();
    assertThat(body.message()).isEqualTo("bad state");
  }

  @Test
  void testHandleGenericException() {
    final Exception exception = new Exception("fatal");
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/fatal", "/"));

    final ResponseEntity<ApiResponse<Object>> resp =
        handler.handleGenericException(exception, request);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    final ApiResponse<Object> body = resp.getBody();
    assertThat(body).isNotNull();
    assertThat(body.message()).isEqualTo("An unexpected error occurred: fatal");
    assertThat(body.path()).isEqualTo("/fatal");
  }

  @Test
  @SuppressWarnings(UNCHECKED_SUPPRESSION)
  void testHandleWebExchangeBindException_multipleErrors() {
    final BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), OBJ);
    result.addError(new FieldError(OBJ, "field1", "message1"));
    result.addError(new FieldError(OBJ, "field2", "message2"));
    final WebExchangeBindException exception = new WebExchangeBindException(null, result);

    final ServerWebExchange exchange = mock(ServerWebExchange.class);
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(exchange.getRequest()).thenReturn(request);
    when(request.getPath()).thenReturn(RequestPath.parse("/api/multi", "/"));

    StepVerifier.create(
            handler.handleWebExchangeBindException(
                exception, new HttpHeaders(), HttpStatus.BAD_REQUEST, exchange))
        .expectNextMatches(
            resp -> {
              final ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
              return body.errors().size() == 2
                  && "field1".equals(body.errors().get(0).field())
                  && "field2".equals(body.errors().get(1).field())
                  && body.status() == 400;
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings(UNCHECKED_SUPPRESSION)
  void testHandleServerWebInputException_withUnrecognizedProperty() {
    final UnrecognizedPropertyException unrecognizedEx = mock(UnrecognizedPropertyException.class);
    when(unrecognizedEx.getPropertyName()).thenReturn("extraField");

    final ServerWebInputException exception =
        new ServerWebInputException(ERROR, null, unrecognizedEx);

    final ServerWebExchange exchange = mock(ServerWebExchange.class);
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(exchange.getRequest()).thenReturn(request);
    when(request.getPath()).thenReturn(RequestPath.parse("/api/input", "/"));

    StepVerifier.create(
            handler.handleServerWebInputException(
                exception, new HttpHeaders(), HttpStatus.BAD_REQUEST, exchange))
        .expectNextMatches(
            resp -> {
              final ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
              return resp.getStatusCode() == HttpStatus.BAD_REQUEST
                  && body.status() == 400
                  && INVALID_REQUEST_BODY.equals(body.message())
                  && body.errors().size() == 1
                  && "extraField".equals(body.errors().get(0).field())
                  && "Unknown field: 'extraField'".equals(body.errors().get(0).message());
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings(UNCHECKED_SUPPRESSION)
  void testHandleServerWebInputException_nestedUnrecognizedProperty() {
    final UnrecognizedPropertyException unrecognizedEx = mock(UnrecognizedPropertyException.class);
    when(unrecognizedEx.getPropertyName()).thenReturn("nestedField");

    final RuntimeException middle = new RuntimeException("intermediate", unrecognizedEx);
    final ServerWebInputException exception = new ServerWebInputException(ERROR, null, middle);

    final ServerWebExchange exchange = mock(ServerWebExchange.class);
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(exchange.getRequest()).thenReturn(request);
    when(request.getPath()).thenReturn(RequestPath.parse("/api/nested", "/"));

    StepVerifier.create(
            handler.handleServerWebInputException(
                exception, new HttpHeaders(), HttpStatus.BAD_REQUEST, exchange))
        .expectNextMatches(
            resp -> {
              final ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
              return "nestedField".equals(body.errors().get(0).field());
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings(UNCHECKED_SUPPRESSION)
  void testCreateResponseEntity_stringBody() {
    final ServerWebExchange exchange = mock(ServerWebExchange.class);
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(exchange.getRequest()).thenReturn(request);
    when(request.getPath()).thenReturn(RequestPath.parse("/test", "/"));

    StepVerifier.create(
            handler.createResponseEntity(
                "custom error message", new HttpHeaders(), HttpStatus.BAD_REQUEST, exchange))
        .expectNextMatches(
            resp -> {
              final ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
              return "custom error message".equals(body.message())
                  && resp.getStatusCode() == HttpStatus.BAD_REQUEST
                  && body.status() == 400;
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings(UNCHECKED_SUPPRESSION)
  void testCreateResponseEntity_nullBody() {
    final ServerWebExchange exchange = mock(ServerWebExchange.class);
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(exchange.getRequest()).thenReturn(request);
    when(request.getPath()).thenReturn(RequestPath.parse("/null", "/"));

    StepVerifier.create(
            handler.createResponseEntity(
                null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, exchange))
        .expectNextMatches(
            resp -> {
              final ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
              return "500 INTERNAL_SERVER_ERROR".equals(body.message())
                  && resp.getStatusCode() == HttpStatus.INTERNAL_SERVER_ERROR
                  && body.status() == 500;
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings(UNCHECKED_SUPPRESSION)
  void testCreateResponseEntity_alreadyApiResponse() {
    final ApiResponse<String> existingResponse =
        ApiResponse.success(200, "Already formatted", "data");

    final ServerWebExchange exchange = mock(ServerWebExchange.class);
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(exchange.getRequest()).thenReturn(request);
    when(request.getPath()).thenReturn(RequestPath.parse("/api", "/"));

    StepVerifier.create(
            handler.createResponseEntity(
                existingResponse, new HttpHeaders(), HttpStatus.OK, exchange))
        .expectNextMatches(
            resp -> {
              final ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
              return body.equals(existingResponse) && resp.getStatusCode() == HttpStatus.OK;
            })
        .verifyComplete();
  }

  @Test
  void testHandleResourceNotFoundException_withoutResourceTypeAndId() {
    final ResourceNotFoundException exception = new ResourceNotFoundException("Custom message");
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/api/resource", "/"));

    final ResponseEntity<ApiResponse<Object>> resp =
        handler.handleResourceNotFound(exception, request);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    final ApiResponse<Object> body = resp.getBody();
    assertThat(body).isNotNull();
    assertThat(body.message()).isEqualTo("Custom message");
    assertThat(body.errors()).isEmpty();
  }

  @Test
  void testHandleResourceNotFoundException_withResourceTypeOnly() {
    final ResourceNotFoundException exception = new ResourceNotFoundException("Workflow", null);
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/workflows", "/"));

    final ResponseEntity<ApiResponse<Object>> resp =
        handler.handleResourceNotFound(exception, request);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    final ApiResponse<Object> body = resp.getBody();
    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo(404);
    assertThat(body.errors()).isEmpty();
  }

  @Test
  void testHandleValidationException_singleError() {
    final ValidationException exception = new ValidationException(VALIDATION_FAILED);
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/validate", "/"));

    final ResponseEntity<ApiResponse<Object>> resp =
        handler.handleValidationException(exception, request);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    final ApiResponse<Object> body = resp.getBody();
    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo(400);
    assertThat(body.message()).isEqualTo(VALIDATION_FAILED);
    assertThat(body.errors()).hasSize(1);
    assertThat(body.errors().get(0).field()).isEqualTo("validation");
  }

  @Test
  void testHandleValidationException_multipleErrors() {
    final ValidationException exception =
        new ValidationException(VALIDATION_FAILED, List.of("Error 1", "Error 2", "Error 3"));
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/validate", "/"));

    final ResponseEntity<ApiResponse<Object>> resp =
        handler.handleValidationException(exception, request);
    assertThat(resp.getStatusCode())
        .as("status should be BAD_REQUEST")
        .isEqualTo(HttpStatus.BAD_REQUEST);
    final ApiResponse<Object> body = resp.getBody();
    assertThat(body).as("response body should not be null").isNotNull();
    assertThat(body.status()).isEqualTo(400);
    assertThat(body.message()).isEqualTo(VALIDATION_FAILED);
    assertThat(body.errors()).as("errors should have 3 items").hasSize(3);
    assertThat(body.errors())
        .allMatch(err -> "validation".equals(err.field()))
        .extracting(ApiResponse.FieldError::message)
        .containsExactly("Error 1", "Error 2", "Error 3");
  }

  @Test
  void testHandleConstraintViolation_multipleViolations() {
    final jakarta.validation.ConstraintViolation<?> violation1 =
        mock(jakarta.validation.ConstraintViolation.class);
    final jakarta.validation.ConstraintViolation<?> violation2 =
        mock(jakarta.validation.ConstraintViolation.class);
    final jakarta.validation.Path path1 = mock(jakarta.validation.Path.class);
    final jakarta.validation.Path path2 = mock(jakarta.validation.Path.class);

    when(path1.toString()).thenReturn("field1");
    when(path2.toString()).thenReturn("field2");
    when(violation1.getPropertyPath()).thenReturn(path1);
    when(violation2.getPropertyPath()).thenReturn(path2);
    when(violation1.getMessage()).thenReturn("message1");
    when(violation2.getMessage()).thenReturn("message2");

    final ConstraintViolationException exception =
        new ConstraintViolationException(Set.of(violation1, violation2));
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/api", "/"));

    final ResponseEntity<ApiResponse<Object>> resp =
        handler.handleConstraintViolation(exception, request);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    final ApiResponse<Object> body = resp.getBody();
    assertThat(body).isNotNull();
    assertThat(body.errors()).hasSize(2);
    assertThat(body.message()).isEqualTo(CONSTRAINT_VIOLATION);
    assertThat(body.status()).isEqualTo(400);
  }

  @Test
  void testHandleUnrecognizedProperty() {
    final UnrecognizedPropertyException exception = mock(UnrecognizedPropertyException.class);
    when(exception.getPropertyName()).thenReturn("unknownProperty");

    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/api/body", "/"));

    final ResponseEntity<ApiResponse<Object>> resp =
        handler.handleUnrecognizedProperty(exception, request);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    final ApiResponse<Object> body = resp.getBody();
    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo(400);
    assertThat(body.message()).isEqualTo(INVALID_REQUEST_BODY);
    assertThat(body.errors()).hasSize(1);
    assertThat(body.errors().get(0).field()).isEqualTo("unknownProperty");
    assertThat(body.errors().get(0).message()).isEqualTo("Unknown field: 'unknownProperty'");
  }

  @Test
  void testHandleIllegalArgument_withMessage() {
    final IllegalArgumentException exception = new IllegalArgumentException("argument was invalid");
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/args", "/"));

    final ResponseEntity<ApiResponse<Object>> resp =
        handler.handleIllegalArgument(exception, request);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    final ApiResponse<Object> body = resp.getBody();
    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo(400);
    assertThat(body.message()).isEqualTo("argument was invalid");
    assertThat(body.path()).isEqualTo("/args");
    assertThat(body.errors()).isEmpty();
  }

  @Test
  void testHandleIllegalArgument_emptyMessage() {
    final IllegalArgumentException exception = new IllegalArgumentException();
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/args", "/"));

    final ResponseEntity<ApiResponse<Object>> resp =
        handler.handleIllegalArgument(exception, request);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    final ApiResponse<Object> body = resp.getBody();
    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo(400);
  }

  @Test
  void testHandleIllegalState_withMessage() {
    final IllegalStateException exception = new IllegalStateException("system in invalid state");
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/state", "/"));

    final ResponseEntity<ApiResponse<Object>> resp = handler.handleIllegalState(exception, request);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    final ApiResponse<Object> body = resp.getBody();
    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo(500);
    assertThat(body.message()).isEqualTo("system in invalid state");
  }

  @Test
  void testHandleIllegalState_emptyMessage() {
    final IllegalStateException exception = new IllegalStateException();
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/state", "/"));

    final ResponseEntity<ApiResponse<Object>> resp = handler.handleIllegalState(exception, request);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    final ApiResponse<Object> body = resp.getBody();
    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo(500);
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void testHandleGenericException_logsErrorWithCause(final CapturedOutput output) {
    final Exception cause = new RuntimeException("root cause");
    final Exception exception = new Exception("wrapper", cause);
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/error", "/"));

    final ResponseEntity<ApiResponse<Object>> resp =
        handler.handleGenericException(exception, request);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(output.getAll()).contains("Unhandled exception occurred");
  }

  @Test
  void testHandleGenericException_nullMessage() {
    final Exception exception = new Exception();
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/error", "/"));

    final ResponseEntity<ApiResponse<Object>> resp =
        handler.handleGenericException(exception, request);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    final ApiResponse<Object> body = resp.getBody();
    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo(500);
    assertThat(body.message()).isEqualTo("An unexpected error occurred: null");
  }

  @Test
  void testHandleResourceNotFound_errorFieldLowercase() {
    final ResourceNotFoundException exception = new ResourceNotFoundException("Session", "test-id");
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getPath()).thenReturn(RequestPath.parse("/sessions", "/"));

    final ResponseEntity<ApiResponse<Object>> resp =
        handler.handleResourceNotFound(exception, request);
    final ApiResponse<Object> body = resp.getBody();
    assertThat(body).isNotNull();
    assertThat(body.errors()).hasSize(1);
    assertThat(body.errors().get(0).field()).isEqualTo("session");
  }

  @Test
  void testConstructor() {
    final GlobalExceptionHandler handlerInstance = new GlobalExceptionHandler();
    assertThat(handlerInstance).isNotNull();
  }

  @Test
  @SuppressWarnings(UNCHECKED_SUPPRESSION)
  void testHandleServerWebInputException_unrecognizedIsNull() {
    final ServerWebInputException exception = new ServerWebInputException(ERROR, null, null);

    final ServerWebExchange exchange = mock(ServerWebExchange.class);
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(exchange.getRequest()).thenReturn(request);
    when(request.getPath()).thenReturn(RequestPath.parse("/api/input", "/"));

    StepVerifier.create(
            handler.handleServerWebInputException(
                exception, new HttpHeaders(), HttpStatus.BAD_REQUEST, exchange))
        .expectNextMatches(
            resp -> {
              final ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
              assertThat(body.errors().get(0).field()).isEqualTo("unknown");
              return true;
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings(UNCHECKED_SUPPRESSION)
  void testHandleServerWebInputException_propertyNameIsNull() {
    final UnrecognizedPropertyException unrecognizedEx = mock(UnrecognizedPropertyException.class);
    when(unrecognizedEx.getPropertyName()).thenReturn(null);

    final ServerWebInputException exception =
        new ServerWebInputException(ERROR, null, unrecognizedEx);

    final ServerWebExchange exchange = mock(ServerWebExchange.class);
    final ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(exchange.getRequest()).thenReturn(request);
    when(request.getPath()).thenReturn(RequestPath.parse("/api/input", "/"));

    StepVerifier.create(
            handler.handleServerWebInputException(
                exception, new HttpHeaders(), HttpStatus.BAD_REQUEST, exchange))
        .expectNextMatches(
            resp -> {
              final ApiResponse<Object> body = (ApiResponse<Object>) resp.getBody();
              assertThat(body.errors().get(0).field()).isEqualTo("unknown");
              return true;
            })
        .verifyComplete();
  }
}
