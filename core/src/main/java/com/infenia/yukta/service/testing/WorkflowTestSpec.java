// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.testing;

import java.util.Collections;
import java.util.LinkedHashMap;
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
// null means "not given" for these fields, which is why they are kept null
@SuppressWarnings("PMD.NullAssignment")
public record WorkflowTestSpec(
    Map<String, Object> input,
    Map<String, List<Stub>> stubs,
    Expect expect,
    List<String> allow,
    Integer timeoutSeconds) {

  /** Copies the collections, so the spec cannot be changed through them. */
  public WorkflowTestSpec {
    input = input == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(input));
    stubs = stubs == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(stubs));
    allow = allow == null ? null : List.copyOf(allow);
  }

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
  @SuppressWarnings("PMD.NullAssignment")
  public record Expect(
      String status,
      List<String> reached,
      List<String> notReached,
      Map<String, Map<String, Object>> nodes) {

    /** Copies the collections, so the assertions cannot be changed through them. */
    public Expect {
      reached = reached == null ? null : List.copyOf(reached);
      notReached = notReached == null ? null : List.copyOf(notReached);
      nodes = nodes == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
    }
  }
}
