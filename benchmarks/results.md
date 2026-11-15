# Benchmark results

Measurements of the server on the five projects of [corpus.md](corpus.md), against the C6 budget: first diagnostics within 3 s of a warm start, completion within 300 ms, resident memory under 1.5 GB with ten files open. The budget was set for the private project the server was developed against; these projects show where other and larger projects stand. That project's numbers are not published here.

- Server: commit `d28b216`, plus the memory test's soft-reference check (commit `test: tell a leaked loader from one only held by soft references`, right after it).
- Date: 2026-11-15. Machine: MacBook Air, Apple M5 (fanless), 16 GB RAM, macOS 26.5.
- Test JVM heap 4096 MB (a quarter of RAM, what `java -jar` gets). Each project runs on its own build JDK (corpus.md): cherokeedictionary 11.0.28, mdm-core, mod-agreements and rundeck 17.0.19, hoist-core 21.0.13 (Amazon Corretto).
- Times are milliseconds, given as median / p90 / max. Heap is MB after GC; RSS is the process's resident set in MB.

## Verdict against the budget

| Budget | Meets it | Misses it |
|---|---|---|
| First diagnostics within 3 s (warm start, one process per file) | cherokeedictionary: every file | hoist-core: medians about 3 s, 16 of 20 controllers and 9 of 20 services within 3 s; mod-agreements: 7 and 4 of 20; mdm-core: 0 and 3 of 20; rundeck: 5 to 10 of 20 controllers (medians 10.5 to 14 s), 14 of 20 services |
| Completion within 300 ms, compiled document | all five (max 4 ms) | |
| Completion within 300 ms, after a typed dot (the editor waits for the compile of the copy) | cherokeedictionary (p90 136) | hoist-core (p90 608), mod-agreements (599), mdm-core (2372), rundeck (4597) |
| RSS under 1.5 GB with ten files | cherokeedictionary 442, mdm-core 1087, mod-agreements 1069, hoist-core 1017 | rundeck 2874 |
| Loader released on close | cherokeedictionary, mdm-core, mod-agreements; hoist-core once soft references are cleared | rundeck on JDK 17: a JDK limit (see Known problems); released once soft references are cleared when the server runs on JDK 21 |

Errors on valid code: 0 on cherokeedictionary, mod-agreements and hoist-core; mdm-core 4 and rundeck 30 (sweep) and 1 (static test), all attributed below. Grails members (artefact traits and GORM methods) are found on every controller, domain class, taglib and `@Transactional` service of every project.

## Measurement caveats

- The full runs (all four perf tests in one JVM) ran on battery (59 % falling to about 15 %). A rerun of hoist-core on mains power gave timings within about ±15 % of it, with no consistent direction (sweep controllers median 548 on battery, 538 on mains; latency compile median 524 and 595); heap after the sweep differed more (891 and 696 MB). So the battery runs are published.
- The memory test alone and the cold starts were rerun on mains power, with sleep prevented, and those runs are published. The memory verdicts and RSS matched the earlier battery runs (RSS with ten files within 20 %), but the ten files took 26 to 53 % longer on every project.
- Timings vary by about 30 % between runs on this fanless machine. rundeck's cold start varied more and is given as the range of two runs.
- RSS in a full run includes the earlier tests, so RSS comes from the memory test run alone. Heap numbers in the full runs are also taken in that shared JVM.

## Sweep: every Groovy file compiled as the open file

`LatencyTest.artefactSweep`, per artefact kind. Members: files where Grails's members were found / artefact files (files Grails does not take as artefacts, such as controllers-dir classes not named `*Controller`, are left out).

