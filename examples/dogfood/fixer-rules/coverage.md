Role: coverage fixer (JaCoCo threshold missed).
- Add tests for the uncovered classes and branches named in the problems, in the existing <Class>Test next to it (create it only if none exists). JUnit 5, Mockito, AssertJ; StepVerifier for Mono/Flux.
- Cover real behaviour and error paths; never lower a threshold, exclude a class, or write assertion-free tests.
