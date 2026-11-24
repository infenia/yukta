// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import com.infenia.yukta.console.ConsoleTheme.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;

/** A screen as a grid of characters, each with a {@link Role}; drawing off the edge is ignored. */
/* default */ final class Cells {

  private static final char BLANK = ' ';

  private final int cols;
  private final int height;
  private final char[][] chars;
  private final Role[][] roles;

  /* default */ Cells(final int width, final int height) {
    this.cols = width;
    this.height = height;
    this.chars = new char[height][width];
    this.roles = new Role[height][width];
    for (int r = 0; r < height; r++) {
      java.util.Arrays.fill(chars[r], BLANK);
      java.util.Arrays.fill(roles[r], Role.TEXT);
    }
  }

  /* default */ int width() {
    return cols;
  }

  /* default */ char charAt(final int row, final int col) {
    return chars[row][col];
  }

  /* default */ Role roleAt(final int row, final int col) {
    return roles[row][col];
  }

  /* default */ void put(final int row, final int col, final String text, final Role role) {
    put(row, col, text, i -> role);
  }

  /* default */ void put(
      final int row, final int col, final String text, final IntFunction<Role> role) {
    if (row < 0 || row >= height) {
      return;
    }
    for (int i = 0; i < text.length(); i++) {
      final int x = col + i;
      if (x >= 0 && x < cols) {
        chars[row][x] = text.charAt(i);
        roles[row][x] = role.apply(i);
      }
    }
  }

  /** Gives the blank cells of a row a role (a background for a zebra or flashed row). */
  /* default */ void shadeBlanks(final int row, final Role role) {
    for (int x = 0; x < cols; x++) {
      if (chars[row][x] == BLANK) {
        roles[row][x] = role;
      }
    }
  }

  /** Copies the used cells of rows {@code from..to} of {@code src}, shifted right by {@code dx}. */
  /* default */ void overlay(final Cells src, final int dx, final int from, final int to) {
    for (int r = from; r <= to && r < height; r++) {
      for (int x = 0; x < src.cols; x++) {
        final int nx = x + dx;
        if (nx < cols && (src.chars[r][x] != BLANK || src.roles[r][x] != Role.TEXT)) {
          chars[r][nx] = src.chars[r][x];
          roles[r][nx] = src.roles[r][x];
        }
      }
    }
  }

  /** The grid as terminal lines: styled, or plain text when colour is off. */
  /* default */ List<AttributedString> lines(final boolean color) {
    final List<AttributedString> out = new ArrayList<>(height);
    for (int r = 0; r < height; r++) {
      out.add(line(r, color));
    }
    return out;
  }

  private AttributedString line(final int row, final boolean color) {
    final AttributedStringBuilder line = new AttributedStringBuilder();
    for (int x = 0; x < cols; x++) {
      if (color) {
        line.style(roles[row][x].style());
      }
      line.append(chars[row][x]);
    }
    final AttributedString built = line.toAttributedString();
    // a wide character in a log line takes two columns: cut by columns, not by characters
    return built.columnLength() <= cols ? built : built.columnSubSequence(0, cols);
  }
}
