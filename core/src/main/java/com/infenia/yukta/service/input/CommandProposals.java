// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Finds the control command behind an approval request, so the authoriser can apply the rules of a
 * parked command. Kept by the server: nothing in the request a plugin can write is trusted for
 * this.
 */
@FunctionalInterface
public interface CommandProposals {

  /** Knows no parked command. */
  CommandProposals NONE = _ -> Optional.empty();

  /**
   * The parked command an approval request decides.
   *
   * @param requestId the input request
   * @return the proposal, or empty if the request is not one the server raised for a command
   */
  Optional<Proposal> find(String requestId);

  /**
   * A parked command.
   *
   * @param command the command's class name
   * @param proposer who sent it, or {@code null} when the sender had no identity (no API keys)
   * @param askedBy the supervisors that want it approved
   */
  record Proposal(String command, @Nullable ActorInfo proposer, List<String> askedBy) {

    /**
     * Copies the list.
     *
     * @param command the command's class name
     * @param proposer who sent it, or {@code null}
     * @param askedBy the supervisors that want it approved
     */
    public Proposal {
      askedBy = List.copyOf(askedBy);
    }
  }
}