| | cherokeedictionary | mdm-core | mod-agreements | rundeck | hoist-core |
|---|---|---|---|---|---|
| Files | 101 | 1296 | 229 | 1714 | 312 |
| Errors / files with errors | 0 / 0 | 4 / 4 | 0 / 0 | 30 / 21 | 0 / 0 |
| Members: controllers | 21 / 21 | 61 / 61 | 33 / 33 | 32 / 32 | 31 / 31 |
| Members: domain | 33 / 33 | 58 / 58 | 60 / 60 | 23 / 23 | 9 / 9 |
| Members: taglibs | 8 / 8 | - | - | 9 / 9 | - |
| Members: `@Transactional` services | - (0) | 64 / 64 | 9 / 9 | 24 / 24 | 9 / 9 |
| Compile: controllers | 55 / 186 / 343 | 1175 / 3097 / 7171 | 590 / 1059 / 3995 | 443 / 4753 / 14224 | 548 / 680 / 3108 |
| Compile: services | 15 / 35 / 123 | 1493 / 2364 / 2977 | 627 / 972 / 1046 | 37 / 4099 / 4339 | 437 / 477 / 508 |
| Compile: domain | 25 / 123 / 130 | 513 / 913 / 1099 | 525 / 594 / 718 | 3814 / 4127 / 4295 | 394 / 401 / 401 |
| Heap after the sweep | 138 | 3385 | 436 | 2565 | 891 |
| Full run, all four tests (s) | 33 | 2040 | 298 | 2717 | 302 |

## Static test: files with `@CompileStatic` or `@GrailsCompileStatic`

| | cherokeedictionary | mdm-core | mod-agreements | rundeck | hoist-core |
|---|---|---|---|---|---|
| Files / errors | 4 / 0 | 93 / 0 | 56 / 0 | 252 / 1 | 79 / 0 |
| Compile | 216 / 667 / 667 | 419 / 2311 / 5293 | 578 / 1020 / 2898 | 29 / 3512 / 11641 | 483 / 926 / 3500 |

## Latency: queries at the first member access of 40 sampled artefact files

Files without a member access are not measured. "Typed dot": the member deleted, so `receiver.` as just typed, which does not parse; the completion compiles a copy with a placeholder.

| | cherokeedictionary | mdm-core | mod-agreements | rundeck | hoist-core |
|---|---|---|---|---|---|
| Files measured | 16 | 37 | 33 | 33 | 40 |
| Compile | 49 / 211 / 331 | 1153 / 3336 / 5245 | 591 / 1085 / 2014 | 911 / 4731 / 10878 | 524 / 594 / 2454 |
| First query (builds the node lookup) | 0 / 2 / 4 | 1 / 2 / 2 | 0 / 0 / 4 | 1 / 2 / 4 | 0 / 0 / 4 |
| Hover | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 1 | 0 / 0 / 0 |
| Completion | 0 / 0 / 1 | 1 / 2 / 3 | 0 / 0 / 1 | 2 / 3 / 4 | 0 / 0 / 3 |
| Signature help | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 1 |
| Compile with a typed dot | 12 / 76 / 116 | 3 / 1527 / 2504 | 39 / 1052 / 1391 | 4 / 4226 / 4533 | 2 / 480 / 523 |
| Completion after a typed dot | 12 / 136 / 186 | 790 / 2372 / 2697 | 1 / 599 / 1019 | 19 / 4597 / 6801 | 510 / 608 / 1092 |
| Completions with no items: compiled / typed dot | 0 / 3 | 0 / 0 | 4 / 5 | 0 / 2 | 0 / 2 |

Symbol index (all contexts):

| | cherokeedictionary | mdm-core | mod-agreements | rundeck | hoist-core |
|---|---|---|---|---|---|
| Files / symbols | 101 / 754 | 1296 / 18300 | 229 / 2598 | 1714 / 25979 | 312 / 4104 |
| Time | 363 | 9215 | 3480 | 25472 | 2466 |
| Heap after | 21 | 445 | 187 | 2166 | 129 |

## Memory: ten files kept, repeated compiles, close

`LatencyTest.keptFilesRepeatsAndClose`, run alone. In the compile context with the most artefact files: compile ten files (controllers first) and keep them; compile the median and the slowest file 50 times each; close the context and check that its class loader is collected, first by GC alone, then after the heap is filled until the JVM clears soft references.

