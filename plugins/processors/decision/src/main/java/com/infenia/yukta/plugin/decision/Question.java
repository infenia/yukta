// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A constrained question for a decision model: pick an option, answer yes or no, or rate. */
public sealed interface Question {

  /**
   * What is asked.
   *
   * @return the instructions
   */
  String instructions();

  /**
   * Pick one option.
   *
   * @param instructions what is asked
   * @param criteria the options, by key, with a description of when each applies
   */
  record Choice(String instructions, Map<String, String> criteria) implements Question {

    /** Keeps the options in their given order. */
    public Choice {
      criteria = Collections.unmodifiableMap(new LinkedHashMap<>(criteria));
    }
  }

  /**
   * Answer yes or no.
   *
   * @param instructions what is asked
   */
  record YesNo(String instructions) implements Question {}

  /**
   * Rate on an ordered scale.
   *
   * @param instructions what is asked
   * @param levels the levels, lowest first; the score runs from 0 to {@code levels.size() - 1}
   */
  record Score(String instructions, List<String> levels) implements Question {

    /** Keeps an immutable copy of the levels. */
    public Score {
      levels = List.copyOf(levels);
    }
  }
}
