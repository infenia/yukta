// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input.route;

import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.service.input.EligibleSupervisors;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * Works out whom a tier reaches for one request: {@code supervisor:*} and {@code supervisor:<id>}
 * match the attached supervisors that watch the request's node, and {@code supervisor:above} the
 * ones whose scope strictly covers a reference supervisor. Every other selector is kept as it is.
 *
 * @since 0.1.0-alpha.4
 */
@UtilityClass
public class TierTargets {

  /** The selector for supervisors that watch more than a reference supervisor. */
  public static final String ABOVE = "supervisor:above";

  private static final String ANY = "supervisor:*";

  private static final String PREFIX = "supervisor:";

  /**
   * The selectors a request in this tier carries: {@code supervisor:above} is replaced by one
   * {@code supervisor:<id>} for each supervisor above the references; a tier that asks for it and
   * finds none keeps no such selector.
   *
   * @param request the request, which says which node it is about
   * @param tier the tier
   * @param bound the attached supervisors
   * @param references the scopes the supervisors must strictly cover
   * @return the selectors, without duplicates
   */
  public static List<String> selectors(
      final InputRequest request,
      final Tier tier,
      final EligibleSupervisors bound,
      final List<SupervisorScope> references) {
    final Set<String> out = new LinkedHashSet<>();
    for (final String selector : tier.responders()) {
      if (ABOVE.equals(selector)) {
        bound.bound().stream()
            .filter(c -> watches(c, request) && bound.mayReceive(c, request))
            .filter(c -> references.stream().anyMatch(c.scope()::strictlyCovers))
            .forEach(c -> out.add(PREFIX + c.id()));
      } else {
        out.add(selector);
      }
    }
    return new ArrayList<>(out);
  }

  /**
   * The attached supervisors the selectors reach for this request.
   *
   * @param request the request
   * @param selectors the selectors of the tier, after {@link #selectors}
   * @param bound the attached supervisors
   * @return the supervisors that watch the request's node and are named or matched
   */
  public static List<EligibleSupervisors.Candidate> supervisors(
      final InputRequest request, final List<String> selectors, final EligibleSupervisors bound) {
    final boolean any = selectors.contains(ANY);
    return bound.bound().stream()
        .filter(c -> watches(c, request) && bound.mayReceive(c, request))
        .filter(c -> any || selectors.contains(PREFIX + c.id()))
        .toList();
  }

  /**
   * Whether the selectors reach someone who is not a supervisor.
   *
   * @param selectors the selectors of the tier
   * @return true if a person or an external responder is named
   */
  public static boolean namesOthers(final List<String> selectors) {
    return selectors.stream().anyMatch(s -> !s.startsWith(PREFIX));
  }

  private static boolean watches(
      final EligibleSupervisors.Candidate candidate, final InputRequest request) {
    return candidate.scope().matches(request.sessionId(), request.workflowId(), request.nodeId());
  }
}
