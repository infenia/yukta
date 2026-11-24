// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import java.util.List;
import org.jline.utils.AttributedStyle;

/**
 * The console's palette and the pure, frame-indexed drawing helpers (spinners, shimmer, meters).
 * Colours are truecolor; JLine rounds them down to what the terminal supports.
 */
/* default */ final class ConsoleTheme {

  private static final int WHITE = 255;

  /** What a cell is for; the style is looked up here so a test can name the role of any cell. */
  /* default */ enum Role {
    TEXT(AttributedStyle.DEFAULT),
    DIM(AttributedStyle.DEFAULT.faint()),
    BRAND(rgb(0x2F, 0x6B, 0xFF)),
    ACCENT(rgb(0xE8, 0x5D, 0x0C)),
    SLATE(rgb(0x6A, 0x7A, 0x9B)),
    OK(rgb(0x3F, 0xB9, 0x7C)),
    FAIL(rgb(0xEF, 0x5B, 0x5B)),
    WARN(rgb(0xE6, 0xB4, 0x22)),
    RUN(rgb(0x3D, 0xC2, 0xE0)),
    BOLD(AttributedStyle.BOLD),
    CHIP(AttributedStyle.BOLD.foreground(WHITE, WHITE, WHITE).background(0x2B, 0x33, 0x50)),
    TAB(AttributedStyle.BOLD.foreground(WHITE, WHITE, WHITE).background(0x2F, 0x6B, 0xFF)),
    ZEBRA(AttributedStyle.DEFAULT.background(0x12, 0x18, 0x26)),
    FLASH(AttributedStyle.DEFAULT.foreground(0xE6, 0xB4, 0x22).background(0x3A, 0x2F, 0x10)),
    SHIM(AttributedStyle.BOLD.foreground(WHITE, WHITE, WHITE)),
    G0(rgb(0x30, 0x6B, 0xFF)),
    G1(rgb(0x4A, 0x72, 0xFF)),
    G2(rgb(0x6A, 0x78, 0xF5)),
    G3(rgb(0x8A, 0x78, 0xE0)),
    G4(rgb(0xB0, 0x70, 0x9A)),
    G5(rgb(0xD0, 0x68, 0x6A)),
    G6(rgb(0xE8, 0x60, 0x1F)),
    G7(rgb(0xE9, 0x5D, 0x0C));

    private final transient AttributedStyle attributes;

    private static AttributedStyle rgb(final int r, final int g, final int b) {
      return AttributedStyle.DEFAULT.foreground(r, g, b);
    }

    Role(final AttributedStyle style) {
      this.attributes = style;
    }

    /* default */ AttributedStyle style() {
      return attributes;
    }
  }

  private static final String[] STAR_FRAMES = {"·", "✢", "✳", "✶", "✻", "✽", "✻", "✶", "✳", "✢"};
  private static final String BRAILLE_FRAMES = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏";
  private static final String SPARK = "▁▂▃▄▅▆▇█";
  private static final String EIGHTHS = " ▏▎▍▌▋▊▉█";

  private ConsoleTheme() {}

  /** The brand gradient for character {@code i} of a word at frame {@code f}: it sweeps slowly. */
  /* default */ static Role gradient(final long f, final int i) {
    final int j = Math.floorMod((int) Math.floorDiv(f, 3L) + i, 16);
    return Role.values()[Role.G0.ordinal() + (j < 8 ? j : 15 - j)];
  }

  /** A bright three-character band moving across a phrase of {@code len} characters. */
  /* default */ static Role shimmer(final long f, final int len, final int i) {
    final int head = Math.floorMod((int) f, len + 6);
    return i >= head - 3 && i < head ? Role.SHIM : Role.TEXT;
  }

  /** The Claude-style star spinner. */
  /* default */ static String star(final long f) {
    return STAR_FRAMES[Math.floorMod((int) f, STAR_FRAMES.length)];
  }

  /** The braille spinner for a running row. */
  /* default */ static String braille(final long f) {
    return String.valueOf(BRAILLE_FRAMES.charAt(Math.floorMod((int) f, BRAILLE_FRAMES.length())));
  }

  /** A bar {@code width} cells wide, filled to {@code value / max} in eighths of a cell. */
  /* default */ static String meter(final int value, final int max, final int width) {
    final long eighths = Math.round((double) Math.min(value, max) / max * width * 8);
    final int full = (int) (eighths / 8);
    final StringBuilder bar = new StringBuilder("█".repeat(full));
    if (full < width) {
      bar.append(EIGHTHS.charAt((int) (eighths % 8))).append(" ".repeat(width - full - 1));
    }
    return bar.toString();
  }

  /** One block per sample, scaled to the largest sample. */
  /* default */ static String sparkline(final List<Integer> samples) {
    final int max = samples.stream().mapToInt(Integer::intValue).max().orElse(0);
    final StringBuilder out = new StringBuilder();
    for (final int v : samples) {
      out.append(max == 0 ? SPARK.charAt(0) : SPARK.charAt((int) Math.round(v * 7.0 / max)));
    }
    return out.toString();
  }
}
