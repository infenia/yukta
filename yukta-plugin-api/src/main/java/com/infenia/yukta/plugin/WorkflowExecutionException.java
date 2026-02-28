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
package com.infenia.yukta.plugin;

import java.io.Serial;

/**
 * Standardized exception for errors occurring during workflow execution or plugin processing.
 *
 * <p>Concrete gateways and plugins should use this exception to maintain a technology-independent
 * error contract.
 */
public class WorkflowExecutionException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Constructs a new WorkflowExecutionException with the specified detail message.
   *
   * @param message the detail message
   */
  public WorkflowExecutionException(final String message) {
    super(message);
  }

  /**
   * Constructs a new WorkflowExecutionException with the specified detail message and cause.
   *
   * @param message the detail message
   * @param cause the cause
   */
  public WorkflowExecutionException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
