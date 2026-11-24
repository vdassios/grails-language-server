# Benchmark results

Measured figures for the server: first on its own fixtures, the latest numbers (2026-11-23, after the Stage 6 latency work), then on the five public projects of [corpus.md](corpus.md) (2026-11-18, server `7224fc6`). The corpus projects were not on the machine for the later measurements, so their figures predate the changes below and are not claimed for them.

The server was developed against a target (C6): first diagnostics within 3 s of a warm start, completion within 300 ms, resident memory under 1.5 GB with ten files open. It is a target, not a promise: both fixtures are well within it; on the corpus, first diagnostics and completion after a typed dot miss it on the larger projects (mdm-core, mod-agreements, rundeck, hoist-core) and resident memory on rundeck, as the corpus sections give.

## Fixtures, 2026-11-23: before and after the latency work

- Before: server `d215979` (before any Stage 6 change). After: `a1c0741`. The fixtures: `grails4` (Grails 4, Groovy 2.5.14, JDK 11.0.28, 5 compile contexts, 111 jars) and `grails7` (Grails 7, Groovy 4, JDK 17.0.19, 5 contexts, 87 jars), Amazon Corretto. They hold a handful of files per artefact kind, so they show fixed costs (loaders, jars, the parser), not those that grow with a project.
- MacBook Air, Apple M5 (fanless), 16 GB RAM; test heap 4096 MB. "Battery" is the first baseline, taken on battery; "mains" re-measures the same commit on mains power with sleep prevented, as every "after" figure is. Timings vary by about 30 % between runs; where a figure was measured more than once, every run is given.
- Times are milliseconds, median / p90 (n is 2 or 3 for the latency sample, 10 per cold-start group, 50 for the repeats). Heap is MB after three collections; the battery baseline used a single collection, which varied from 38 to 78 MB for the same code, so its heap figures are left out.

| grails4 | Before, battery | Before, mains | After, mains |
|---|---|---|---|
| Cold start, controllers (server mark) | 516 / 520 | 474 / 497; 499 / 551 | 542 / 554; 491 / 520; 502 / 528 |
| Cold start, services | 482 / 497 | 449 / 463; 473 / 498 | 512 / 519; 467 / 490; 462 / 472 |
| Compile, latency sample | 23 / 282 | 16 / 214; 22 / 309; 19 / 292 | 17 / 322; 28 / 391; 29 / 401 |
| Completion after a typed dot | 19 / 31 | 17 / 35; 15 / 48; 17 / 39 | 1 / 7; 1 / 10; 2 / 20 |
| Page code completion after a typed dot | 31 | 20 | 20 |
| Compile a page | 229 / 253 | 166 / 203 | 164 / 211 |
| Repeat x50, median file | 15 / 22 | 13 / 21 | 13 / 22 |
| Symbol index, all files | 104 | 92; 130; 136 | 55; 82; 88 |
| Loaders started by the symbol index | 5 | 5 | 1 |
| Heap after the symbol index | - | 13; 13 | 8; 6; 6 |
| Heap after compiling every file (sweep) | - | 46; 46 | 48; 48; 48 |

| grails7 | Before, battery | Before, mains | After, mains |
|---|---|---|---|
| Cold start, controllers (server mark) | 649 / 657 | 631 / 661 | 635 / 687 |
| Cold start, services | 620 / 641 | 597 / 620 | 598 / 628 |
| Compile, latency sample | 186 / 186 | 187; 328; 294 | 324; 410; 375 |
| Completion after a typed dot | 0 / 0 | 0 / 0 | 0 / 0 |
| Page code completion after a typed dot | 29 | 40 | 28 |
| Compile a page | 203 / 255 | 216 / 329 | 203 / 291 |
| Repeat x50, median file | 12 / 20 | 12 / 19 | 12 / 16 |
| Symbol index, all files | 120 | 126; 160; 168 | 128; 174; 160 |
| Loaders started by the symbol index | 5 | 5 | 1 |
| Heap after the symbol index | - | 13; 13 | 16; 14; 14 |
| Heap after compiling every file (sweep) | - | 37; 37 | 39; 39; 39 |

What changed and what it shows:

