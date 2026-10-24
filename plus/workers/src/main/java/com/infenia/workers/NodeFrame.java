// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One message on the stream of a node attempt. The control plane sends {@code START}, the node's
 * input as {@code MESSAGE} frames and {@code COMPLETE}; the worker answers with the output as
 * {@code MESSAGE} frames, {@code HEARTBEAT}s while it works, and {@code COMPLETE} or {@code ERROR}.
 *
 * @param kind what the frame is
 * @param executionId the run (START only)
 * @param nodeId the node (START only)
 * @param pluginType the plugin to run (START only)
 * @param config the node configuration (START only)
 * @param message a message as plain JSON values (MESSAGE only)
 * @param errorClass the class name of the failure (ERROR only)
 * @param errorMessage the text of the failure (ERROR only)
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "a frame is a transport value that nobody changes after it is built")
public record NodeFrame(
    Kind kind,
    @Nullable String executionId,
    @Nullable String nodeId,
    @Nullable String pluginType,
    @Nullable Map<String, Object> config,
    @Nullable Map<String, Object> message,
    @Nullable String errorClass,
    @Nullable String errorMessage) {

  /** Kind of frame. */
  public enum Kind {
    /** Control plane to worker: the first frame; says what to run. */
    START,
    /** Either way: one message. */
    MESSAGE,
    /** Worker to control plane: the node is still working. */
    HEARTBEAT,
    /** Either way: no more messages. */
    COMPLETE,
    /** Worker to control plane: the attempt failed. */
    ERROR
  }

  /**
   * The frame that starts an attempt.
   *
   * @param executionId the run
   * @param nodeId the node
   * @param pluginType the plugin to run
   * @param config the node configuration
   * @return the frame
   */
  public static NodeFrame start(
      final String executionId,
      final String nodeId,
      final String pluginType,
      final Map<String, Object> config) {
    return new NodeFrame(Kind.START, executionId, nodeId, pluginType, config, null, null, null);
  }

  /**
   * A message.
   *
   * @param message the message as plain JSON values
   * @return the frame
   */
  public static NodeFrame message(final Map<String, Object> message) {
    return new NodeFrame(Kind.MESSAGE, null, null, null, null, message, null, null);
  }

  /**
   * A sign of life.
   *
   * @return the frame
   */
  public static NodeFrame heartbeat() {
    return new NodeFrame(Kind.HEARTBEAT, null, null, null, null, null, null, null);
  }

  /**
   * The end of the messages.
   *
   * @return the frame
   */
  public static NodeFrame complete() {
    return new NodeFrame(Kind.COMPLETE, null, null, null, null, null, null, null);
  }

  /**
   * A failed attempt.
   *
   * @param errorClass the class name of the failure
   * @param errorMessage the text of the failure
   * @return the frame
   */
  public static NodeFrame error(final String errorClass, final String errorMessage) {
    return new NodeFrame(Kind.ERROR, null, null, null, null, null, errorClass, errorMessage);
  }
}
