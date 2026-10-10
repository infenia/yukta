// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service;

/**
 * A session, workflow, execution or node that was asked for does not exist. It extends {@link
 * IllegalArgumentException} so existing callers that treat it as a bad argument keep working, while
 * the web layer can tell it apart from other invalid input and answer 404.
 */
public class NotFoundException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what was not found, safe to show to API clients
   */
  public NotFoundException(final String message) {
    super(message);
  }
}
