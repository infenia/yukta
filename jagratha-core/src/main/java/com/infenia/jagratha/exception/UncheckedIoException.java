package com.infenia.jagratha.exception;

/** Unchecked exception for IO errors. */
public final class UncheckedIoException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /**
   * Constructs a new unchecked IO exception with the specified message and cause.
   *
   * @param message the detail message
   * @param cause the cause
   */
  public UncheckedIoException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
