// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.console.ConsoleTheme.Role;
import org.junit.jupiter.api.Test;

class CellsTest {

  @Test
  void putOffTheEdgeIsIgnored() {
    final var g = new Cells(4, 2);
    g.put(-1, 0, "x", Role.TEXT);
    g.put(2, 0, "x", Role.TEXT);
    g.put(0, -1, "ab", Role.TEXT);
    g.put(0, 3, "cd", Role.BOLD);
    assertThat(g.lines(false).get(0).toString()).isEqualTo("b  c");
    assertThat(g.charAt(0, 0)).isEqualTo('b');
    assertThat(g.charAt(0, 3)).isEqualTo('c');
    assertThat(g.roleAt(0, 3)).isEqualTo(Role.BOLD);
    assertThat(g.width()).isEqualTo(4);
  }

  @Test
  void putWithRolePerCharacter() {
    final var g = new Cells(5, 1);
    g.put(0, 1, "abc", i -> i == 0 ? Role.BRAND : Role.ACCENT);
    assertThat(g.roleAt(0, 1)).isEqualTo(Role.BRAND);
    assertThat(g.roleAt(0, 2)).isEqualTo(Role.ACCENT);
    assertThat(g.charAt(0, 3)).isEqualTo('c');
  }

  @Test
  void shadeBlanksOnlyTouchesBlankCells() {
    final var g = new Cells(4, 1);
    g.put(0, 0, "a", Role.TEXT);
    g.shadeBlanks(0, Role.ZEBRA);
    assertThat(g.roleAt(0, 0)).isEqualTo(Role.TEXT);
    assertThat(g.roleAt(0, 1)).isEqualTo(Role.ZEBRA);
  }

  @Test
  void overlayShiftsUsedCellsOnly() {
    final var src = new Cells(3, 2);
    src.put(0, 0, "x", Role.BOLD);
    src.put(1, 2, "y", Role.TEXT);
    final var dst = new Cells(4, 2);
    dst.overlay(src, 1, 0, 1);
    assertThat(dst.charAt(0, 1)).isEqualTo('x');
    assertThat(dst.roleAt(0, 1)).isEqualTo(Role.BOLD);
    assertThat(dst.charAt(1, 3)).isEqualTo('y');
    assertThat(dst.charAt(0, 0)).isEqualTo(' ');
  }

  @Test
  void overlayStopsAtTheRowLimit() {
    final var src = new Cells(2, 3);
    src.put(2, 0, "z", Role.TEXT);
    final var dst = new Cells(2, 2);
    dst.overlay(src, 0, 0, 2);
    assertThat(dst.charAt(0, 0)).isEqualTo(' ');
  }

  @Test
  void linesAreStyledOnlyWhenColourIsOn() {
    final var g = new Cells(3, 2);
    g.put(0, 0, "hi", Role.BOLD);
    final var plain = g.lines(false);
    final var styled = g.lines(true);
    assertThat(plain).hasSize(2);
    assertThat(plain.get(0).toString()).isEqualTo("hi ");
    assertThat(styled.get(0).toString()).isEqualTo("hi ");
    assertThat(styled.get(0).styleAt(0)).isNotEqualTo(plain.get(0).styleAt(0));
  }

  @Test
  void wideCharactersAreCutByColumns() {
    final var g = new Cells(3, 1);
    g.put(0, 0, "日本語", Role.TEXT);
    final var line = g.lines(false).get(0);
    assertThat(line.columnLength()).isLessThanOrEqualTo(3);
  }
}
