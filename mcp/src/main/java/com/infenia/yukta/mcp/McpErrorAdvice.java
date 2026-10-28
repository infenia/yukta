// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import io.modelcontextprotocol.spec.McpError;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Answers a request the MCP SDK refuses (an unknown method, a bad message) with its JSON-RPC error
 * and a 400, instead of the generic 500 of the web layer. Only the code and message of the error
 * are sent, never the exception.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class McpErrorAdvice {

  /**
   * Maps an SDK error to a JSON-RPC error body.
   *
   * @param error the SDK error
   * @return the JSON-RPC error with status 400
   */
  @ExceptionHandler(McpError.class)
  public ResponseEntity<Map<String, Object>> handle(final McpError error) {
    final var rpc = error.getJsonRpcError();
    final Map<String, Object> body =
        Map.of(
            "jsonrpc",
            "2.0",
            "id",
            "",
            "error",
            Map.of(
                "code",
                rpc == null ? -32600 : rpc.code(),
                "message",
                rpc == null ? "Invalid request" : rpc.message()));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body);
  }
}
