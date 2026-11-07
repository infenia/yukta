// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

/** A supervisor that wants to know the id of the attachment that runs it. */
@FunctionalInterface
public interface AttachmentAware {

  /**
   * Tells the supervisor which attachment it runs under; called before it starts.
   *
   * @param attachmentId the attachment id
   */
  void bindTo(String attachmentId);
}
