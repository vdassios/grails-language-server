package io.github.vdassios.grailsls;

import java.util.Objects;

/** Payload of {@code grails/status}. */
public final class StatusParams {
  public static final String RESOLVING = "resolving";
  public static final String INDEXING = "indexing";
  public static final String READY = "ready";
  public static final String FAILED = "failed";

  /** One of {@link #RESOLVING}, {@link #INDEXING}, {@link #READY}, {@link #FAILED}. */
  public String state;
  /** Detail; for {@link #FAILED}, the reason. */
  public String message;

  public StatusParams() {}

  public StatusParams(String state, String message) {
    this.state = state;
    this.message = message;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof StatusParams && Objects.equals(state, ((StatusParams) o).state) && Objects.equals(message, ((StatusParams) o).message);
  }

  @Override
  public int hashCode() {
    return Objects.hash(state, message);
  }

  @Override
  public String toString() {
    return state + (message == null ? "" : ": " + message);
  }
}