- **Completion after a typed dot** is answered from the last compile that parsed when that is provably right, and otherwise compiles one repaired copy: grails4 median 17 → 1 ms, p90 35 to 48 → 7 to 20 ms.
- **The symbol index parses in one loader per Groovy jar**, not in every source set: parsing needs no class path. It starts 1 loader instead of 5 on each fixture, and its heap on grails4 halves (13 → 6 to 8 MB); on grails7 it stays within the variation (13 → 14 to 16 MB). The cost moves to the first compile in a source set nobody had opened, which now starts that loader itself: the latency sample's p90 (its file in a source set not started yet) was 214, 309 and 292 ms before and 322, 391 and 401 ms after on grails4, and 187, 328 and 294 ms before and 324, 410 and 375 ms after on grails7. First diagnostics do not change (cold start, within the variation): the server starts the symbol index only after the open documents' first compiles.
- **Each jar's entries are read once per workspace** (`JarEntries`), for every module loader's jar index, import completion and the page lookups. Before, each loader read every jar of its class path again: 11 to 21 ms per loader of about 100 jars on the fixtures; the taglib and plugin file lookups (12 ms) and import completion read them once more. The classes by package that import completion needs are now kept from the first read for every jar, about 2 MB on the fixtures (heap after the sweep +2 MB).
- **Cold start and repeated compiles** are unchanged within the variation.

### Decisions measured on the fixtures and rundeck

The shared parser cache (`AntlrLoader`, `SharedParser`) is kept. It loads the ANTLR runtime of a Groovy 3 or 4 jar once for all module loaders of that jar and points their parsers at one prediction cache, by writing a final field of Groovy's internals, which JEP 500 restricts (see [Known limits](../docs/how-it-works.md#known-limits)). The rule was to keep it only if heap or p90 compile time improved by 15 % or more on the largest project measured. The fixtures cannot show it (grails7, the only one with an ANTLR 4 parser: heap after the sweep 37 MB with and without it), so it was measured on rundeck (21f4101, JDK 17, mains power), two rounds in alternating order (with, without; then without, with): heap after the sweep 1756 and 1753 MB with it, 2564 and 2615 MB without, 32 % lower in both rounds. Compile p90 by sweep group was 12 to 22 % lower with it in the first round, whose run without it overlapped other work; in the second, on an idle machine, 2 to 10 % (controllers 5351 / 5522 ms, services 4692 / 4810, domain 4713 / 4835, other 3928 / 4383, with / without), and the static test's p90 was higher with it (4663 / 3509). It cost heap earlier on: after the symbol index 1814 and 1822 MB with it, 1513 and 1515 without; with ten files kept, the same (2061 to 2085). That cost was the symbol index filling the shared cache without emptying it, which the server now does after each batch: the latency test run alone (two runs each, interleaved), heap after the symbol index 296 and 296 MB with the cache and that trim, 571 and 571 without the trim, 295 and 295 without the cache; latency-sample compile p90 3872 and 3749 ms, 3835 and 3859, 4017 and 4082.

`JarIndex` was kept: without it (every lookup searches the jars), compiles repeated 50 times on grails4 took 15 / 25, 15 / 25, 15 / 26 ms (median file) and 12 / 15, 12 / 18, 12 / 16 ms (slowest file) against 14 / 23, 14 / 21, 13 / 23 and 10 / 13, 10 / 13, 10 / 13 with it, three runs each; on grails7 the two were equal within the variation.

Creating a class loader for each file the symbol index parses costs about 1 µs, against about 130 µs to parse and convert the grails4 fixture's controller with Groovy 2.5.14 (JDK 11, after warm-up): kept as it is.

## Corpus, 2026-11-18: against the C6 target

- Server: commit `7224fc6`.
- Date: 2026-11-18 (first measured 2026-11-15 on `d28b216`: see "Changes since the 2026-11-15 measurements"). Machine: MacBook Air, Apple M5 (fanless), 16 GB RAM, macOS 26.5.
- Test JVM heap 4096 MB (a quarter of RAM, what `java -jar` gets). Each project runs on its own build JDK (corpus.md): cherokeedictionary 11.0.28, mdm-core, mod-agreements and rundeck 17.0.19, hoist-core 21.0.13 (Amazon Corretto).
- Times are milliseconds, given as median / p90 / max. Heap is MB after GC; RSS is the process's resident set in MB.

