package com.infenia.jagratha.model;

/**
 * Response object for task execution.
 *
 * @param status the task status (e.g., SUCCESS, FAILURE)
 * @param output the task output or error message
 */
public record TaskResponse(String status, String output) {}
