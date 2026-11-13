// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.service.session.SessionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * How much of the server nobody supervises: the workflows nobody owns. Shared by REST ({@code GET
 * /api/supervision/summary}), MCP and the CLI.
 */
@Component
@RequiredArgsConstructor
public class SupervisionSummary {

  /** Most unowned workflows a summary names; {@code unowned} still counts them all. */
  public static final int MAX_NAMED = 100;

  private final OwnershipMap ownershipMap;
  private final SessionService sessions;

  /**
   * The summary.
   *
   * @param unowned how many workflows nobody owns (the default supervisor carries them)
   * @param unownedWorkflows the first of them, at most {@link #MAX_NAMED}
   */
  public record Summary(int unowned, List<OwnershipMap.WorkflowRef> unownedWorkflows) {

    /** Keeps an unmodifiable copy of the workflows. */
    public Summary {
      unownedWorkflows = List.copyOf(unownedWorkflows);
    }
  }

  /**
   * Counts the workflows nobody owns, internal sessions left out.
   *
   * @return the summary
   */
  public Mono<Summary> summarize() {
    return ownershipMap
        .unowned(sessions.getSessionIds(false))
        .collectList()
        .map(
            found ->
                new Summary(found.size(), found.subList(0, Math.min(MAX_NAMED, found.size()))));
  }
}