| Target | Meets it | Misses it |
|---|---|---|
| First diagnostics within 3 s (warm start, one process per file) | cherokeedictionary: every file | mod-agreements: 17 of 20 controllers and 12 of 20 services within 3 s; rundeck: 10 and 8 of 20 (medians 7.7 and 12.9 s); hoist-core: 1 and 1 of 20 (medians 3.2 and 3.4 s); mdm-core: 0 and 4 of 20 |
| Completion within 300 ms, compiled document | all five (max 8 ms) | |
| Completion within 300 ms, after a typed dot (the editor waits for the compile of the copy) | cherokeedictionary (p90 113) | hoist-core (p90 396), mod-agreements (524), mdm-core (2357), rundeck (5462) |
| RSS under 1.5 GB with ten files | cherokeedictionary 448, mdm-core 872, mod-agreements 1137 (median of three runs), hoist-core 1145 | rundeck 2389 |
| Loader released on close | cherokeedictionary, mdm-core, mod-agreements, hoist-core | rundeck on JDK 17: a JDK limit (see Known problems); released once soft references are cleared when the server runs on JDK 21 |

Errors on valid code: 0 on cherokeedictionary, mod-agreements and hoist-core; mdm-core 4 and rundeck 29 (sweep) and 1 (static test), all attributed below. Grails members (artefact traits and GORM methods) are found on every controller, domain class, taglib and `@Transactional` service of every project.

## Corpus measurement caveats

- Every run was on mains power with sleep prevented, in one sequence: the five full runs (all four perf tests in one JVM), then the memory test alone on each project, then the cold starts (2 hours in all, no sleep).
- Timings vary by about 30 % between runs on this fanless machine. RSS varies more: mod-agreements' RSS with ten files was 1677, 927 and 1137 MB in three runs of the memory test alone, with the same live heap; the median is given.
- Cold starts: the client takes the files named `*Controller.groovy` and `*Service.groovy` under `grails-app/`. The sample of the 2026-11-15 runs was not kept, so the two samples may differ. rundeck's service times fall in two groups, about 2 s and about 13 s (most of the `rundeckapp` services), so its median depends on the sample. hoist-core's medians (3.2 and 3.4 s) are 10 to 15 % above the 2026-11-15 ones, within the variation above, but this moves most of its files across the 3 s line; not investigated.
- RSS in a full run includes the earlier tests, so RSS comes from the memory test run alone. Heap numbers in the full runs are also taken in that shared JVM.

## Sweep: every Groovy file compiled as the open file

`LatencyTest.artefactSweep`, per artefact kind. Members: files where Grails's members were found / artefact files (files Grails does not take as artefacts, such as controllers-dir classes not named `*Controller`, are left out).

| | cherokeedictionary | mdm-core | mod-agreements | rundeck | hoist-core |
|---|---|---|---|---|---|
| Files | 101 | 1296 | 229 | 1714 | 312 |
| Errors / files with errors | 0 / 0 | 4 / 4 | 0 / 0 | 29 / 20 | 0 / 0 |
| Members: controllers | 21 / 21 | 61 / 61 | 33 / 33 | 32 / 32 | 31 / 31 |
| Members: domain | 33 / 33 | 58 / 58 | 60 / 60 | 23 / 23 | 9 / 9 |
| Members: taglibs | 8 / 8 | - | - | 9 / 9 | - |
| Members: `@Transactional` services | - (0) | 64 / 64 | 9 / 9 | 24 / 24 | 9 / 9 |
| Compile: controllers | 40 / 120 / 404 | 1031 / 2938 / 6089 | 488 / 1013 / 1695 | 290 / 4575 / 8145 | 362 / 513 / 1971 |
| Compile: services | 12 / 22 / 82 | 1445 / 1926 / 2742 | 502 / 774 / 855 | 34 / 4151 / 7602 | 333 / 362 / 408 |
| Compile: domain | 16 / 86 / 154 | 501 / 822 / 1092 | 408 / 489 / 596 | 3599 / 4116 / 4162 | 312 / 346 / 346 |
| Heap after the sweep | 136 | 1981 | 524 | 1738 | 654 |
| Full run, all four tests (s) | 25 | 1859 | 258 | 2660 | 218 |

## Static test: files with `@CompileStatic` or `@GrailsCompileStatic`

