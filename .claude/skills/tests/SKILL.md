---
name: tests
description: "Use when the user asks to generate, create, or write unit tests for code. Analyzes the target code, produces a structured test case list for review, then generates test code. Supports Java (JUnit 5, Mockito, AssertJ)."
allowed-tools: Read, Write, Glob, Grep, Bash, AskUserQuestion
context: fork
---

# Generate Tests Skill

You will analyze code and generate high-quality unit tests for a given target.

**Target to test:** $ARGUMENTS

## Quality Standards

- Take your time to analyze the code thoroughly before generating test cases.
- Quality is more important than speed — read all relevant source files and rules carefully.
- Do not skip any step in the workflow below. Every step exists for a reason.
- Do not take shortcuts with test data — read the actual classes to use correct constructors and fields.

---

## Instructions

### Step 1: Read Rules and Analyze Context

1. **Read the relevant rules** from `./rules/tests/` based on code type (see Rules Reference below)
2. **Read the target** source file/class/method
3. **Read dependencies**: Follow imports to read DTOs, entities, enums, custom exceptions, and other types referenced by the target (as specified in `code-context-analysis` rule)
4. **Check for existing tests**: Search for `{ClassName}Test` or `{ClassName}Tests` in the test directory (as specified in `existing-test-awareness` rule)
   - If found, read fully — you will add missing tests to it, do not create a new file
   - If not found, scan 2-3 neighboring test classes to learn project conventions

### Step 2: Generate Test Cases

1. Analyze ALL code branches, including:
   - Success paths
   - Error/exception paths
   - Validation logic
   - Private/protected methods called by the target
   - Security annotations (if present)
2. Apply the INCLUDE/EXCLUDE rules strictly
3. Output the list of test cases in the format below — do NOT generate test code yet

#### Test Case Output Format

```
## Test Cases for {ClassName}.{methodName}

### 1. {testMethodName}
- **Given:** {preconditions/input state}
- **When:** {action being tested}
- **Then:** {expected outcome}
- **Code branch:** {which code path this covers}

### 2. {testMethodName}
...
```

#### Naming Convention
Test method name format: `{testedMethod}_{givenState}_{expectedOutcome}`

Examples:
- `calculateTotal_validProducts_returnsSum`
- `calculateTotal_emptyList_throwsIllegalArgumentException`
- `getUser_unauthorized_returns401`

### Step 3: Ask for User Review

After outputting test cases, use the **AskUserQuestion tool** to ask the user:
```
Question: "Test cases are ready. Proceed with generating test code?"
Header: "Next step"
Options:
  - Label: "Yes, generate tests" / Description: "Proceed to generate test files from the test cases above"
  - Label: "No, let me review first" / Description: "Stop here so I can review and adjust the test cases"
```

- If user selects "Yes", proceed to Step 4
- If user selects "No", STOP and wait for further instructions

### Step 4: Generate Test Code

1. Determine code type and apply the matching rules:
   - **Controller** → Apply `controller-test-rules.md` (use `@WebMvcTest`, MockMvc patterns)
   - **Service / Domain logic** → Apply `domain-service-rules.md` (use `@ExtendWith(MockitoExtension.class)`, Mockito patterns)
   - **Repository / Messaging / Other types** → Apply `domain-service-rules.md` as baseline; inform the user that type-specific rules are not yet available
   - **All Java code** → Always apply `java-test-template.md`, `argument-matching.md`, `json-serialization.md` regardless of code type
2. If an existing test class was found in Step 1, add new test methods to it (do not create a duplicate file)
3. Generate tests following all rules and the test cases from Step 2
4. Create or update the test file using the Write tool

### Step 5: Verify Compilation and Execution

1. Run compilation and fix any issues (max 5 attempts — see `compilation-verification.md`)
2. Run the generated test class to verify all tests pass (see `test-execution-verification.md`)
3. Fix any failing tests — do NOT modify production code
4. If a test cannot be fixed after 3 attempts, remove it and inform the user

---

## Troubleshooting

