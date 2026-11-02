// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Which runs a supervisor watches, may veto and may act on: all of them, those of one session,
 * those of one workflow, or a section (some nodes) of one workflow.
 *
 * <p>A command that belongs to no run (such as {@code SetLimitsCommand}) has no session or
 * workflow, so only a {@link Global} supervisor reviews or sends it. An item that belongs to a
 * whole run (a workflow event, a workflow command) names no node, so a {@link Section} supervisor
 * does not see, review or send it.
 */
public sealed interface SupervisorScope {

  /**
   * Separator between the ids and the selectors of a section. Session and workflow ids cannot
   * contain a {@code /} ({@code @SessionId}, {@code @WorkflowId}), so the text before the first
   * {@code //} is always the ids. They may contain a {@code #}, which is why that is not used.
   */
  String SECTION_SEPARATOR = "//";

  /** Message for a scope text that cannot be read. */
  String BAD_SCOPE =
      "Scope must be global, session:<id>, workflow:<id>, workflow:<sessionId>/<workflowId>, or a"
          + " section workflow:[<sessionId>/]<workflowId>//<selector>,<selector> where a selector"
          + " is a node id or <prefix>.* (1 to 50 selectors, each at most 256 characters, no"
          + " comma)";

  /** What kind of scope this is. */
  enum Kind {
    /** Every run. */
    GLOBAL,
    /** The runs of one session. */
    SESSION,
    /** The runs of one workflow. */
    WORKFLOW,
    /** Some nodes of the runs of one workflow. */
    SECTION
  }

  /**
   * The kind of scope.
   *
   * @return the kind
   */
  Kind kind();

  /**
   * The session this scope is limited to.
   *
   * @return the session id, or {@code null} when the scope is not limited to a session
   */
  @Nullable String sessionId();

  /**
   * The workflow this scope is limited to.
   *
   * @return the workflow id, or {@code null} when the scope is not limited to a workflow
   */
  @Nullable String workflowId();

  /**
   * Tells whether a whole run, or an item of it that names no node, falls within the scope.
   *
   * @param runSessionId the session of the run, or {@code null} when unknown or not a run
   * @param runWorkflowId the workflow of the run, or {@code null} when unknown or not a run
   * @return true if a supervisor with this scope watches the run as a whole
   */
  boolean matches(@Nullable String runSessionId, @Nullable String runWorkflowId);

  /**
   * Tells whether an item of a run (an event, a command) falls within the scope.
   *
   * @param runSessionId the session of the run, or {@code null} when unknown or not a run
   * @param runWorkflowId the workflow of the run, or {@code null} when unknown or not a run
   * @param nodeId the node the item is about, or {@code null} when it is about the whole run
   * @return true if a supervisor with this scope watches the item
   */
  default boolean matches(
      final @Nullable String runSessionId,
      final @Nullable String runWorkflowId,
      final @Nullable String nodeId) {
    return matches(runSessionId, runWorkflowId);
  }

  /**
   * Tells whether this scope contains another: every run and node the other matches, this one
   * matches too. A scope covers itself.
   *
   * @param other the other scope
   * @return true if this scope covers the other
   */
  boolean covers(SupervisorScope other);

  /**
   * Tells whether this scope covers another and is wider than it: {@code covers(other)} and not
   * {@code other.covers(this)}. It is a strict partial order (never true of a scope itself, never
   * true both ways), so a chain of strictly covering scopes has no cycle.
   *
   * @param other the other scope
   * @return true if this scope is strictly above the other
   */
  default boolean strictlyCovers(final SupervisorScope other) {
    return covers(other) && !other.covers(this);
  }

  /**
   * The text form of the scope, as {@link #parse} reads it: {@code global}, {@code session:<id>},
   * {@code workflow:<id>}, {@code workflow:<sessionId>/<workflowId>} or, for a section, one of the
   * workflow forms followed by {@code //} and the selectors separated by commas.
   *
   * @return the text
   */
  default String key() {
    return switch (this) {
      case Global _ -> "global";
      case Session session -> "session:" + session.sessionId();
      case Workflow workflow -> workflowKey(workflow.sessionId(), workflow.workflowId());
      case Section section ->
          workflowKey(section.sessionId(), section.workflowId())
              + SECTION_SEPARATOR
              + String.join(",", section.nodes());
    };
  }