| | cherokeedictionary | mdm-core | mod-agreements | rundeck | hoist-core |
|---|---|---|---|---|---|
| Files / errors | 4 / 0 | 93 / 0 | 56 / 0 | 252 / 1 | 79 / 0 |
| Compile | 125 / 639 / 639 | 441 / 2112 / 3677 | 468 / 840 / 2768 | 37 / 3498 / 11721 | 354 / 687 / 3217 |

## Latency: queries at the first member access of 40 sampled artefact files

Files without a member access are not measured. "Typed dot": the member deleted, so `receiver.` as just typed, which does not parse; the completion compiles a copy with a placeholder.

| | cherokeedictionary | mdm-core | mod-agreements | rundeck | hoist-core |
|---|---|---|---|---|---|
| Files measured | 16 | 37 | 33 | 33 | 40 |
| Compile | 38 / 192 / 284 | 1134 / 2834 / 3748 | 478 / 855 / 1725 | 1217 / 5491 / 16551 | 335 / 414 / 1719 |
| First query (builds the node lookup) | 1 / 2 / 3 | 1 / 3 / 4 | 0 / 1 / 3 | 1 / 5 / 9 | 0 / 1 / 6 |
| Hover (the first one may read a sources jar) | 0 / 1 / 13 | 0 / 1 / 24 | 0 / 1 / 14 | 0 / 7 / 66 | 0 / 0 / 10 |
| Completion | 0 / 1 / 6 | 1 / 2 / 3 | 0 / 0 / 2 | 4 / 6 / 8 | 0 / 0 / 2 |
| Signature help | 0 / 0 / 0 | 0 / 0 / 1 | 0 / 0 / 0 | 0 / 0 / 2 | 0 / 0 / 0 |
| Compile with a typed dot | 10 / 55 / 82 | 4 / 1511 / 2274 | 34 / 848 / 1248 | 7 / 5146 / 5816 | 2 / 319 / 364 |
| Completion after a typed dot | 10 / 113 / 125 | 789 / 2357 / 2668 | 3 / 524 / 803 | 14 / 5462 / 7139 | 330 / 396 / 751 |
| Completions with no items: compiled / typed dot | 0 / 3 | 0 / 0 | 4 / 5 | 0 / 2 | 0 / 2 |

Symbol index (all contexts):

| | cherokeedictionary | mdm-core | mod-agreements | rundeck | hoist-core |
|---|---|---|---|---|---|
| Files / symbols | 101 / 754 | 1296 / 18300 | 229 / 2598 | 1714 / 25979 | 312 / 4104 |
| Time | 345 | 7865 | 3266 | 29843 | 2220 |
| Heap after | 21 | 294 | 175 | 2047 | 132 |

## Memory: ten files kept, repeated compiles, close

`LatencyTest.keptFilesRepeatsAndClose`, run alone. In the compile context with the most artefact files: compile ten files (controllers first) and keep them; compile the median and the slowest file 50 times each; close the context and check that its class loader is collected, first by GC alone, then after the heap is filled until the JVM clears soft references.

| | cherokeedictionary | mdm-core | mod-agreements | rundeck | hoist-core |
|---|---|---|---|---|---|
| Contexts in the project | 2 | 51 | 3 | 83 | 8 |
| Ten files, all compiles | 1251 | 9199 | 7549 | 35301 | 6741 |
| Repeat x50, median / slowest file (median ms) | 29 / 96 | 652 / 1470 | 439 / 790 | 3819 / 4217 | 306 / 384 |
| RSS before compiling | 158 | 264 | 145 | 293 | 161 |
| **RSS with ten files** | 448 | 872 | 1137 (927 to 1677) | **2389** | 1145 |
| RSS after the repeats, before close | 556 | 1360 | 1598 (1463 to 1645) | 2986 | 1655 |
| RSS after close | 312 | 277 | 252 (234 to 291) | 2119 | 266 |
| Heap with ten files | 59 | 285 | 341 | 1313 | 292 |
| Heap after 10 → 50 repeats (median file; slowest file) | 74 → 68; 76 → 76 | 321 → 322; 362 → 363 | 415 → 410; 438 → 438 | 1502 → 1503; 1642 → 1643 | 404 → 392; 423 → 423 |
| Loader collected by GC / once soft references are cleared | yes / yes | yes / yes | yes / yes | **no / no** | yes / yes |
| Close: heap before → after | 76 → 7 | 363 → 14 | 438 → 11 | 1643 → 690 | 423 → 11 |
| Close: loaded classes before → after | 6200 → 4338 | 7535 → 4618 | 7072 → 4448 | 11345 → 10746 | 7880 → 4882 |