| | cherokeedictionary | mdm-core | mod-agreements | rundeck | hoist-core |
|---|---|---|---|---|---|
| Contexts in the project | 2 | 51 | 3 | 83 | 8 |
| Ten files, all compiles | 2001 | 12048 | 12310 | 45713 | 11656 |
| Repeat x50, median / slowest file (median ms) | 38 / 166 | 804 / 1702 | 694 / 1114 | 4209 / 4656 | 487 / 502 |
| RSS before compiling | 130 | 256 | 136 | 296 | 158 |
| **RSS with ten files** | 442 | 1087 | 1069 | **2874** | 1017 |
| RSS after the repeats, before close | 540 | 1440 | 1615 | 4547 | 1718 |
| RSS after close | 305 | 335 | 291 | 2695 | 288 |
| Heap with ten files | 61 | 285 | 344 | 1316 | 297 |
| Heap after 10 → 50 repeats (median file; slowest file) | 74 → 68; 75 → 76 | 333 → 323; 362 → 363 | 400 → 387; 386 → 386 | 1502 → 1503; 1633 → 1634 | 429 → 416; 417 → 417 |
| Loader collected by GC / once soft references are cleared | yes / yes | yes / yes | yes / yes | **no / no** | no / yes |
| Close: heap before → after | 76 → 7 | 363 → 14 | 386 → 11 | 1634 → 682 | 417 → 8 |
| Close: loaded classes before → after | 6487 → 4366 | 7451 → 4598 | 7316 → 4585 | 11651 → 10983 | 8217 → 4815 |

Live heap stays flat over the repeats on every project. RSS keeps growing after the ten files (the JVM keeps the heap it has committed), so after 100 more compiles mod-agreements and hoist-core are above 1.5 GB although their live heap stays under 450 MB; launcher heap flags are a distribution question.

## Cold start: time to first diagnostics

One server process per file, started over stdio as an editor starts it (`initialize`, then `didOpen` at once), on the fat jar of `d28b216`. An untimed first run fills the project-model cache, so every timed run is a warm start. The time is the server's own `startup: first diagnostics after N ms` mark. Up to 20 controllers and 20 services per project, spread evenly over the sorted file list.

| | Controllers: n, median / p90 / max | Within 3 s | Services: n, median / p90 / max | Within 3 s |
|---|---|---|---|---|
| cherokeedictionary | 20, 876 / 1068 / 1498 | 20 | 13, 751 / 1030 / 1487 | 13 |
| mdm-core | 20, 7051 / 8785 / 9430 | 0 | 20, 5761 / 7922 / 8365 | 3 |
| mod-agreements | 20, 3461 / 4344 / 5571 | 7 | 20, 4064 / 5598 / 5765 | 4 |
| rundeck (two runs) | 20, 10497 to 14041 / 15208 to 27799 / 17543 to 28698 | 5 to 10 | 20, 1784 to 2251 / 12138 to 15263 / 12706 to 15762 | 14 |
| hoist-core | 20, 2875 / 3039 / 3418 | 16 | 20, 3050 / 3203 / 3361 | 9 |

Every timed file got diagnostics, none with errors.

## Errors on valid code

- **mdm-core, 4 (known limits):** 2 bound violations, `The type D is not a valid substitute for the bounded parameter <D extends ...Diffable>` in `DataType` and `ReferenceDataType` (`D` is unbounded in the source; Groovy 4 checks bounds against class files, 2.5 and 3 do not). 2 Spock `Only @Shared and static fields may be accessed from here` in `mdm-testing-functional` (`UserAccessAndPermissionChangingFunctionalSpec`, `UserAccessAndCopyingInDataModelsFunctionalSpec`): groovyc gives the same 2 when the sibling trait `SecurityDefinition` is compiled from source, and 0 against the built jar; the server compiles sibling sources.
- **rundeck sweep, 30 in 21 files, all in test source sets:**
  - 29 name Java test classes that were not built (the corpus builds `classes`, main source sets only): `core/src/test/java` `AbstractBaseTest` (18), `TestDirPluginScanner` (2), `TestJarPluginProviderLoader` (7); `WorkflowExecutionListenerTest.java` under `rundeckapp/src/test/groovy` (2).
  - 1 internal compiler error in `JettyCombinedLdapLoginModuleSpec`: `NoClassDefFoundError: rundeck.services.ConfigurationService`. **Fixed after these measurements** (sweep rerun: 29 in 20 files). Spock's transformation reads the methods of the Java class `JettyCombinedLdapLoginModule` (from main's build output); a method of its superclass returns the Groovy service `ConfigurationService`, and Groovy's reader of class files throws for a class whose source was only just added to the compile. The server now compiles such a document again with those sources there from the start.
