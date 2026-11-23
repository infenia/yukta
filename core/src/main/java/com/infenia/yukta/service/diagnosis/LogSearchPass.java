// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.diagnosis;

import com.infenia.yukta.logging.api.GrepPattern;
import com.infenia.yukta.logging.api.IndexedLog;
import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.plugin.core.LogLevel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/** One {@link RunLogSearcher} search, from the runs read to the answer. Not thread-safe. */
@RequiredArgsConstructor
@SuppressWarnings("PMD.TooManyMethods")
final class LogSearchPass {

  private final RunLogQuery query;
  private final @Nullable GrepPattern pattern;
  private final @Nullable Cursor cursor;
  private final UnaryOperator<String> clean;

  private final List<RunLogSearch.NodeCount> counts = new ArrayList<>();
  private final List<RunLogSearch.Line> lines = new ArrayList<>();
  private long scanned;
  private long matchCount;
  private long bytes;
  private int returnedMatches;
  private boolean truncated;
  private @Nullable Cursor last;

  /**
   * The lines of one run.
   *
   * @param executionId the run
   * @param logs its lines, in order
   */
  /* default */ record Run(String executionId, List<IndexedLog> logs) {}

  /**
   * Where the previous page ended: after line {@code sequence} of run {@code executionId}.
   *
   * @param executionId the run
   * @param sequence the last line returned
   */
  /* default */ record Cursor(String executionId, long sequence) {

    /* default */ static @Nullable Cursor parse(@Nullable final String text) {
      if (text == null || text.isBlank()) {
        return null;
      }
      final int colon = text.lastIndexOf(':');
      try {
        return new Cursor(text.substring(0, colon), Long.parseLong(text.substring(colon + 1)));
      } catch (IndexOutOfBoundsException | NumberFormatException e) {
        throw new IllegalArgumentException("cursor is not a nextCursor of this search", e);
      }
    }

    @Override
    public String toString() {
      return executionId + ":" + sequence;
    }
  }

  /** A line picked for the answer, before the budgets cut the list. */
  private record Pick(
      long sequence, String nodeId, PluginLogEntry entry, String text, boolean hit) {}

  /** What one node holds: its cleaned lines and which of them matched. */
  private record Rated(String[] texts, BitSet hits) {}

  /* default */ RunLogSearch answer(final List<Run> runs) {
    if (cursor != null
        && runs.stream().noneMatch(r -> r.executionId().equals(cursor.executionId()))) {
      throw new IllegalArgumentException("cursor is not a nextCursor of this search");
    }
    boolean beforeCursor = cursor != null;
    for (final Run run : runs) {
      beforeCursor =
          cursor != null && beforeCursor && !run.executionId().equals(cursor.executionId());
      scanned += run.logs().size();
      final List<Pick> picks = searchRun(run);
      if (!beforeCursor && query.mode() == LogSearchMode.LINES) {
        emit(run.executionId(), picks);
      }
    }
    return build(runs.size());
  }

  /** Counts every node of the run and returns the lines LINES mode would show, in order. */
  private List<Pick> searchRun(final Run run) {
    final Map<String, List<IndexedLog>> byNode =
        run.logs().stream()
            .filter(log -> wanted(log.entry()))
            .collect(
                Collectors.groupingBy(
                    log -> log.entry().pluginId(), LinkedHashMap::new, Collectors.toList()));
    final List<Pick> picks = new ArrayList<>();
    byNode.forEach((nodeId, logs) -> picks.addAll(searchNode(run.executionId(), nodeId, logs)));
    picks.sort(Comparator.comparingLong(Pick::sequence));
    return picks;
  }

  private boolean wanted(final PluginLogEntry entry) {
    return (query.nodes().isEmpty() || query.nodes().contains(entry.pluginId()))
        && (query.stream() == null || query.stream() == entry.stream());
  }

  private List<Pick> searchNode(
      final String executionId, final String nodeId, final List<IndexedLog> logs) {
    final Rated rated = rate(executionId, nodeId, logs);
    final List<Pick> picks = new ArrayList<>();
    if (query.mode() == LogSearchMode.LINES && !rated.hits().isEmpty()) {
      final int size = logs.size();
      final BitSet shown = new BitSet(size);
      rated.hits().stream()
          .forEach(i ->
              shown.set(Math.max(0, i - query.context()), Math.min(size, i + query.context() + 1)));
      shown.stream()
          .forEach(i -> {
            final IndexedLog log = logs.get(i);
            picks.add(
                new Pick(
                    log.sequence(), nodeId, log.entry(), rated.texts()[i], rated.hits().get(i)));
          });
    }
    return picks;
  }

