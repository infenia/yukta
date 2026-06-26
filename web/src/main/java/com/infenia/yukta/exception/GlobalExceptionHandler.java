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

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.infenia.yukta.model.api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

/** Global exception handler for the application. */
@Slf4j
@RestControllerAdvice
@NullMarked
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyMethods")
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  @Override
  protected Mono<ResponseEntity<Object>> handleWebExchangeBindException(
      final WebExchangeBindException exception,
      final HttpHeaders headers,
      final HttpStatusCode status,
      final ServerWebExchange exchange) {
    final List<ApiResponse.FieldError> errors =
        exception.getFieldErrors().stream()
            .map(err -> new ApiResponse.FieldError(err.getField(), err.getDefaultMessage()))
            .collect(Collectors.toList());

    @SuppressWarnings("PMD.LawOfDemeter")
    final var request = exchange.getRequest();
    final String path = request.getPath().value();
    final ApiResponse<Object> errorResponse =
        ApiResponse.error(
            status.value(),
            HttpStatus.valueOf(status.value()).getReasonPhrase(),
            "Validation failed",
            path,
            errors);

    return Mono.just(ResponseEntity.status(status).headers(headers).body(errorResponse));
  }

  @Override
  protected Mono<ResponseEntity<Object>> handleServerWebInputException(
      final ServerWebInputException exception,
      final HttpHeaders headers,
      final HttpStatusCode status,
      final ServerWebExchange exchange) {
    final UnrecognizedPropertyException unrecognized = findUnrecognizedPropertyException(exception);
    final Mono<ResponseEntity<Object>> result;
    final String fieldName =
        (unrecognized != null && unrecognized.getPropertyName() != null)
            ? unrecognized.getPropertyName()
            : "unknown";
    final String path = getRequestPath(exchange.getRequest());
    final List<ApiResponse.FieldError> errors =
        List.of(new ApiResponse.FieldError(fieldName, "Unknown field: '" + fieldName + "'"));
    final ApiResponse<Object> errorResponse =
        ApiResponse.error(
            HttpStatus.BAD_REQUEST.value(),
            HttpStatus.BAD_REQUEST.getReasonPhrase(),
            "Invalid request body",
            path,
            errors);
    result = Mono.just(ResponseEntity.badRequest().headers(headers).body(errorResponse));
    return result;
  }

  @Override
  protected Mono<ResponseEntity<Object>> createResponseEntity(
      @Nullable final Object body,
      @Nullable final HttpHeaders headers,
      final HttpStatusCode status,
      final ServerWebExchange exchange) {

    Object responseBody = body;
    if (!(body instanceof ApiResponse)) {
      final String path = getRequestPath(exchange.getRequest());
      final String message = (body instanceof String stringBody) ? stringBody : status.toString();

      responseBody =
          ApiResponse.error(
              status.value(),
              HttpStatus.valueOf(status.value()).getReasonPhrase(),
              message,
              path,
              List.of());
    }

    return super.createResponseEntity(responseBody, headers, status, exchange);
  }

  /**
   * Handle resource not found exceptions.
   *
   * @param exception the exception
   * @param request the current request
   * @return structured error response
   */
  @ExceptionHandler(ResourceNotFoundException.class)
  public ResponseEntity<ApiResponse<Object>> handleResourceNotFound(
      final ResourceNotFoundException exception, final ServerHttpRequest request) {
    final List<ApiResponse.FieldError> errors =
        exception.getResourceType() != null && exception.getResourceId() != null
            ? List.of(
                new ApiResponse.FieldError(
                    exception.getResourceType().toLowerCase(Locale.ENGLISH),
                    exception.getMessage()))
            : List.of();

    final String path = getRequestPath(request);
    final ApiResponse<Object> errorResponse =
        ApiResponse.error(
            HttpStatus.NOT_FOUND.value(),
            HttpStatus.NOT_FOUND.getReasonPhrase(),
            exception.getMessage(),
            path,
            errors);

    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
  }

  /**
   * Handle validation exceptions.
   *
   * @param exception the exception
   * @param request the current request
   * @return structured error response
   */
  @ExceptionHandler(ValidationException.class)
  public ResponseEntity<ApiResponse<Object>> handleValidationException(
      final ValidationException exception, final ServerHttpRequest request) {
    final List<ApiResponse.FieldError> errors =
        exception.getErrors().stream()
            .map(err -> new ApiResponse.FieldError("validation", err))
            .collect(Collectors.toList());

    final String path = getRequestPath(request);
    final ApiResponse<Object> errorResponse =
        ApiResponse.error(
            HttpStatus.BAD_REQUEST.value(),
            HttpStatus.BAD_REQUEST.getReasonPhrase(),
            exception.getMessage(),
            path,
            errors);

    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
  }

  /**
   * Handle constraint violation exceptions from method-level validation.
   *
   * @param exception the exception
   * @param request the current request
   * @return structured error response
   */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiResponse<Object>> handleConstraintViolation(
      final ConstraintViolationException exception, final ServerHttpRequest request) {
    final List<ApiResponse.FieldError> errors =
        exception.getConstraintViolations().stream()
            .map(
                violation ->
                    new ApiResponse.FieldError(
                        violation.getPropertyPath().toString(), violation.getMessage()))
            .collect(Collectors.toList());

    final String path = getRequestPath(request);
    final ApiResponse<Object> errorResponse =
        ApiResponse.error(
            HttpStatus.BAD_REQUEST.value(),
            HttpStatus.BAD_REQUEST.getReasonPhrase(),
            "Constraint violation",
            path,
            errors);

    return ResponseEntity.badRequest().body(errorResponse);
  }

  /**
   * Handle unrecognized property exceptions from JSON deserialization.
   *
   * @param exception the exception
   * @param request the current request
   * @return structured error response
   */
  @ExceptionHandler(UnrecognizedPropertyException.class)
  public ResponseEntity<ApiResponse<Object>> handleUnrecognizedProperty(
      final UnrecognizedPropertyException exception, final ServerHttpRequest request) {
    final String fieldName = exception.getPropertyName();
    final String message = "Unknown field: '" + fieldName + "'";
    final List<ApiResponse.FieldError> errors =
        List.of(new ApiResponse.FieldError(fieldName, message));

    final String path = getRequestPath(request);
    final ApiResponse<Object> errorResponse =
        ApiResponse.error(
            HttpStatus.BAD_REQUEST.value(),
            HttpStatus.BAD_REQUEST.getReasonPhrase(),
            "Invalid request body",
            path,
            errors);

    return ResponseEntity.badRequest().body(errorResponse);
  }

  /**
   * Handle illegal argument exceptions.
   *
   * @param exception the exception
   * @param request the current request
   * @return structured error response
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiResponse<Object>> handleIllegalArgument(
      final IllegalArgumentException exception, final ServerHttpRequest request) {
    return buildErrorResponse(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
  }

  /**
   * Handle illegal state exceptions.
   *
   * @param exception the exception
   * @param request the current request
   * @return structured error response
   */
  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ApiResponse<Object>> handleIllegalState(
      final IllegalStateException exception, final ServerHttpRequest request) {
    return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage(), request);
  }

  /**
   * Handle all other exceptions.
   *
   * @param exception the exception
   * @param request the current request
   * @return structured error response
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Object>> handleGenericException(
      final Exception exception, final ServerHttpRequest request) {
    log.atError().setCause(exception).log("Unhandled exception occurred");
    return buildErrorResponse(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "An unexpected error occurred: " + exception.getMessage(),
        request);
  }

  private ResponseEntity<ApiResponse<Object>> buildErrorResponse(
      final HttpStatus status, final String message, final ServerHttpRequest request) {
    final String path = getRequestPath(request);
    final ApiResponse<Object> errorResponse =
        ApiResponse.error(status.value(), status.getReasonPhrase(), message, path, List.of());
    return ResponseEntity.status(status).body(errorResponse);
  }

  @SuppressWarnings("PMD.LawOfDemeter")
  private String getRequestPath(final ServerHttpRequest request) {
    return request.getPath().value();
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  private @Nullable UnrecognizedPropertyException findUnrecognizedPropertyException(
      final Throwable throwable) {
    Throwable cause = throwable;
    while (cause != null) {
      if (cause instanceof UnrecognizedPropertyException unrecognized) {
        return unrecognized;
      }
      cause = cause.getCause();
    }
    return null;
  }
}
