// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.exception;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.infenia.yukta.filter.RequestTracingFilter;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ExecutionRejectedException;
import com.infenia.yukta.service.leader.NotLeaderException;
import com.infenia.yukta.service.supervisor.ControlRejectedException;
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
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CouplingBetweenObjects"})
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
    final String path = getRequestPath(exchange.getRequest());
    final List<ApiResponse.FieldError> errors;
    final String message;
    if (unrecognized != null) {
      final String fieldName =
          unrecognized.getPropertyName() != null ? unrecognized.getPropertyName() : "unknown";
      errors = List.of(new ApiResponse.FieldError(fieldName, "Unknown field: '" + fieldName + "'"));
      message = "Invalid request body";
    } else {
      errors = List.of();
      message = exception.getReason() != null ? exception.getReason() : "Invalid request";
    }
    final ApiResponse<Object> errorResponse =
        ApiResponse.error(
            HttpStatus.BAD_REQUEST.value(),
            HttpStatus.BAD_REQUEST.getReasonPhrase(),
            message,
            path,
            errors);
    return Mono.just(ResponseEntity.badRequest().headers(headers).body(errorResponse));
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
   * Handle missing sessions, workflows, executions and nodes reported by core.
   *
   * @param exception the exception
   * @param request the current request
   * @return structured 404 response
   */
  @ExceptionHandler(NotFoundException.class)
  public ResponseEntity<ApiResponse<Object>> handleNotFound(
      final NotFoundException exception, final ServerHttpRequest request) {
    return buildErrorResponse(HttpStatus.NOT_FOUND, exception.getMessage(), request);
  }

  /**
   * Handles a command refused by a supervisor as forbidden.
   *
   * @param exception the refusal
   * @param request the request
   * @return the error response
   */
  @ExceptionHandler(ControlRejectedException.class)
  public ResponseEntity<ApiResponse<Object>> handleControlRejected(
      final ControlRejectedException exception, final ServerHttpRequest request) {
    return buildErrorResponse(HttpStatus.FORBIDDEN, exception.getMessage(), request);
  }

  /**
   * Handles illegal-argument errors as bad requests.
   *
   * @param exception the exception
   * @param request the request
   * @return the 400 response
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiResponse<Object>> handleIllegalArgument(
      final IllegalArgumentException exception, final ServerHttpRequest request) {
    return buildErrorResponse(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
  }

  /**
   * Handles an expression that does not parse (a node's SpEL) as a bad request: the definition is
   * wrong, not the server.
   *
   * @param exception the parse failure
   * @param request the request
   * @return the 400 response with the parser's message
   */
  @ExceptionHandler(org.springframework.expression.ParseException.class)
  public ResponseEntity<ApiResponse<Object>> handleExpressionParse(
      final org.springframework.expression.ParseException exception,
      final ServerHttpRequest request) {
    return buildErrorResponse(
        HttpStatus.BAD_REQUEST, "Invalid expression: " + exception.getMessage(), request);
  }

  /**
   * Handle a start sent to a standby server.
   *
   * @param exception the exception
   * @param request the current request
   * @return structured 503 response
   */
  @ExceptionHandler(NotLeaderException.class)
  public ResponseEntity<ApiResponse<Object>> handleNotLeader(
      final NotLeaderException exception, final ServerHttpRequest request) {
    return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), request);
  }

  /**
   * Handle a start refused because of the execution limit or a shutdown.
   *
   * @param exception the exception
   * @param request the current request
   * @return structured error response
   */
  @ExceptionHandler(ExecutionRejectedException.class)
  public ResponseEntity<ApiResponse<Object>> handleExecutionRejected(
      final ExecutionRejectedException exception, final ServerHttpRequest request) {
    return buildErrorResponse(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage(), request);
  }

  /**
   * Handle requests that the target's current state does not allow.
   *
   * @param exception the exception
   * @param request the current request
   * @return structured 409 response
   */
  @ExceptionHandler(ConflictException.class)
  public ResponseEntity<ApiResponse<Object>> handleConflict(
      final ConflictException exception, final ServerHttpRequest request) {
    return buildErrorResponse(HttpStatus.CONFLICT, exception.getMessage(), request);
  }

  /**
   * Handles a body over the in-memory limit as 413, whichever endpoint read it.
   *
   * @param exception the limit failure
   * @param request the current request
   * @return structured 413 response
   */
  @ExceptionHandler(org.springframework.core.io.buffer.DataBufferLimitException.class)
  public ResponseEntity<ApiResponse<Object>> handleBodyTooLarge(
      final org.springframework.core.io.buffer.DataBufferLimitException exception,
      final ServerHttpRequest request) {
    return buildErrorResponse(HttpStatus.CONTENT_TOO_LARGE, "Request body too large", request);
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
    log.atError().setCause(exception).log("Illegal state");
    return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, null, request);
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
    return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, null, request);
  }

  private ResponseEntity<ApiResponse<Object>> buildErrorResponse(
      final HttpStatus status, final String message, final ServerHttpRequest request) {
    final String path = getRequestPath(request);
    // Server errors never echo exception text; the request ID lets support find the logged cause.
    final String body =
        status.is5xxServerError()
            ? "An unexpected error occurred. Reference: " + requestId(request)
            : message;
    final ApiResponse<Object> errorResponse =
        ApiResponse.error(status.value(), status.getReasonPhrase(), body, path, List.of());
    return ResponseEntity.status(status).body(errorResponse);
  }

  private static String requestId(final ServerHttpRequest request) {
    final HttpHeaders headers = request.getHeaders();
    final String value =
        headers == null ? null : headers.getFirst(RequestTracingFilter.X_REQUEST_ID);
    return value == null ? "unavailable" : value;
  }

  private String getRequestPath(final ServerHttpRequest request) {
    return request.getPath().value();
  }

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
