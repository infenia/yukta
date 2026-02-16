package io.jagratha.jagratha.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Request object for file operations.
 *
 * @param path the file path
 * @param content the file content
 */
public record FileRequest(@NotBlank String path, @NotBlank String content) {}
