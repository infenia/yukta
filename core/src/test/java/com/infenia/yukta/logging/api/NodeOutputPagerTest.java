// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for NodeOutputPager on a 10,000 line output. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class NodeOutputPagerTest {

  /** Ten thousand lines: "line 1" ... "line 10000". */
  private static final List<String> BIG =
      IntStream.rangeClosed(1, 10_000).mapToObj(i -> "line " + i).toList();

  private static NodeOutputSlice slice(
      final List<String> all,
      final Integer tail,
      final Integer head,
      final Long offset,
      final Integer limit,
      final String grep,
      final Integer context) {
    return NodeOutputPager.slice("n", "all", all, tail, head, offset, limit, grep, context);
  }

  @Test
  void pages_ofFifty_walkTheWholeOutput() {
    Long offset = 0L;
    int pages = 0;
    long seen = 0;
    while (offset != null) {
      final NodeOutputSlice page = slice(BIG, null, null, offset, 50, null, null);
      assertThat(page.lines()).hasSizeLessThanOrEqualTo(50);
      assertThat(page.lines().getFirst().number()).isEqualTo(offset + 1);
      seen += page.lines().size();
      offset = page.nextOffset();
      pages++;
    }
    assertThat(seen).isEqualTo(10_000);
    assertThat(pages).isEqualTo(200);
  }

  @Test
  void firstPage_reportsTotalsAndTruncation() {
    final NodeOutputSlice page = slice(BIG, null, null, null, null, null, null);

    assertThat(page.totalLines()).isEqualTo(10_000);
    assertThat(page.matchedLines()).isEqualTo(10_000);
    assertThat(page.truncated()).isTrue();
    assertThat(page.lines()).hasSize(NodeOutputPager.DEFAULT_LIMIT);
    assertThat(page.nextOffset()).isEqualTo(50L);
    assertThat(page.totalBytes()).isPositive();
  }

  @Test
  void limit_isCappedAtFiveHundred() {
    assertThat(slice(BIG, null, null, 0L, 100_000, null, null).lines()).hasSize(500);
  }

  @Test
  void tail_returnsTheLastLinesWithTheirNumbers() {
    final NodeOutputSlice out = slice(BIG, 3, null, null, null, null, null);

    assertThat(out.lines())
        .extracting(NodeOutputSlice.Line::number, NodeOutputSlice.Line::text)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(9998L, "line 9998"),
            org.assertj.core.groups.Tuple.tuple(9999L, "line 9999"),
            org.assertj.core.groups.Tuple.tuple(10_000L, "line 10000"));
    assertThat(out.nextOffset()).isNull();
  }

  @Test
  void head_returnsTheFirstLines() {
    assertThat(slice(BIG, null, 2, null, null, null, null).lines())
        .extracting(NodeOutputSlice.Line::number)
        .containsExactly(1L, 2L);
  }

  @Test
  void grep_returnsOnlyMatchingLinesWithTheirNumbers() {
    final NodeOutputSlice out = slice(BIG, null, null, null, null, "^line 5\\d{3}$", null);

    assertThat(out.matchedLines()).isEqualTo(1000);
    assertThat(out.lines()).allSatisfy(l -> assertThat(l.text()).startsWith("line 5"));
    assertThat(out.lines().getFirst().number()).isEqualTo(5000);
  }

  @Test
  void grep_withContext_addsNeighboursOnceAndMergesOverlaps() {
    final List<String> all = List.of("a", "b", "ERR", "c", "ERR", "d", "e", "f", "g", "ERR");

    final NodeOutputSlice out = slice(all, null, null, null, null, "ERR", 1);

    assertThat(out.lines())
        .extracting(NodeOutputSlice.Line::number)
        .containsExactly(2L, 3L, 4L, 5L, 6L, 9L, 10L);
  }

  @Test
  void grep_tail_takesTheLastMatches() {
    assertThat(slice(BIG, 2, null, null, null, "9$", null).lines())
        .extracting(NodeOutputSlice.Line::text)
        .containsExactly("line 9989", "line 9999");
  }

  @Test
  void offsetPastTheEnd_isEmptyNotAnError() {
    final NodeOutputSlice out = slice(List.of("a"), null, null, 99L, null, null, null);

    assertThat(out.lines()).isEmpty();
    assertThat(out.nextOffset()).isNull();
  }

  @Test
  void emptyOutput_isEmpty() {
    final NodeOutputSlice out = slice(List.of(), null, null, null, null, null, null);

    assertThat(out.totalLines()).isZero();
    assertThat(out.lines()).isEmpty();
  }

  @Test
  void longLines_areCut() {
    final NodeOutputSlice out =
        slice(List.of("x".repeat(5000)), null, null, null, null, null, null);

    assertThat(out.lines().getFirst().text()).hasSize(NodeOutputPager.MAX_LINE);
  }

  @Test
  void badArguments_areRejected() {
    assertThatThrownBy(() -> slice(BIG, -1, null, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> slice(BIG, 1, 1, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> slice(BIG, 1, null, 5L, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> slice(BIG, null, 1, 5L, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> slice(BIG, null, null, null, null, "(", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("regular expression");
    assertThatThrownBy(() -> slice(BIG, null, null, null, null, "x".repeat(201), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void headAndTail_areCappedAtTheLimit() {
    assertThat(slice(BIG, 9999, null, null, null, null, null).lines()).hasSize(500);
    assertThat(slice(BIG, null, 9999, null, null, null, null).lines()).hasSize(500);
  }

  @Test
  void expensiveGrep_isRefusedNotHung() {
    final List<String> trap = List.of("a".repeat(40) + "!");

    assertThatThrownBy(() -> slice(trap, null, null, null, null, "(a*)*\\1b", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too expensive");
  }

  @Test
  void budgetedLine_behavesLikeItsText() {
    assertThat(slice(List.of("hello world"), null, null, null, null, "o w", null).lines())
        .hasSize(1);
    assertThat(slice(List.of("hello world"), null, null, null, null, "^hello (wor)", null).lines())
        .hasSize(1);
  }

  @Test
  void emptyGrep_meansNoFilter() {
    assertThat(slice(BIG, null, null, null, null, "", null).matchedLines()).isEqualTo(10_000);
  }
}
