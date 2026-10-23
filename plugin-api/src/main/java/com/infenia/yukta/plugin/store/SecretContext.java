// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.store;

/**
 * Who is asking for a secret. Any field may be null when it is not known.
 *
 * @param tenant the tenant the run belongs to, if the deployment has tenants
 * @param sessionId the session the run belongs to
 * @param workflowId the workflow being run
 * @param nodeId the node that resolves the value
 */
public record SecretContext(String tenant, String sessionId, String workflowId, String nodeId) {

  /** No caller known: only global secrets apply. */
  public static final SecretContext NONE = new SecretContext(null, null, null, null);
}
