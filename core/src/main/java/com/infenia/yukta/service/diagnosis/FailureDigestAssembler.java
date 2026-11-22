// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.diagnosis;

import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.service.orchestrator.stream.NodeStreams;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/** Puts one {@link FailureDigest} together from the logs of the runs it covers. Not thread-safe. */
@RequiredArgsConstructor
final class FailureDigestAssembler {

  /** Rough size, in bytes, the findings and failed nodes of a digest stay within. */
  /* default */ static final int BUDGET = 4096;

  /** Most findings before the budget is applied. */
  private static final int MAX_FINDINGS = 20;

  /** ERROR lines taken from a failed node no extractor understood. */
  private static final int FALLBACK_LINES = 3;

  /** Lines shown before a finding when reading around it. */
  private static final int LEAD = 5;

  /** Most next reads. */
  private static final int MAX_NEXT = 4;

  /** Bytes a finding or node costs besides its texts (field names, punctuation, ids). */
  private static final int OVERHEAD = 120;

  private final List<FailureExtractor> extractors;
  private final UnaryOperator<String> clean;

  /**
   * One run with every line it wrote.
   *
   * @param progress the run
   * @param entries its lines, in order
   */
  /* default */ record RunLog(WorkflowProgress progress, List<PluginLogEntry> entries) {}

  /* default */ FailureDigest assemble(final WorkflowProgress root, final List<RunLog> runs) {
    final List<FailureDigest.FailedNode> failed = new ArrayList<>();
    final List<Finding> findings = new ArrayList<>();
    final List<FailureDigest.NextRead> searches = new ArrayList<>();
    for (final RunLog run : runs) {
      final Map<String, List<PluginLogEntry>> byNode =
          run.entries().stream()
              .collect(
                  Collectors.groupingBy(
                      PluginLogEntry::pluginId, LinkedHashMap::new, Collectors.toList()));
      for (final TaskProgress task : run.progress().tasks()) {
        if (FailureDigestService.failed(task)) {
          final List<PluginLogEntry> lines = byNode.getOrDefault(task.nodeId(), List.of());
          failed.add(failedNode(run.progress().executionId(), task, lines));
          final List<Finding> found = findings(run.progress().executionId(), task.nodeId(), lines);
          findings.addAll(found);
          if (found.isEmpty() || found.stream().allMatch(f -> f.kind() == Finding.Kind.ERROR)) {
            searches.add(search(run.progress().executionId(), task.nodeId()));
          }
        }
      }
    }
    final List<Finding> kept = withinBudget(prioritise(dedupe(findings)), failed);
    return new FailureDigest(
        root.executionId(),
        root.workflowId(),
        root.status().name(),
        root.status().isTerminal(),
        runs.size(),
        failed.subList(0, Math.min(failed.size(), FailureDigest.MAX_NODES)),
        kept,
        findings.size() - kept.size(),
        next(root, kept, searches, failed.isEmpty()));
  }

  private static FailureDigest.FailedNode failedNode(
      final String executionId, final TaskProgress task, final List<PluginLogEntry> lines) {
    final Object error = task.metadata().get(NodeStreams.ERROR_KEY);
    return new FailureDigest.FailedNode(
        executionId,
        task.nodeId(),
        task.module(),
        task.status().name(),
        error == null ? null : error.toString(),
        lines.size(),
        lines.stream().filter(e -> e.logLevel() == LogLevel.ERROR).count());
  }

  /** What the extractors recognise in a node; its first ERROR lines if they recognise nothing. */
  private List<Finding> findings(
      final String executionId, final String nodeId, final List<PluginLogEntry> entries) {
    final NodeLog log =
        new NodeLog(
            executionId,
            nodeId,
            entries.stream().map(FailureDigestService::text).map(clean).toList());
    final List<Finding> found = new ArrayList<>();
    extractors.forEach(extractor -> found.addAll(extractor.extract(log)));
    if (found.isEmpty()) {
      for (int i = 0; i < entries.size() && found.size() < FALLBACK_LINES; i++) {
        if (entries.get(i).logLevel() == LogLevel.ERROR) {
          found.add(log.finding(Finding.Kind.ERROR, i, null, null, log.lines().get(i)));
        }
      }
    }
    return found;
  }

