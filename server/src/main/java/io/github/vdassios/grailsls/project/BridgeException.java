package io.github.vdassios.grailsls.project;

/** Resolving the project failed; the message is the reason shown in {@code grails/status}. */
public final class BridgeException extends Exception {
  public BridgeException(String message) {
    super(message);
  }

  public BridgeException(String message, Throwable cause) {
    super(message, cause);
  }
}