- **rundeck static test, 1:** `AbstractBaseTest`, unbuilt test output as above.

## Changes since the first measurements (2026-11-14, before fixes 1 to 10)

- mdm-core: the sweep completes (it timed out after 613 files); errors 111 in 58 files → 4 known limits; static test 10 → 0 errors; services p90 11029 → 2364 ms (Groovy's parser cache is now kept).
- mod-agreements: the latency test passes (it hung on an Elvis expression that was its own parent); errors 1 → 0.
- hoist-core: errors 2 → 0. Its loader, not collected then, is held only by soft references (confirmed by the new check).
- rundeck: first results (the static test exhausted the heap before).

## Known problems found

- **Known limit: on a JDK 17 server process, rundeck's compile context loader is not released.** After close the loader survives even once soft references are cleared: heap 1634 → 682 MB, and only 668 of 11651 loaded classes unloaded. A heap dump after close shows the cause in the JDK, not the server: `MethodHandle.asTypeCache` is a strong field on JDK 17, and method handles kept in JDK statics (`java.lang.runtime.ObjectMethods.OBJECT_EQ` and `OBJECTS_HASHCODE`, used by record classes; `MethodHandleImpl.ARRAYS`, reached from Groovy 4's `invokedynamic` call sites) keep the last type they were adapted to, whose classes belong to the context's loader (Micronaut's source generation records, Groovy's AST classes). JDK 21 keeps such types behind a soft reference: the same test with the server's JVM on 21.0.13 (the project still on JDK 17, 0 errors) gives no / yes, heap after close 10 MB, loaded classes 11245 → 4698 (that run compiled each repeated file once, not 50 times). Seen on rundeck only, the one Groovy 4 project measured on JDK 17. Nothing to fix in the server; the launcher should prefer JDK 21 or newer for the server process.
- **Fixed: Java build output that names Groovy sources.** A Java class from build output whose member types name a Groovy class not yet in the compile failed the compile (rundeck's `JettyCombinedLdapLoginModuleSpec` above). A document that hits this is compiled twice.
- **Budget misses on larger projects:** first diagnostics above 3 s on mdm-core, mod-agreements, rundeck and partly hoist-core; completion after a typed dot above 300 ms at p90 on all but cherokeedictionary; RSS with ten files above 1.5 GB on rundeck.

## Rerunning

Clone and build each project as in [corpus.md](corpus.md). The perf tests (outside `build`; counts and timings only, written to `server/build/perf/`):

```sh
JAVA_HOME=<JDK 17> GLS_TEST_JDK11=<JDK 11> GLS_TEST_JDK17=<JDK 17> \
GLS_PERF_PROJECT=<built project> GLS_PERF_JDK=<project JDK> GLS_PERF_DETAILS=<error texts file, outside the repo> \
  ./gradlew :server:perfTest -PtestJavaHome=<project JDK>
```

This writes `sweep.txt`, `static.txt`, `latency.txt` and `memory.txt`. For RSS, run the memory test alone: add `--tests '*LatencyTest.keptFilesRepeatsAndClose'`. `GLS_PERF_HEAP` (e.g. `4g`) sets the heap; `GLS_PERF_FILES` the latency sample (40).

The cold-start client (a Python script driving the fat jar from `./gradlew :server:shadowJar` as described above) is not in the repository. Run measurements on mains power with sleep prevented (`caffeinate -i` on macOS), and with no other measurement running.
