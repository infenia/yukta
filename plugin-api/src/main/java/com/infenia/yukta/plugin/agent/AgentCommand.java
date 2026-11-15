// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agent;

import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What a client sends to a running agent, as the plugin receives it. Core has already checked who
 * may send it and that the conversation has the capability.
 *
 * @since 0.1.0-alpha.4
 */
public sealed interface AgentCommand {

  /**
   * A message to the agent.
   *
   * @param text the text
   * @param interrupt true to stop the current turn first
   * @param actor who sent it
   */
  record Message(String text, boolean interrupt, String actor) implements AgentCommand {

    /**
     * Checks the parts.
     *
     * @param text the text
     * @param interrupt whether to interrupt first
     * @param actor who sent it
     */
    public Message {
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(actor, "actor");
    }
  }

  /**
   * Stop the current turn without ending the conversation.
   *
   * @param actor who sent it
   */
  record Interrupt(String actor) implements AgentCommand {

    /**
     * Checks the actor.
     *
     * @param actor who sent it
     */
    public Interrupt {
      Objects.requireNonNull(actor, "actor");
    }
  }

  /**
   * End the conversation.
   *
   * @param outcome the outcome to record, or null for the plugin to choose
   * @param reason why, or null
   * @param actor who sent it
   */
  record End(@Nullable String outcome, @Nullable String reason, String actor)
      implements AgentCommand {

    /**
     * Checks the parts.
     *
     * @param outcome the outcome, or null
     * @param reason the reason, or null
     * @param actor who sent it
     */
    public End {
      if (outcome != null) {
        AgentOutcome.require(outcome);
      }
      Objects.requireNonNull(actor, "actor");
    }
  }

  /**
   * Bytes typed into the terminal of the agent.
   *
   * @param data the bytes; copied on the way in and out
   * @param actor who typed them
   */
  @SuppressWarnings("ArrayRecordComponent") // copied on the way in and out
  record TerminalInput(byte[] data, String actor) implements AgentCommand {

    /**
     * Copies the bytes.
     *
     * @param data the bytes
     * @param actor who typed them
     */
    public TerminalInput {
      data = Objects.requireNonNull(data, "data").clone();
      Objects.requireNonNull(actor, "actor");
    }

    @Override
    public byte[] data() {
      return data.clone();
    }

    @Override
    public boolean equals(final Object other) {
      return other instanceof final TerminalInput input
          && Arrays.equals(data, input.data)
          && actor.equals(input.actor);
    }

    @Override
    public int hashCode() {
      return 31 * Arrays.hashCode(data) + actor.hashCode();
    }

    @Override
    public String toString() {
      return "TerminalInput[" + data.length + " bytes, actor=" + actor + "]";
    }
  }

  /**
   * The size of the terminal changed.
   *
   * @param cols the width in characters
   * @param rows the height in lines
   */
  record TerminalResize(int cols, int rows) implements AgentCommand {

    /** Largest width or height accepted. */
    public static final int MAX_SIZE = 1000;

    /**
     * Checks the size.
     *
     * @param cols the width, from 1 to {@link #MAX_SIZE}
     * @param rows the height, from 1 to {@link #MAX_SIZE}
     */
    public TerminalResize {
      if (cols < 1 || cols > MAX_SIZE || rows < 1 || rows > MAX_SIZE) {
        throw new IllegalArgumentException(
            "terminal size must be from 1 to " + MAX_SIZE + " in both directions");
      }
    }
  }
}
