// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.testsupport;

import java.util.List;

/** Watches one source of hidden errors for the length of a test. */
interface Probe {

  /** Starts recording. */
  void start();

  /** The lines seen so far, without stopping; safe to call from another thread. */
  List<String> snapshot();

  /** Stops recording and returns one text line per event seen, in order. */
  List<String> stop();
}
