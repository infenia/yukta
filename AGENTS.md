# Agent guide

Read `CLAUDE.md` first: it is the repository guide for every agent (commands, tests, conventions). Build, test and lint go through `yk` (`yk gradle`, `yk tool`, `yk check`), never `./gradlew`, `go` or `pnpm` directly.

Code navigation uses the `codebase-memory` MCP server (see `.claude/CLAUDE.md`): search the graph for symbols and callers before grep or reading files.

<skills_system priority="1">

## Available Skills

<!-- SKILLS_TABLE_START -->
<usage>
When users ask you to perform tasks, check if any of the available skills below can help complete the task more effectively. Skills provide specialized capabilities and domain knowledge.

How to use skills:
- Invoke: `npx openskills read <skill-name>` (run in your shell)
  - For multiple: `npx openskills read skill-one,skill-two`
- The skill content will load with detailed instructions on how to complete the task
- Base directory provided in output for resolving bundled resources (references/, scripts/, assets/)

Usage notes:
- Only use skills listed in <available_skills> below
- Do not invoke a skill that is already loaded in your context
- Each skill invocation is stateless
</usage>

<available_skills>

<skill>
<name>generate-test-cases</name>
<description>"Use when the user asks to analyze code for test coverage, list what test cases are needed, or review testing strategy — WITHOUT generating actual test code."</description>
<location>project</location>
</skill>

<skill>
<name>generate-tests</name>
<description>"Use when the user asks to generate, create, or write unit tests for code. Analyzes the target code, produces a structured test case list for review, then generates test code. Supports Java (JUnit 5, Mockito, AssertJ)."</description>
<location>project</location>
</skill>

</available_skills>
<!-- SKILLS_TABLE_END -->

</skills_system>
