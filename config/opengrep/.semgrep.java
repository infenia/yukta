// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Test cases for yukta-spel-standard-evaluation-context: `ruleid` lines must be flagged, `ok` lines must not.
class SpelCases {
  void cases(org.springframework.expression.Expression expression, org.springframework.expression.ExpressionParser parser, String text) {
    // ruleid: yukta-spel-standard-evaluation-context
    var a = new StandardEvaluationContext(text);
    // ruleid: yukta-spel-standard-evaluation-context
    expression.getValue();
    // ruleid: yukta-spel-standard-evaluation-context
    expression.getValue(String.class);
    // ruleid: yukta-spel-standard-evaluation-context
    expression.getValue(text);
    // ok: yukta-spel-standard-evaluation-context
    expression.getValue(SimpleEvaluationContext.forReadOnlyDataBinding().build());
    // ok: yukta-spel-standard-evaluation-context
    expression.getValue(SimpleEvaluationContext.forReadOnlyDataBinding().build(), text);
  }

  // ruleid: yukta-spel-standard-evaluation-context
  static class Relaxed extends StandardEvaluationContext {}
}

class GraalCases {
  void cases(Object b, java.util.function.Predicate<String> p) {
    // ruleid: yukta-graalvm-host-access-all
    var a = HostAccess.ALL;
    // ok: yukta-graalvm-host-access-all
    var e = HostAccess.EXPLICIT;

    // ruleid: yukta-graalvm-allow-host-class-lookup
    b.allowHostClassLookup(c -> true);
    // ruleid: yukta-graalvm-allow-host-class-lookup
    b.allowHostClassLookup(c -> { return true; });
    // ruleid: yukta-graalvm-allow-host-class-lookup
    b.allowHostClassLookup(Predicates.alwaysTrue());
    // ok: yukta-graalvm-allow-host-class-lookup
    b.allowHostClassLookup(c -> c.startsWith("java.util."));

    // ruleid: yukta-graalvm-context-allow-all
    b.allowAllAccess(true);
    // ruleid: yukta-graalvm-context-allow-all
    b.allowCreateProcess(true);
    // ruleid: yukta-graalvm-context-allow-all
    b.allowNativeAccess(true);
    // ruleid: yukta-graalvm-context-allow-all
    b.allowIO(IOAccess.ALL);
    // ok: yukta-graalvm-context-allow-all
    b.allowAllAccess(false);
    // ok: yukta-graalvm-context-allow-all
    b.allowIO(IOAccess.NONE);
  }
}
