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

import lombok.Getter;

/** Exception thrown when a requested resource is not found. */
@Getter
public class ResourceNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The type of the resource that was not found. */
  private final String resourceType;

  /** The identifier of the resource that was not found. */
  private final String resourceId;

  /**
   * Constructor for resource not found exception.
   *
   * @param resourceType the type of resource (e.g., "Session", "Workflow")
   * @param resourceId the identifier of the missing resource
   */
  public ResourceNotFoundException(final String resourceType, final String resourceId) {
    super(
        String.format(
            "%s not found: '%s'", resourceType, resourceId != null ? resourceId : "unknown"));
    this.resourceType = resourceType;
    this.resourceId = resourceId;
  }

  /**
   * Constructor with custom message.
   *
   * @param message the error message
   */
  public ResourceNotFoundException(final String message) {
    super(message);
    this.resourceType = null;
    this.resourceId = null;
  }
}