  private static String workflowKey(final @Nullable String sessionId, final String workflowId) {
    return sessionId == null
        ? "workflow:" + workflowId
        : "workflow:" + sessionId + "/" + workflowId;
  }

  /**
   * Reads the text form written by {@link #key()}.
   *
   * @param text {@code global}, {@code session:<id>}, {@code workflow:<id>}, {@code
   *     workflow:<sessionId>/<workflowId>}, or one of the workflow forms followed by {@code
   *     //<selector>,<selector>}
   * @return the scope
   * @throws IllegalArgumentException if the text is none of these, an id is empty, or a section has
   *     no selector, an empty or too long one, or too many
   */
  static SupervisorScope parse(final String text) {
    final String[] parts = (text == null ? "" : text.strip()).split(":", 2);
    final String id = parts.length == 2 ? parts[1] : "";
    return switch (parts[0]) {
      case "global" -> new Global();
      case "session" -> new Session(requireId(id));
      case "workflow" -> workflowOrSection(requireId(id));
      default -> throw new IllegalArgumentException(BAD_SCOPE);
    };
  }

  private static String requireId(final String id) {
    if (id.isBlank()) {
      throw new IllegalArgumentException(BAD_SCOPE);
    }
    return id;
  }

  private static SupervisorScope workflowOrSection(final String text) {
    final int separator = text.indexOf(SECTION_SEPARATOR);
    if (separator < 0) {
      return workflowScope(text);
    }
    final Workflow workflow = workflowScope(requireId(text.substring(0, separator)));
    final Set<String> nodes = new LinkedHashSet<>();
    for (final String selector : text.substring(separator + 2).split(",", -1)) {
      nodes.add(selector.strip());
    }
    try {
      return new Section(workflow.workflowId(), workflow.sessionId(), nodes);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(BAD_SCOPE + ": " + e.getMessage(), e);
    }
  }

  private static Workflow workflowScope(final String id) {
    final int slash = id.indexOf('/');
    if (slash < 0) {
      return new Workflow(id, null);
    }
    return new Workflow(requireId(id.substring(slash + 1)), requireId(id.substring(0, slash)));
  }

  /** Every run, and the commands that belong to no run. */
  record Global() implements SupervisorScope {

    @Override
    public Kind kind() {
      return Kind.GLOBAL;
    }

    @Override
    public @Nullable String sessionId() {
      return null;
    }

    @Override
    public @Nullable String workflowId() {
      return null;
    }

    @Override
    public boolean matches(
        final @Nullable String runSessionId, final @Nullable String runWorkflowId) {
      return true;
    }

    @Override
    public boolean covers(final SupervisorScope other) {
      return true;
    }
  }

  /**
   * The runs of one session.
   *
   * @param sessionId the session
   */
  record Session(String sessionId) implements SupervisorScope {

    /** Rejects a missing session id. */
    public Session {
      Objects.requireNonNull(sessionId, "sessionId");
    }

    @Override
    public Kind kind() {
      return Kind.SESSION;
    }

    @Override
    public @Nullable String workflowId() {
      return null;
    }

    @Override
    public boolean matches(
        final @Nullable String runSessionId, final @Nullable String runWorkflowId) {
      return sessionId.equals(runSessionId);
    }

    /** Covers itself and the workflows and sections of its session. */
    @Override
    public boolean covers(final SupervisorScope other) {
      return sessionId.equals(other.sessionId());
    }
  }

  /**
   * The runs of one workflow.
   *
   * @param workflowId the workflow
   * @param sessionId the session that owns it; {@code null} for the workflow of that id in any
   *     session
   */
  record Workflow(String workflowId, @Nullable String sessionId) implements SupervisorScope {

    /** Rejects a missing workflow id. */
    public Workflow {
      Objects.requireNonNull(workflowId, "workflowId");
    }

    @Override
    public Kind kind() {
      return Kind.WORKFLOW;
    }

    @Override
    public boolean matches(
        final @Nullable String runSessionId, final @Nullable String runWorkflowId) {
      return workflowId.equals(runWorkflowId)
          && (sessionId == null || sessionId.equals(runSessionId));
    }

