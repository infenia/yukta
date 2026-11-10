// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.review;

import com.infenia.yukta.plugin.control.ApprovalNeed;
import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.input.InputRisk;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/** Combines the answers of several supervisors to one command into one verdict. */
@Slf4j
@UtilityClass
final class ReviewVerdicts {

  /** The answers that are not allows, in order; at most one refusal, as the last. */
  /* default */ static ReviewVerdict combine(
      final ControlRequest request, final List<Vote> votes, final boolean byPerson) {
    if (votes.isEmpty()) {
      return ReviewVerdict.ALLOWED;
    }
    final Vote last = votes.getLast();
    if (!last.decision().requiresApproval()) {
      return new ReviewVerdict.Denied(last.supervisorId() + ": " + last.decision().reason());
    }
    if (byPerson && isStop(request)) {
      log.atWarn()
          .addKeyValue("command", request.command().getClass().getSimpleName())
          .addKeyValue("actor", request.actor())
          .log("A supervisor wants a stop approved; a person sent it, so it runs now");
      return ReviewVerdict.ALLOWED;
    }
    return merged(votes);
  }

  /** Several needs for approval as one request. */
  private static ReviewVerdict merged(final List<Vote> votes) {
    InputRisk risk = InputRisk.LOW;
    String route = null;
    Duration ttl = null;
    final List<String> reasons = new ArrayList<>();
    final List<String> askedBy = new ArrayList<>();
    for (final Vote vote : votes) {
      final ApprovalNeed need = Objects.requireNonNull(vote.decision().approval());
      risk = need.risk().atLeast(risk) ? need.risk() : risk;
      route = route == null ? need.routeName() : route;
      if (ttl == null || need.ttl() != null && need.ttl().compareTo(ttl) < 0) {
        ttl = need.ttl();
      }
      reasons.add(
          vote.supervisorId()
              + ": "
              + Objects.requireNonNullElse(vote.decision().reason(), "approval required"));
      askedBy.add(vote.supervisorId());
    }
    return new ReviewVerdict.NeedsApproval(
        new ApprovalNeed(risk, route, ttl), String.join("; ", reasons), askedBy);
  }

  /** Whether the command is a stop, which a dry run or a timeout never holds back. */
  /* default */ static boolean isStop(final ControlRequest request) {
    return request.command() instanceof ExecutionControlCommand.StopWorkflowCommand
        || request.command() instanceof ExecutionControlCommand.StopNodeCommand;
  }

  /**
   * One supervisor's decision, by its id.
   *
   * @param supervisorId the supervisor
   * @param decision what it said
   */
  /* default */ record Vote(String supervisorId, Decision decision) {

    /** A need for approval that this supervisor's approved request already met. */
    /* default */ boolean satisfiedBy(final Set<String> satisfied) {
      return decision.requiresApproval() && satisfied.contains(supervisorId);
    }
  }
}