  /**
   * One finding per failure: tools print some twice (a summary and a detail, a task line and its
   * cause). The one with the more telling message is kept.
   */
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  private static List<Finding> dedupe(final List<Finding> findings) {
    final Map<List<Object>, Finding> unique = new LinkedHashMap<>();
    for (final Finding finding : findings) {
      final List<Object> key =
          Arrays.asList(
              finding.executionId(),
              finding.nodeId(),
              finding.kind(),
              finding.name(),
              finding.location(),
              // a plain error line has neither: only the same line is the same failure
              finding.name() == null && finding.location() == null ? finding.line() : 0L);
      unique.merge(key, finding, (kept, other) -> telling(other) && !telling(kept) ? other : kept);
    }
    return List.copyOf(unique.values());
  }

  private static boolean telling(final Finding finding) {
    return !Set.of("FAIL", "FAILED", "").contains(finding.message());
  }

  /** Tests and compile errors first, then lint, tasks and plain errors; log order within each. */
  private static List<Finding> prioritise(final List<Finding> findings) {
    return findings.stream()
        .sorted(Comparator.comparing(Finding::kind))
        .limit(MAX_FINDINGS)
        .toList();
  }

  /** As many findings as fit in the budget next to the failed nodes. */
  private static List<Finding> withinBudget(
      final List<Finding> findings, final List<FailureDigest.FailedNode> failed) {
    long used =
        failed.stream()
            .limit(FailureDigest.MAX_NODES)
            .mapToLong(n -> OVERHEAD + n.nodeId().length() + length(n.error()))
            .sum();
    final List<Finding> kept = new ArrayList<>();
    for (final Finding finding : findings) {
      used +=
          OVERHEAD
              + length(finding.name())
              + length(finding.location())
              + finding.message().length();
      if (used > BUDGET) {
        break;
      }
      kept.add(finding);
    }
    return kept;
  }

  private static int length(@Nullable final String text) {
    return text == null ? 0 : text.length();
  }

  /** Read around the first finding of each node, then search the nodes nothing was found in. */
  private static List<FailureDigest.NextRead> next(
      final WorkflowProgress root,
      final List<Finding> findings,
      final List<FailureDigest.NextRead> searches,
      final boolean noFailedNode) {
    final List<FailureDigest.NextRead> next = new ArrayList<>();
    findings.stream()
        .collect(
            Collectors.toMap(
                f -> f.executionId() + "/" + f.nodeId(), f -> f, (a, b) -> a, LinkedHashMap::new))
        .values()
        .forEach(f ->
            next.add(
                new FailureDigest.NextRead(
                    FailureDigest.NextRead.Kind.NODE_OUTPUT,
                    f.executionId(),
                    f.nodeId(),
                    Math.max(0, f.line() - 1 - LEAD),
                    null,
                    "the lines around " + Objects.requireNonNullElse(f.name(), f.message()))));
    next.addAll(searches);
    if (noFailedNode && !root.status().isTerminal()) {
      next.add(
          new FailureDigest.NextRead(
              FailureDigest.NextRead.Kind.SEARCH,
              root.executionId(),
              null,
              null,
              "WARN",
              "the run is still going: what it warned about so far"));
    } else if (noFailedNode && root.status() != TaskStatus.SUCCESS) {
      next.add(search(root.executionId(), null));
    }
    return next.subList(0, Math.min(next.size(), MAX_NEXT));
  }

  private static FailureDigest.NextRead search(
      final String executionId, @Nullable final String nodeId) {
    return new FailureDigest.NextRead(
        FailureDigest.NextRead.Kind.SEARCH,
        executionId,
        nodeId,
        null,
        "ERROR",
        nodeId == null ? "the error lines of the run" : "the error lines of the node");
  }
}
