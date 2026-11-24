// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import java.io.IOException;
import org.jline.utils.NonBlockingReader;

/**
 * Reads the mouse reports the terminal sends once mouse tracking is on. JLine's own reader waits
 * without a timeout for the rest of a report it cannot place (a click's release did that and froze
 * the console), so this one gives up on a cut-short report after {@link #WAIT_MS}.
 */
/* default */ final class ConsoleMouse {

  /** How long to wait for the next character of one report. */
  /* default */ static final long WAIT_MS = 100;

  private static final int MAX_REPORT = 24;
  private static final int WHEEL = 64;
  private static final int MOTION = 32;
  private static final int BUTTON_BITS = 3;
  private static final int X10_OFFSET = 32;
  private static final int CELL_PARTS = 3;

  /** One report: the button bits, the 1-based cell, and whether it is a release. */
  /* default */ record Report(int button, int col, int row, boolean release) {}

  private ConsoleMouse() {}

  /**
   * Reads the rest of an SGR report, {@code b;x;y} then {@code M} or {@code m} (the {@code ESC [ <}
   * is already read); null if it is cut short or malformed.
   */
  /* default */ static Report readSgr(final NonBlockingReader in) throws IOException {
    final StringBuilder digits = new StringBuilder();
    for (int i = 0; i < MAX_REPORT; i++) {
      final int c = in.read(WAIT_MS);
      if (c < 0) {
        return null;
      }
      if (c == 'M' || c == 'm') {
        return parse(digits.toString(), c == 'm');
      }
      digits.append((char) c);
    }
    return null;
  }

  /** Reads the rest of an X10 report, three characters (the {@code ESC [ M} is already read). */
  /* default */ static Report readX10(final NonBlockingReader in) throws IOException {
    final int[] part = new int[CELL_PARTS];
    for (int i = 0; i < CELL_PARTS; i++) {
      part[i] = in.read(WAIT_MS);
      if (part[i] < 0) {
        return null;
      }
    }
    final int button = part[0] - X10_OFFSET;
    return new Report(
        button,
        part[1] - X10_OFFSET,
        part[2] - X10_OFFSET,
        (button & BUTTON_BITS) == BUTTON_BITS && button < WHEEL);
  }

  private static Report parse(final String fields, final boolean release) {
    final String[] p = fields.split(";");
    if (p.length != CELL_PARTS) {
      return null;
    }
    try {
      return new Report(
          Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), release);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * The key a report stands for: the wheel is UP or DOWN, a left click is {@code CLICK:col:row}
   * (cells from 0); anything else (moves, releases, other buttons) is nothing.
   */
  /* default */ static String toKey(final Report r) {
    if (r == null || r.release() || (r.button() & MOTION) != 0) {
      return null;
    }
    if ((r.button() & WHEEL) != 0) {
      return (r.button() & 1) == 0 ? "UP" : "DOWN";
    }
    return (r.button() & BUTTON_BITS) == 0 ? "CLICK:" + (r.col() - 1) + ':' + (r.row() - 1) : null;
  }
}
