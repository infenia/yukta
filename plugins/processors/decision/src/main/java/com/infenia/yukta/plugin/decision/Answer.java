// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import java.util.Map;

/** A decision model's answer to a {@link Question}. */
public sealed interface Answer {

  /**
   * The answer as plain data, for message metadata.
   *
   * @return a map describing the answer
   */
  Map<String, Object> toMap();

  /**
   * The chosen option.
   *
   * @param choice the key of the option
   * @param probabilities how likely each option is; calibrated for System One, estimated for chat
   *     models
   */
  record Choice(String choice, Map<String, Double> probabilities) implements Answer {

    /** Keeps an immutable copy of the probabilities. */
    public Choice {
      probabilities = Map.copyOf(probabilities);
    }

    /**
     * How likely the chosen option is.
     *
     * @return the probability, 0 if unknown
     */
    public double probability() {
      return probabilities.getOrDefault(choice, 0.0);
    }

    @Override
    public Map<String, Object> toMap() {
      return Map.of("choice", choice, "probability", probability(), "probabilities", probabilities);
    }
  }

  /**
   * A yes or no.
   *
   * @param probability how likely the answer is yes, from 0 to 1
   */
  record YesNo(double probability) implements Answer {

    @Override
    public Map<String, Object> toMap() {
      return Map.of("probability", probability);
    }
  }

  /**
   * A rating.
   *
   * @param score the probability-weighted level index, from 0 to the number of levels minus 1
   */
  record Score(double score) implements Answer {

    @Override
    public Map<String, Object> toMap() {
      return Map.of("score", score);
    }
  }
}