Live heap stays flat over the repeats on every project. RSS keeps growing after the ten files (the JVM keeps the heap it has committed), so after 100 more compiles mod-agreements and hoist-core are at or above 1.5 GB although their live heap stays under 450 MB; launcher heap flags are a distribution question.

## Cold start: time to first diagnostics

One server process per file, started over stdio as an editor starts it (`initialize`, then `didOpen` at once), on the fat jar of `7224fc6`. An untimed first run fills the project-model cache, so every timed run is a warm start. The time is the server's own `startup: first diagnostics after N ms` mark. Up to 20 controllers (`*Controller.groovy`) and 20 services (`*Service.groovy`) under `grails-app/` of all modules per project, spread evenly over the sorted file list.

| | Controllers: n, median / p90 / max | Within 3 s | Services: n, median / p90 / max | Within 3 s |
|---|---|---|---|---|
| cherokeedictionary | 20, 779 / 847 / 1054 | 20 | 13, 711 / 825 / 908 | 13 |
| mdm-core | 20, 5729 / 6908 / 7283 | 0 | 20, 4049 / 5935 / 6559 | 4 |
| mod-agreements | 20, 2472 / 3290 / 3938 | 17 | 20, 2688 / 4054 / 4148 | 12 |
| rundeck | 20, 7652 / 13565 / 13913 | 10 | 20, 12929 / 14332 / 14647 | 8 |
| hoist-core | 20, 3224 / 3519 / 4007 | 1 | 20, 3414 / 3757 / 3941 | 1 |

Every timed file got diagnostics, none with errors.

## Errors on valid code

- **mdm-core, 4 (known limits):** 2 bound violations, `The type D is not a valid substitute for the bounded parameter <D extends ...Diffable>` in `DataType` and `ReferenceDataType` (`D` is unbounded in the source; Groovy 4 checks bounds against class files, 2.5 and 3 do not). 2 Spock `Only @Shared and static fields may be accessed from here` in `mdm-testing-functional` (`UserAccessAndPermissionChangingFunctionalSpec`, `UserAccessAndCopyingInDataModelsFunctionalSpec`): groovyc gives the same 2 when the sibling trait `SecurityDefinition` is compiled from source, and 0 against the built jar; the server compiles sibling sources.
- **rundeck sweep, 29 in 20 files, all in test source sets:** they name Java test classes that were not built (the corpus builds `classes`, main source sets only): `core/src/test/java` `AbstractBaseTest` (18), `TestDirPluginScanner` (2), `TestJarPluginProviderLoader` (7); `WorkflowExecutionListenerTest.java` under `rundeckapp/src/test/groovy` (2).
- **rundeck static test, 1:** `AbstractBaseTest`, unbuilt test output as above.

## Changes since the 2026-11-15 measurements (`d28b216`)

Server changes in between: library sources for definition and hover, GORM finders and criteria, one parser cache per Groovy jar shared by all compile contexts, and compile output kept out of the working directory. Until that last fix, compiles rewrote a `grails-plugin.xml` below the working directory that grew with every artefact compiled, so the 2026-11-15 compile times include a cost that grew over each run. The 2026-11-15 full runs were also on battery (their memory and cold-start runs were on mains).

- Heap after the sweep: mdm-core 3385 → 1981 MB and rundeck 2565 → 1738 MB (the shared parser cache); hoist-core 891 → 654, mod-agreements 436 → 524.
- Compile times: lower in most groups on every project, by up to about 40 % at the median. rundeck's latency sample is the exception (compile p90 4731 → 5491, completion after a typed dot p90 4597 → 5462), within the run-to-run variation.
- Cold starts: faster on cherokeedictionary, mdm-core and mod-agreements (mod-agreements 7 → 17 controllers within 3 s); hoist-core slower (16 → 1 controllers within 3 s); rundeck's controllers faster (median 10.5 to 14 s → 7.7 s) and its services slower (14 → 8 within 3 s), perhaps from a different sample (see Measurement caveats).
- RSS with ten files: mdm-core 1087 → 872, rundeck 2874 → 2389; the others within the variation.
- hoist-core's loader is now collected by GC alone (before: only once soft references were cleared).
- rundeck sweep: 30 → 29 errors (Java build output that names a Groovy source, fixed: see Known problems).
- Hover max 1 → 66 ms: the first hover on a library class may read its sources jar for the documentation.

