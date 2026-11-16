// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

/**
 * A subscriber of the live stream fell more than the buffer behind. It reconnects with the last
 * sequence number it saw; the store has the history.
 */
public class AgentStreamLaggedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param buffer the size of the buffer the subscriber overran
   */
  public AgentStreamLaggedException(final int buffer) {
    super("The subscriber fell more than " + buffer + " changes behind; reconnect to catch up");
  }
}
