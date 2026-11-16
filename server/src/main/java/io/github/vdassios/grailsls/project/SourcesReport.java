package io.github.vdassios.grailsls.project;

/** Result of {@code grails/downloadSources}: the libraries of the compile classpaths, counted by what became of their sources. */
public final class SourcesReport {
  /** Libraries with a sources jar on disk now, fetched by this run or earlier. */
  public final int available;
  /** Libraries that publish no sources jar. */
  public final int unavailable;
  /** Libraries whose sources could not be fetched (offline, repository error). */
  public final int failed;
  /** Why nothing ran or something failed, else null. */
  public final String message;

  public SourcesReport(int available, int unavailable, int failed, String message) {
    this.available = available;
    this.unavailable = unavailable;
    this.failed = failed;
    this.message = message;
  }
}