## Changes since the first measurements (2026-11-14, before fixes 1 to 10)

- mdm-core: the sweep completes (it timed out after 613 files); errors 111 in 58 files → 4 known limits; static test 10 → 0 errors; services p90 11029 → 2364 ms (Groovy's parser cache is now kept).
- mod-agreements: the latency test passes (it hung on an Elvis expression that was its own parent); errors 1 → 0.
- hoist-core: errors 2 → 0. Its loader, not collected then, is held only by soft references (confirmed by the new check).
- rundeck: first results (the static test exhausted the heap before).

## Known problems found

- **Known limit: on a JDK 17 server process, rundeck's compile context loader is not released.** After close the loader survives even once soft references are cleared: heap 1643 → 690 MB, and only 599 of 11345 loaded classes unloaded. A heap dump after close shows the cause in the JDK, not the server: `MethodHandle.asTypeCache` is a strong field on JDK 17, and method handles kept in JDK statics (`java.lang.runtime.ObjectMethods.OBJECT_EQ` and `OBJECTS_HASHCODE`, used by record classes; `MethodHandleImpl.ARRAYS`, reached from Groovy 4's `invokedynamic` call sites) keep the last type they were adapted to, whose classes belong to the context's loader (Micronaut's source generation records, Groovy's AST classes). JDK 21 keeps such types behind a soft reference: the same test with the server's JVM on 21.0.13 (the project still on JDK 17, 0 errors) gives no / yes, heap after close 10 MB, loaded classes 11245 → 4698 (that run compiled each repeated file once, not 50 times). Seen on rundeck only, the one Groovy 4 project measured on JDK 17. Nothing to fix in the server; the launcher should prefer JDK 21 or newer for the server process.
- **Fixed: Java build output that names Groovy sources.** A Java class from build output whose member types name a Groovy class not yet in the compile failed the compile (rundeck's `JettyCombinedLdapLoginModuleSpec`: `NoClassDefFoundError: rundeck.services.ConfigurationService`, the 30th sweep error of 2026-11-15). A document that hits this is compiled twice.
- **Budget misses on larger projects:** first diagnostics above 3 s on mdm-core, rundeck and hoist-core, and partly mod-agreements; completion after a typed dot above 300 ms at p90 on all but cherokeedictionary; RSS with ten files above 1.5 GB on rundeck.

## Rerunning

Clone and build each project as in [corpus.md](corpus.md). The perf tests (outside `build`; counts and timings only, written to `server/build/perf/`):

```sh
JAVA_HOME=<JDK 17> GLS_TEST_JDK11=<JDK 11> GLS_TEST_JDK17=<JDK 17> \
GLS_PERF_PROJECT=<built project> GLS_PERF_JDK=<project JDK> GLS_PERF_DETAILS=<error texts file, outside the repo> \
  ./gradlew :server:perfTest -PtestJavaHome=<project JDK>
```

Without `GLS_PERF_PROJECT` the tests measure the `grails4` fixture; `GLS_PERF_PROJECT=$PWD/fixtures/grails7 GLS_PERF_JDK=<JDK 17>` measures `grails7` (the fixture figures above, with `--repeat 10` for the cold starts). This writes `sweep.txt`, `static.txt`, `latency.txt` and `memory.txt`. For RSS, run the memory test alone: add `--tests '*LatencyTest.keptFilesRepeatsAndClose'`. `GLS_PERF_HEAP` (e.g. `4g`) sets the heap; `GLS_PERF_FILES` the latency sample (40).

The cold starts (the fat jar from `./gradlew :server:shadowJar`, driven as described above):

```sh
python3 benchmarks/cold-start.py --project <built project> --java <project JDK>/bin/java
```

`--files` sets the sample per group (20), `--repeat` the timed runs per file (1). Run measurements on mains power with sleep prevented (`caffeinate -i` on macOS), and with no other measurement running.
