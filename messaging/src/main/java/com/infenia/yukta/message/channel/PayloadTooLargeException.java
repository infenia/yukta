// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

/** A message payload is larger than {@code yukta.messaging.max-payload-bytes}. */
public class PayloadTooLargeException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param nodeId the node that sent or was to receive the message
   * @param size the payload size in bytes
   * @param limit the limit in bytes
   */
  public PayloadTooLargeException(final String nodeId, final long size, final long limit) {
    super(
        "Payload at node '%s' is %d bytes, over the limit of %d (yukta.messaging.max-payload-bytes)"
            .formatted(nodeId, size, limit));
  }
}
