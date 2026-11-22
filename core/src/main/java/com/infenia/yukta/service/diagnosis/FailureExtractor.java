// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.diagnosis;

import java.util.List;

/**
 * Recognises failures in the output of one node: failed tests, compile errors, lint violations and
 * failed tasks of one family of tools. Every Spring bean of this type is asked about every node of
 * a failed run; one that does not know the output returns nothing.
 */
@FunctionalInterface
public interface FailureExtractor {

  /**
   * The failures in a node's output.
   *
   * @param log the node's lines
   * @return what it recognised, in the order the lines were written; empty if nothing
   */
  List<Finding> extract(NodeLog log);
}
