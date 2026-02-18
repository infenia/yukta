package com.infenia.jagratha.model;

import java.time.LocalDateTime;

/** Represents the progress of a single task within a workflow. */
public record TaskProgress(
    String taskName,
    String module,
    String status,
    LocalDateTime startTime,
    LocalDateTime endTime) {}
