// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.testing;

import java.util.List;
import java.util.Map;

/**
 * A test of a workflow: the start payload, what its nodes emit instead of running, and what must
 * hold afterwards.
 *
 * @param input the start payload
 * @param stubs per node id the messages it emits instead of running (processors, triggers)
 * @param expect what must hold at the end
 * @param allow node ids that may run unstubbed; null lets every processor run
 * @param timeoutSeconds how long to wait for the run, 60 when null
 */
public record WorkflowTestSpec(
    Map<String, Object> input,
    Map<String, List<Stub>> stubs,
    Expect expect,
    List<String> allow,
    Integer timeoutSeconds) {

  /**
   * One message a stubbed node emits.
   *
   * @param payload the payload
   * @param port the output port, "default" when null
   */
  public record Stub(Object payload, String port) {}

  /**
   * The assertions.
   *
   * @param status the final run status, or null
   * @param reached nodes that must have started
   * @param notReached nodes that must not have started
   * @param nodes per terminal node id, the payload fields it must have received (dotted paths)
   */
  public record Expect(
      String status,
      List<String> reached,
      List<String> notReached,
      Map<String, Map<String, Object>> nodes) {}
}