  /** Cleans and matches the lines of one node, and records its counts. */
  private Rated rate(final String executionId, final String nodeId, final List<IndexedLog> logs) {
    final int size = logs.size();
    final String[] texts = new String[size];
    final BitSet hits = new BitSet(size);
    long errors = 0;
    long warnings = 0;
    for (int i = 0; i < size; i++) {
      final PluginLogEntry entry = logs.get(i).entry();
      final LogLevel level = levelOf(entry);
      errors += level == LogLevel.ERROR ? 1 : 0;
      warnings += level == LogLevel.WARN ? 1 : 0;
      texts[i] = clean.apply(entry.message() == null ? "" : entry.message());
      hits.set(i, matches(level, texts[i]));
    }
    matchCount += hits.cardinality();
    counts.add(
        new RunLogSearch.NodeCount(
            executionId, nodeId, size, errors, warnings, hits.cardinality()));
    return new Rated(texts, hits);
  }

  private boolean matches(final LogLevel level, final String text) {
    final LogLevel lowest = query.level();
    final GrepPattern grep = pattern;
    return (lowest == null || level.compareTo(lowest) >= 0) && (grep == null || grep.matches(text));
  }

  /** Adds the picks after the cursor to the answer until a budget is spent. */
  private void emit(final String executionId, final List<Pick> picks) {
    for (final Pick pick : picks) {
      if (truncated) {
        return;
      }
      if (afterCursor(executionId, pick)) {
        add(executionId, pick);
      }
    }
  }

  private boolean afterCursor(final String executionId, final Pick pick) {
    return cursor == null
        || !cursor.executionId().equals(executionId)
        || pick.sequence() > cursor.sequence();
  }

  private void add(final String executionId, final Pick pick) {
    final String text = GrepPattern.cut(pick.text());
    final long size = text.getBytes(StandardCharsets.UTF_8).length + 1L;
    final boolean overMatches = pick.hit() && returnedMatches == query.maxMatches();
    if (overMatches || bytes + size > query.maxBytes()) {
      truncated = true;
      return;
    }
    bytes += size;
    returnedMatches += pick.hit() ? 1 : 0;
    last = new Cursor(executionId, pick.sequence());
    lines.add(
        new RunLogSearch.Line(
            executionId,
            pick.nodeId(),
            pick.sequence(),
            streamOf(pick.entry()),
            levelOf(pick.entry()).name(),
            pick.hit(),
            text));
  }

  private RunLogSearch build(final int runs) {
    final List<RunLogSearch.NodeCount> listed =
        counts.stream()
            .filter(c -> query.mode() == LogSearchMode.COUNT || c.matches() > 0)
            .sorted(
                Comparator.comparingLong(RunLogSearch.NodeCount::matches)
                    .thenComparingLong(RunLogSearch.NodeCount::errors)
                    .reversed())
            .toList();
    final List<RunLogSearch.NodeCount> shown =
        listed.subList(0, Math.min(listed.size(), RunLogSearch.MAX_NODES));
    final String next = truncated && last != null ? last.toString() : null;
    return new RunLogSearch(
        query.executionId(),
        query.mode(),
        runs,
        scanned,
        matchCount,
        shown,
        listed.size() - shown.size(),
        lines,
        truncated,
        next,
        hint(shown, next));
  }

  private @Nullable String hint(
      final List<RunLogSearch.NodeCount> shown, @Nullable final String next) {
    if (matchCount == 0) {
      return "No line matched: widen grep or level"
          + (query.includeChildren() ? "." : ", or search sub-runs with includeChildren.");
    }
    if (query.mode() == LogSearchMode.LINES) {
      return next == null
          ? null
          : "More lines matched: pass cursor="
              + next
              + " for the next page, or narrow with nodes, level or grep.";
    }
    final RunLogSearch.NodeCount top = shown.getFirst();
    return "Read the lines with mode=LINES and nodes="
        + top.nodeId()
        + (top.executionId().equals(query.executionId())
            ? ""
            : " (in run " + top.executionId() + ", includeChildren=true)")
        + (query.level() == null && pattern == null ? ", level=ERROR" : "")
        + ".";
  }

  private static LogLevel levelOf(final PluginLogEntry entry) {
    return entry.logLevel() == null ? LogLevel.INFO : entry.logLevel();
  }

  private static String streamOf(final PluginLogEntry entry) {
    return (entry.stream() == null ? LogStream.STDOUT : entry.stream())
        .name()
        .toLowerCase(Locale.ROOT);
  }
}