    /** Covers the scopes of its workflow (in its session, or in any when it names none). */
    @Override
    public boolean covers(final SupervisorScope other) {
      return workflowId.equals(other.workflowId())
          && (sessionId == null || sessionId.equals(other.sessionId()));
    }
  }

  /**
   * Some nodes of the runs of one workflow. A node belongs to the section when a selector takes it:
   * a selector is a node id (exact match) or {@code <prefix>.*}, which takes every node whose id
   * starts with {@code <prefix>.}. An item that names no node (a workflow event or command) is not
   * the section's.
   *
   * @param workflowId the workflow
   * @param sessionId the session that owns it; {@code null} for the workflow of that id in any
   *     session
   * @param nodes the selectors, 1 to {@value #MAX_SELECTORS}, each non-blank, without a comma and
   *     at most {@value #MAX_SELECTOR_LENGTH} characters
   */
  record Section(String workflowId, @Nullable String sessionId, Set<String> nodes)
      implements SupervisorScope {

    /** Most selectors in a section. */
    public static final int MAX_SELECTORS = 50;

    /** Longest selector: the longest node id. */
    public static final int MAX_SELECTOR_LENGTH = 256;

    /** Suffix that makes a selector a prefix. */
    private static final String PREFIX_SUFFIX = ".*";

    /**
     * Checks the parts and keeps an unmodifiable copy of the selectors, in their order.
     *
     * @param workflowId the workflow
     * @param sessionId the session, or {@code null}
     * @param nodes the selectors
     */
    public Section {
      Objects.requireNonNull(workflowId, "workflowId");
      Objects.requireNonNull(nodes, "nodes");
      if (nodes.isEmpty() || nodes.size() > MAX_SELECTORS) {
        throw new IllegalArgumentException(
            "A section needs 1 to " + MAX_SELECTORS + " node selectors");
      }
      nodes.forEach(Section::checkSelector);
      nodes = Collections.unmodifiableSet(new LinkedHashSet<>(nodes));
    }

    private static void checkSelector(final @Nullable String selector) {
      if (selector == null
          || selector.isBlank()
          || selector.indexOf(',') >= 0
          || selector.length() > MAX_SELECTOR_LENGTH
          || PREFIX_SUFFIX.equals(selector)) {
        throw new IllegalArgumentException(
            "A node selector must be a node id or <prefix>.*, non-blank, without a comma and at"
                + " most "
                + MAX_SELECTOR_LENGTH
                + " characters: "
                + selector);
      }
    }

    @Override
    public Kind kind() {
      return Kind.SECTION;
    }

    /** A whole run, or an item that names no node, is not the section's. */
    @Override
    public boolean matches(
        final @Nullable String runSessionId, final @Nullable String runWorkflowId) {
      return false;
    }

    @Override
    public boolean matches(
        final @Nullable String runSessionId,
        final @Nullable String runWorkflowId,
        final @Nullable String nodeId) {
      return nodeId != null
          && workflowId.equals(runWorkflowId)
          && (sessionId == null || sessionId.equals(runSessionId))
          && nodes.stream().anyMatch(selector -> selects(selector, nodeId));
    }

    /** Covers a section of its workflow whose every selector one of its own covers. */
    @Override
    public boolean covers(final SupervisorScope other) {
      return other instanceof Section section
          && workflowId.equals(section.workflowId())
          && (sessionId == null || sessionId.equals(section.sessionId()))
          && section.nodes().stream()
              .allMatch(theirs -> nodes.stream().anyMatch(mine -> coversSelector(mine, theirs)));
    }

    /** Whether a selector takes a node. */
    private static boolean selects(final String selector, final String nodeId) {
      return isPrefix(selector) ? nodeId.startsWith(prefixOf(selector)) : selector.equals(nodeId);
    }

    /** Whether every node one selector takes, another takes too. */
    private static boolean coversSelector(final String mine, final String theirs) {
      return isPrefix(theirs)
          ? isPrefix(mine) && prefixOf(theirs).startsWith(prefixOf(mine))
          : selects(mine, theirs);
    }

    private static boolean isPrefix(final String selector) {
      return selector.endsWith(PREFIX_SUFFIX);
    }

    /** What a node id starts with to be taken: the prefix with its dot, {@code build.}. */
    private static String prefixOf(final String selector) {
      return selector.substring(0, selector.length() - 1);
    }
  }
}