### Target file not found
If the specified target does not exist, inform the user with the exact path you searched and ask for clarification.

### Unsupported language
If the target code is in a language without specific rules (not Java), apply only the general rules and inform the user that language-specific conventions may need manual review.

### Compilation keeps failing
If compilation fails after 5 attempts:
1. Stop and show the user the remaining errors
2. Suggest possible causes (missing dependencies, incompatible versions)
3. Ask the user to resolve the build issue before continuing

### Tests fail due to production code behavior
If tests fail because the production code behaves differently than expected:
1. Do NOT modify production code
2. Fix the test to match actual behavior
3. If the behavior seems like a bug, add a comment: `// NOTE: current behavior may be a bug — {description}`

---

## Example

```
User says: "/generate-tests src/main/java/com/example/service/OrderService.java"

Step 1: Agent reads rules, reads OrderService.java, reads OrderRequest.java,
        Order.java, OrderRepository.java (dependencies), checks for
        existing OrderServiceTest.java

Step 2: Agent outputs 7 test cases covering:
        - createOrder success path
        - createOrder with invalid request (validation)
        - processPayment success
        - processPayment failure
        - calculateTotal with products
        - calculateTotal with empty list
        - cancelOrder for non-existent order

Step 3: Agent asks user to review. User says "Yes, generate tests".

Step 4: Agent generates OrderServiceTest.java with @ExtendWith(MockitoExtension.class),
        mocked repository and payment service, 7 test methods.

Step 5: Agent runs `mvn test -Dtest=OrderServiceTest -q`, all tests pass.

Result: Complete test file delivered with 7 passing tests.
```

---

## Rules Reference

**CRITICAL: You MUST read and apply all relevant rules from the `./rules/tests/` directory.**

> **Maintenance note:** General rules in `./rules/tests/general/` are shared with the `generate-test-cases` skill (which has copies in `rules/general/`). When updating rules, keep both locations in sync.

### General Rules (Always Apply)
- [INCLUDE/EXCLUDE criteria](./rules/general/test-case-generation-strategy.md)
- [Test naming format](./rules/general/naming-conventions.md)
- [Core testing principles (Given-When-Then, actual/expected)](./rules/general/general-principles.md)
- [Detect language and framework](./rules/general/technology-stack-detection.md)
- [Clarity, Completeness, Conciseness, Resilience](./rules/general/what-makes-good-test.md)
- [Use helpers and builders for test data](./rules/general/cleanly-create-test-data.md)
- [Effects follow causes immediately](./rules/general/keep-cause-effect-clear.md)
- [KISS > DRY, avoid logic in assertions](./rules/general/no-logic-in-tests.md)
- [One scenario per test](./rules/general/keep-tests-focused.md)
- [Separate tests for behaviors](./rules/general/test-behaviors-not-methods.md)
- [Only verify relevant mock arguments](./rules/general/verify-relevant-arguments-only.md)
- [Test public APIs over private methods](./rules/general/prefer-public-apis.md)
- [Check for existing tests, match project conventions](./rules/general/existing-test-awareness.md)
- [Read dependencies before writing tests](./rules/general/code-context-analysis.md)

### Java Unit Tests
- [Basic template, FORBIDDEN annotations](./rules/java/unit/java-test-template.md)
- [Use explicit JSON literals](./rules/java/unit/json-serialization.md)
- [Use ArgumentCaptor, not any()](./rules/java/unit/argument-matching.md)
- [OutputCaptureExtension for logs](./rules/java/unit/logging-rules.md)
- [Mockito patterns for services](./rules/java/unit/domain-service-rules.md)
- [@WebFluxTest patterns for controllers](./rules/java/unit/controller-test-rules.md)
- [Java Records, @With, and Lombok standards](./rules/java/unit/lombok-records-usage.md)
- [Logging with @Slf4j](./rules/java/unit/logging-standards.md)

### Post-Generation
- [Verify compilation](./rules/post-generation/compilation-verification.md)
- [Verify tests pass](./rules/post-generation/test-execution-verification.md)