// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Map;
import java.util.Set;

/** Rules for reactive code: nothing may park an event-loop thread. */
public final class ReactiveRules {

  private static final Set<String> BLOCKING_OPERATORS =
      Set.of("block", "blockFirst", "blockLast", "toIterable", "toStream");

  private ReactiveRules() {}

  private static boolean blocks(final JavaMethodCall call) {
    final JavaClass owner = call.getTargetOwner();
    return BLOCKING_OPERATORS.contains(call.getName())
            && (owner.isAssignableTo("reactor.core.publisher.Mono")
                || owner.isAssignableTo("reactor.core.publisher.Flux"))
        || "sleep".equals(call.getName()) && owner.isEquivalentTo(Thread.class);
  }

  /**
   * No class may block a reactive stream or sleep, except the listed ones.
   *
   * @param exemptions class name (nested classes count as their outer class) to the reason it is
   *     allowed to block; keep this short, every entry is a place that parks a thread
   * @return the rule
   */
  public static ArchRule noBlockingCalls(final Map<String, String> exemptions) {
    return noClasses()
        .that(
            DescribedPredicate.describe(
                "are not exempt " + exemptions.keySet(),
                (JavaClass c) -> !exemptions.containsKey(c.getName().split("\\$")[0])))
        .should()
        .callMethodWhere(
            DescribedPredicate.describe("it blocks a Mono/Flux or sleeps", ReactiveRules::blocks))
        .as("no class blocks a reactive stream or sleeps, except the listed exemptions")
        .because("the engine runs on a few event-loop threads; a parked thread stalls every run");
  }
}
