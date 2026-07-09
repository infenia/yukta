// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
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
