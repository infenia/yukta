// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.security;

/**
 * Receives every control command that is applied or not applied, in addition to the execution log
 * (which stays the record behind {@code GET /api/control/audit}). A module provides a bean to
 * export the records, for example to a SIEM or a tamper-evident store. A sink that fails is logged
 * and ignored: it can never stop a command.
 */
@FunctionalInterface
public interface AuditSink {

  /**
   * Take one record.
   *
   * @param record what happened to a control command
   */
  void record(AuditRecord record);
}
