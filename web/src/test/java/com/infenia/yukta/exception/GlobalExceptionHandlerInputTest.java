// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.input.InputForbiddenException;
import com.infenia.yukta.plugin.input.InputRejectedException;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

/** A refused answer is 403 with the rule, and an answer that does not fit is 422. */
@NoArgsConstructor
class GlobalExceptionHandlerInputTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void forbiddenSenderIs403WithTheRuleInTheMessage() {
    final var response =
        handler.handleInputForbidden(
            new InputForbiddenException("r1", "risk", "Rule 'risk': supervisor 'x' may not answer"),
            MockServerHttpRequest.get("/api/x").build());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody().message()).contains("Rule 'risk'");
  }

  @Test
  void anAnswerThatDoesNotFitIs422() {
    final var response =
        handler.handleInputRejected(
            new InputRejectedException("r1", "The decision is not one of the options"),
            MockServerHttpRequest.get("/api/x").build());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(response.getBody().message()).contains("not one of the options");
  }
}
