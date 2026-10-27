// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.api;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.experimental.UtilityClass;

/**
 * Cuts the output of a node into the slice a client asked for: a tail, a head, a page, or the lines
 * that match a pattern with some context. Pure, so every client (REST, CLI, MCP) gets the same
 * answer from the same arguments.
 */
@UtilityClass
public class NodeOutputPager {

  /** Most lines in one answer. */
  public static final int MAX_LIMIT = 500;

  /** Lines in one answer when no limit is given. */
  public static final int DEFAULT_LIMIT = 50;

  /** Longest text kept per line; also the longest a pattern is matched against. */
  public static final int MAX_LINE = 2000;

  /** Longest pattern accepted, against runaway regular expressions. */
  private static final int MAX_PATTERN = 200;

  /** Time one line may spend in the regex engine before the read is refused. */
  private static final long MATCH_BUDGET_NANOS = 50_000_000L;

  /** Most context lines either side of a match. */
  private static final int MAX_CONTEXT = 20;

  /**
   * Slices the lines of a node.
   *
   * @param nodeId the node, echoed in the answer
   * @param stream the stream name, echoed in the answer
   * @param all every line the node wrote in the stream, oldest first
   * @param tail the last N selected lines, or {@code null}
   * @param head the first N selected lines, or {@code null}
   * @param offset the first selected line of a page (0-based), or {@code null} for 0
   * @param limit the page size, or {@code null} for {@link #DEFAULT_LIMIT}; at most {@link
   *     #MAX_LIMIT}
   * @param grep a regular expression the lines must match, or {@code null}
   * @param context lines of context either side of a match, or {@code null} for none
   * @return the slice
   * @throws IllegalArgumentException for a negative number, a bad pattern, or {@code tail} and
   *     {@code head} (or either with {@code offset}) together
   */
  public static NodeOutputSlice slice(
      final String nodeId,
      final String stream,
      final List<String> all,
      final Integer tail,
      final Integer head,
      final Long offset,
      final Integer limit,
      final String grep,
      final Integer context) {
    nonNegative(tail, head, offset, limit, context);
    exclusive(tail, head, offset);
    final int[] picks = select(all, grep, context == null ? 0 : context).stream().toArray();
    final Window window = window(picks.length, tail, head, offset, limit);
    final List<NodeOutputSlice.Line> lines = new ArrayList<>();
    for (int i = window.from(); i < window.end(); i++) {
      final String text = all.get(picks[i]);
      lines.add(new NodeOutputSlice.Line(picks[i] + 1L, cut(text)));
    }
    final long bytes =
        all.stream().mapToLong(line -> line.getBytes(StandardCharsets.UTF_8).length + 1L).sum();
    return new NodeOutputSlice(
        nodeId,
        stream,
        all.size(),
        bytes,
        picks.length,
        lines.size() < picks.length,
        window.next(),
        lines);
  }

  /** Which of the selected lines go in the answer, and where the next page starts. */
  private record Window(int from, int end, Long next) {}

  private static Window window(
      final int count,
      final Integer tail,
      final Integer head,
      final Long offset,
      final Integer limit) {
    final Window window;
    if (tail != null) {
      window = new Window(Math.max(0, count - Math.min(tail, MAX_LIMIT)), count, null);
    } else if (head != null) {
      window = new Window(0, Math.min(count, Math.min(head, MAX_LIMIT)), null);
    } else {
      final int size = limit == null ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
      final int from = (int) Math.min(offset == null ? 0 : offset, count);
      final int end = Math.min(count, from + size);
      window = new Window(from, end, end < count ? (Long) (long) end : null);
    }
    return window;
  }

  private static String cut(final String text) {
    return text.length() > MAX_LINE ? text.substring(0, MAX_LINE) : text;
  }

  private static void nonNegative(final Number... numbers) {
    for (final Number number : numbers) {
      if (number != null && number.longValue() < 0) {
        throw new IllegalArgumentException(
            "tail, head, offset, limit and context must not be negative");
      }
    }
  }

  private static void exclusive(final Integer tail, final Integer head, final Long offset) {
    if (tail != null && head != null) {
      throw new IllegalArgumentException("tail and head cannot be used together");
    }
    if ((tail != null || head != null) && offset != null) {
      throw new IllegalArgumentException("offset cannot be used with tail or head");
    }
  }

  private static BitSet select(final List<String> all, final String grep, final int context) {
    final BitSet selected = new BitSet(all.size());
    if (grep == null || grep.isEmpty()) {
      selected.set(0, all.size());
    } else {
      final Pattern pattern = pattern(grep);
      final int around = Math.min(context, MAX_CONTEXT);
      for (int i = 0; i < all.size(); i++) {
        if (matches(pattern, all.get(i))) {
          selected.set(Math.max(0, i - around), Math.min(all.size(), i + around + 1));
        }
      }
    }
    return selected;
  }

  private static boolean matches(final Pattern pattern, final String line) {
    return pattern.matcher(new Budgeted(cut(line), System.nanoTime() + MATCH_BUDGET_NANOS)).find();
  }

  private static Pattern pattern(final String grep) {
    if (grep.length() > MAX_PATTERN) {
      throw new IllegalArgumentException("grep is longer than " + MAX_PATTERN + " characters");
    }
    try {
      // The pattern is the caller's on purpose (a grep): it is at most 200 characters and each line
      // is matched within a time budget.
      return Pattern.compile(grep); // lgtm[java/regex-injection]
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException(
          "grep is not a valid regular expression: " + e.getDescription(), e);
    }
  }

  /**
   * Char sequence that aborts the regex engine once its deadline has passed (catastrophic
   * backtracking).
   */
  private record Budgeted(String text, long deadline) implements CharSequence {
    @Override
    public char charAt(final int index) {
      if (System.nanoTime() > deadline) {
        throw new IllegalArgumentException(
            "grep is too expensive to evaluate; simplify the pattern");
      }
      return text.charAt(index);
    }

    @Override
    public int length() {
      return text.length();
    }

    @Override
    public CharSequence subSequence(final int start, final int end) {
      return new Budgeted(text.substring(start, end), deadline);
    }

    @Override
    public String toString() {
      return text;
    }
  }
}
