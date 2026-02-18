package com.infenia.jagratha.model;

import java.time.LocalDateTime;
import java.util.List;

/** Represents the progress of a workflow. */
public record WorkflowProgress(
    String sessionId,
    String status,
    List<TaskProgress> tasks,
    LocalDateTime startTime,
    LocalDateTime endTime) {

  /**
   * Compact constructor to ensure immutability.
   *
   * @param sessionId session identifier
   * @param status workflow status
   * @param tasks list of task progress
   * @param startTime start time
   * @param endTime end time
   */
  public WorkflowProgress {
    tasks = List.copyOf(tasks);
  }
}
