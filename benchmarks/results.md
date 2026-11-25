# Benchmark results

This page gives measured figures for the server, newest first:

- [matrix, 2026-11-25, later](#matrix-2026-11-25-completion-right-after-a-dot): the same library, before and after completion stopped waiting for the edit's compile right after a dot.
- [matrix, 2026-11-25](#matrix-2026-11-25-compiles-that-reuse-the-pulled-in-sources): a plain Groovy library outside the corpus, before and after compiles started reusing the sources that the last compile pulled in.
- [Fixtures, 2026-11-23](#fixtures-2026-11-23-before-and-after-the-latency-work): the server's own fixture projects, before and after the latency work.
- [rundeck, 2026-11-24](#rundeck-2026-11-24-after-the-latency-work): rundeck measured again after the latency work, on server `1706504`.
- [Corpus, 2026-11-18](#corpus-2026-11-18-against-the-performance-target): the five public projects of [corpus.md](corpus.md), on server `7224fc6`.

The other four corpus projects were not on the machine for the later measurements. Their figures were taken before the changes described below, so they say nothing about those changes.

The server was developed against a performance target:

- first diagnostics within 3 s of a warm start
- completion within 300 ms
- resident memory under 1.5 GB with ten files open

This is a goal, not a promise. Both fixtures are well within it. On the corpus:

- First diagnostics miss it on the larger projects: mdm-core, mod-agreements, rundeck, and hoist-core.
- Resident memory misses it on rundeck.
- Completion after a typed dot missed it on the same four projects on 2026-11-18. rundeck, the only one measured since, now meets it (p90 59 ms).

## matrix, 2026-11-25: completion right after a dot

- **Commits.** Before: server `ba2f3c9`. Then `df842c5`, which parses a text that does not parse only once per compile, and `2646611`, which answers completion right after a dot without waiting for the edit's compile.
- **Project, machine, and method.** The same as in [the section below](#matrix-2026-11-25-compiles-that-reuse-the-pulled-in-sources), with the same script. Mains power, with sleep prevented.
- **Units.** Times are in milliseconds, as the median of 5. Every completion gave the same items in all three versions: 123 for `m.`, 19 for `m.c`, 10 for `m.co`, and 8 for `m.col`. `m.` gives 3 fewer items than in the section below, because completion no longer offers `Matrix`'s package-private members or `Object.finalize` there (`58dba98`).

| Latency | Before (`ba2f3c9`) | `df842c5` | After (`2646611`) |
|---|---|---|---|
| Compile after an edit that keeps the text | 15 | 19 | 15 |
| `m.`, right after its edit | 223 | 89 | 20 |
| `m.c`, right after its edit | 6 | 6 | 12 |
| `m.co`, right after its edit | 6 | 7 | 10 |
| `m.col`, right after its edit | 5 | 7 | 9 |
| `m.`, after its diagnostics | 8 | 9 | 9 |
| `m.c`, `m.co`, and `m.col`, after their diagnostics | 1 | 1 to 2 | 1 to 2 |

The rise for `m.c` to `m.col` right after their edits comes from the processor's clock, not from the code. They take the same code path in both versions. After `2646611`, they no longer follow a long compile of `m.`, so they run before the clock has gone up. With one core kept busy, `df842c5` and `2646611` both take 5 ms for each.

Memory comes from two runs of each version, separated by `;`, with the script and units of the section below. "After one more compile" adds an edit that removes `m.col` and waits for its diagnostics.

| Memory | Before (`ba2f3c9`) | After (`2646611`) |
|---|---|---|
| matrix: heap with ten files | 639 | 639 |
| matrix: heap after the edits | 651; 650 | 676; 678 |
| matrix: heap after one more compile | 642; 641 | 641; 642 |

### What changed

- **Where the 200 ms went.** Completion first ran the compile of the edit, as every request did. That text ends at the dot, so it does not parse. Groovy then parsed it again in ANTLR's slower LL mode, and from Groovy 3, each later phase of the compile parsed it once more. Now a compile skips the later phases of a file that does not parse (`df842c5`). And completion right after a dot no longer waits for the edit's compile (`2646611`). It never answered from that compile: it answers from the last compile that parsed, or from a repaired copy. The compile still runs after its 300 ms delay and publishes the diagnostics.
- **`m.` right after its edit now takes 20 ms**, down from 223 ms. Other requests, such as hover, still start the edit's compile and wait for it.
- **Memory is unchanged once settled.** Right after the edits, the heap is about 25 MB higher. The difference is in Groovy's cache of extension methods, which keeps the entries of unreachable class loaders until its next use. The next compile clears them.

## matrix, 2026-11-25: compiles that reuse the pulled-in sources

- **Commits.** Before: server `240c467`. After: `d4ab1f0`.
- **Project.** [Alipsa/matrix](https://github.com/Alipsa/matrix) at `c598eee`, a plain Groovy library on Groovy 6.0.0 and JDK 21.0.13 (Amazon Corretto). `matrix-stats` has 118 Groovy files and uses the sources of `matrix-core` (47 files, including `Matrix.groovy` at 4,959 lines). Its compiler configuration script makes every file static, so each compile goes on to instruction selection.
- **Machine.** The same as for the fixtures below. Mains power, with sleep prevented.
- **Method.** A script drives the fat jar over stdio, the way an editor does. It opens `matrix-stats/src/main/groovy/se/alipsa/matrix/stats/Sampler.groovy` and adds a method `void f(Matrix m)` with an empty body. It then types `m.col` into that body, one edit per character. Each completion is timed from request to answer. "Right after its edit" sends the request just after the edit, as an editor does while you type. "After its diagnostics" waits for the edit's compile first. A compile is timed from the edit to its diagnostics, minus the 300 ms delay after an edit. The script is not in the repository.
- **Units.** Times are in milliseconds, as the median of 5. Every completion gave the same items before and after: 126 for `m.`, 19 for `m.c`, 10 for `m.co`, and 8 for `m.col`.

| Latency | Before | After |
|---|---|---|
| Compile after an edit that keeps the text | 1139 | 19 |
| `m.`, right after its edit | 1100 | 206 |
| `m.c`, right after its edit | 927 | 4 |
| `m.co`, right after its edit | 943 | 4 |
| `m.col`, right after its edit | 941 | 4 |
| `m.`, after its diagnostics | 965 | 6 |

The memory figures come from two runs of each version, separated by `;`. Where both runs gave the same figure, it is given once. On matrix, the same script opens `Sampler.groovy`, then nine more files of `matrix-stats`, then types `m.col` ten times, with a completion after each character. Heap is in MB after three garbage collections (`jcmd`). The fixture figures come from `LatencyTest.keptFilesRepeatsAndClose`, run alone (see [Memory](#memory-ten-files-kept-repeated-compiles-close)).

| Memory | Before | After |
|---|---|---|
| matrix: heap with ten files | 639 | 639 |
| matrix: heap after the edits | 657; 654 | 651; 654 |
| matrix: RSS with ten files | 1909; 1926 | 1987; 2028 |
| matrix: RSS after the edits | 2125; 1864 | 2038; 2036 |
| grails4: heap with ten files | 38 | 38 |
| grails4: heap after 50 repeats of the median file | 38 | 39 |
| grails4: heap after 50 repeats of the slowest file | 42 | 39 |
| grails4: repeat x50, median file (median / p90 ms) | 5 / 7; 5 / 7 | 5 / 7; 5 / 8 |
| grails4: repeat x50, slowest file (median / p90 ms) | 10 / 21; 11 / 20 | 4 / 7; 4 / 7 |
| grails4: RSS with ten files | 211; 230 | 245; 267 |
| grails7: heap with ten files | 42 | 42 |
| grails7: heap after 50 repeats of the median file | 47 | 43 |
| grails7: heap after 50 repeats of the slowest file | 47 | 43 |
| grails7: repeat x50, median file (median / p90 ms) | 11 / 15; 13 / 20 | 5 / 10; 5 / 7 |
| grails7: repeat x50, slowest file (median / p90 ms) | 8 / 12; 9 / 16 | 4 / 7; 4 / 8 |
| grails7: RSS with ten files | 222; 241 | 249; 247 |

### What changed

- **Where the time went.** Each compile of `Sampler.groovy` parsed and resolved again the 39 other source files that it pulls in, such as `Matrix.groovy` and the classes that it uses. That took about 1 s, whether the compile was for diagnostics or for the repaired copy that completion compiles after a dot. Now each compile starts from the classes of the file's last compile when they are unchanged (see [Classes from source](../docs/how-it-works.md#classes-from-source)).
- **`m.` right after its edit still takes about 200 ms.** Completion first runs the compile of the edit, as every query does. That text ends at the dot, so it does not parse. Groovy then parses it again in ANTLR's slower LL mode, which takes most of the 200 ms. The repaired copy compiles in 5 to 7 ms, and the list takes under 1 ms. This is within the 300 ms target.
- **Memory is unchanged within the variation.** A reused class is shared by the last compile and the next one, not copied. The live heap stays flat on matrix and over the fixtures' repeats. RSS varies between runs because the JVM keeps the memory it has committed. On grails7, the class loader is not collected after close in either version, because the test runs on JDK 17 (see [Known problems found](#known-problems-found)).

## Fixtures, 2026-11-23: before and after the latency work

- **Commits.** Before: server `d215979`, before any of the latency work. After: `a1c0741`.
- **Fixtures.** `grails4` (Grails 4, Groovy 2.5.14, JDK 11.0.28, 5 compile contexts, 111 jars) and `grails7` (Grails 7, Groovy 4, JDK 17.0.19, 5 contexts, 87 jars), on Amazon Corretto. Each fixture has only a few files of each artefact kind. So they show fixed costs, such as class loaders, jars, and the parser, but not the costs that grow with a project.
- **Machine.** MacBook Air, Apple M5 (fanless), 16 GB RAM. Test heap 4096 MB. The "Before, battery" column is the first baseline, taken on battery power. The "Before, mains" column measures the same commit again on mains power with sleep prevented, and every "After" figure was taken the same way.
- **Variation.** Timings vary by about 30 % between runs. Where a figure was measured more than once, every run is given, separated by `;`.
- **Units.** Times are in milliseconds, as median / p90. n is 2 or 3 for the latency sample, 10 for each cold start group, and 50 for the repeats. Heap is in MB, after three garbage collections. The battery baseline used a single collection, which gave between 38 and 78 MB for the same code, so its heap figures are left out.

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

### What changed

- **Completion after a typed dot** now uses the last compile that parsed, when that is known to be correct. Otherwise, it compiles one repaired copy of the text. On grails4, the median went from 17 to 1 ms, and the p90 from 35–48 ms to 7–20 ms.
- **The symbol index parses in one class loader per Groovy jar**, instead of in every source set, because parsing needs no class path. It now starts 1 loader instead of 5 on each fixture. Its heap on grails4 halves, from 13 MB to 6–8 MB. On grails7, it stays within the variation (13 MB to 14–16 MB).

  The cost moves to the first compile in a source set that nobody has opened yet, which now has to start that loader itself. The latency sample's file is in such a source set, so its p90 went up:

  - grails4: 214, 309, and 292 ms before; 322, 391, and 401 ms after
  - grails7: 187, 328, and 294 ms before; 324, 410, and 375 ms after

  First diagnostics do not change: the cold start figures are within the variation. This is because the server only starts the symbol index after the first compiles of the open documents.
- **Each jar's entries are read once per workspace**, by `JarEntries`. Every module loader's jar index, import completion, and the page lookups share them. Before, each loader read every jar on its class path again, which took 11 to 21 ms per loader for about 100 jars on the fixtures. The taglib and plugin file lookups (12 ms) and import completion read them once more.

  Import completion needs the classes in each package. These are now kept from the first read of every jar. That is about 2 MB on the fixtures: the heap after the sweep grew by 2 MB. On rundeck, with 517 jars and 239 MB on disk, all of `JarEntries` holds 6.6 MB, of which the classes by package are 6 MB. That is under 0.4 % of the heap after the sweep, which is about 1,750 MB.
- **Cold start and repeated compiles** did not change beyond the variation.

### Parser cache and other decisions

#### The shared parser cache is kept

The shared parser cache (`AntlrLoader`, `SharedParser`) loads the ANTLR runtime of a Groovy 3 or 4 jar once, for all the module loaders that use that jar, and points their parsers at one prediction cache. To do this, it writes a final field of Groovy's internals, which JEP 500 restricts (see [Known limits](../docs/how-it-works.md#known-limits)).

The rule was to keep the cache only if it improved heap or p90 compile time by 15 % or more on the largest project measured. The fixtures cannot show this. grails7 is the only fixture with an ANTLR 4 parser, and its heap after the sweep is 37 MB with and without the cache. So the cache was measured on rundeck (`21f4101`, JDK 17, mains power), in two rounds in alternating order: with, then without; then without, then with.

- **Heap after the sweep** was 1756 and 1753 MB with the cache, and 2564 and 2615 MB without it. That is 32 % lower in both rounds.
- **Compile p90 by sweep group** was 12 to 22 % lower with the cache in the first round, but the run without the cache overlapped other work. In the second round, on an idle machine, it was 2 to 10 % lower: controllers 5351 / 5522 ms, services 4692 / 4810, domain 4713 / 4835, and other 3928 / 4383 (with / without). The static test's p90 was higher with the cache (4663 / 3509).
- **Heap earlier in the run** was higher with the cache. After the symbol index, it was 1814 and 1822 MB with the cache, and 1513 and 1515 without. With ten files kept, it was the same either way (2061 to 2085).

That extra heap came from the symbol index filling the shared cache without emptying it. The server now empties the cache after each batch. To check this, the latency test was run alone, twice for each setup, interleaved:

| | Heap after the symbol index (MB) | Latency sample compile p90 (ms) |
|---|---|---|
| With the cache, emptied after each batch | 296, 296 | 3872, 3749 |
| With the cache, not emptied | 571, 571 | 3835, 3859 |
| Without the cache | 295, 295 | 4017, 4082 |

#### `JarIndex` is kept

Without `JarIndex`, every lookup searches the jars. These are compiles repeated 50 times on grails4, in three runs each (median / p90, in ms):

| | Median file | Slowest file |
|---|---|---|
| Without `JarIndex` | 15 / 25, 15 / 25, 15 / 26 | 12 / 15, 12 / 18, 12 / 16 |
| With `JarIndex` | 14 / 23, 14 / 21, 13 / 23 | 10 / 13, 10 / 13, 10 / 13 |

On grails7, the two were equal within the variation.

#### One class loader per parsed file is kept

Creating a class loader for each file that the symbol index parses costs about 1 µs. Parsing and converting the grails4 fixture's controller with Groovy 2.5.14 takes about 130 µs (JDK 11, after warm-up). So the loaders are kept as they are.

## rundeck, 2026-11-24: after the latency work

- **Commits.** Server `1706504`, compared with the 2026-11-18 figures on `7224fc6`. rundeck at `21f4101`, on JDK 17.0.19.
- **Setup.** The machine, the test heap (4096 MB), and the units are the same as in the corpus sections below. Mains power, with sleep prevented.
- **Runs.** One full run (all four perf tests in one JVM, 61 minutes), then the memory test alone three times, then the cold starts. Where three runs are given, the first figure is their median.
- **Cold starts** ran on the fat jar with the fix for the JDK check (see [Known problems found](#known-problems-found)). Without that fix, the server failed on rundeck before its first compile. The perf tests do not run that check.

| rundeck | 2026-11-18 (`7224fc6`) | 2026-11-24 (`1706504`) |
|---|---|---|
| Sweep: errors / files with errors | 29 / 20 | 29 / 20 |
| Sweep compile: controllers | 290 / 4575 / 8145 | 273 / 5839 / 8933 |
| Sweep compile: services | 34 / 4151 / 7602 | 54 / 5308 / 9740 |
| Sweep compile: domain | 3599 / 4116 / 4162 | 4540 / 5187 / 5387 |
| Heap after the sweep | 1738 | 1673 |
| Static test: errors; compile | 1; 37 / 3498 / 11721 | 1; 29 / 3517 / 10289 |
| Latency sample: compile | 1217 / 5491 / 16551 | 906 / 5154 / 11453 |
| Hover | 0 / 7 / 66 | 0 / 7 / 21 |
| Completion | 4 / 6 / 8 | 0 / 0 / 250 |
| Compile with a typed dot | 7 / 5146 / 5816 | 4 / 4512 / 4737 |
| **Completion after a typed dot** | 14 / **5462** / 7139 | 1 / **59** / 104 |
| Completions with no items: compiled / typed dot | 0 / 2 | 0 / 2 |
| Symbol index: files / symbols | 1714 / 25979 | 1714 / 23766 |
| Symbol index: time; contexts it started | 29843; - | 19868; 2 |
| Heap after the symbol index | 2047 | 1570 |
| Memory test: ten files, all compiles | 35301 | 39822 (38318, 41225) |
| Memory test: heap with ten files | 1313 | 1344 (1344, 1346) |
| **RSS with ten files** | 2389 | 2505 (2272, 2575) |
| RSS after the repeats, before close | 2986 | 3331 (3115, 4129) |
| RSS after close | 2119 | 2292 (2283, 2476) |
| Loader collected by GC / once soft references are cleared | no / no | no / no (all three runs) |
| Cold start, controllers: median / p90 / max; within 3 s | 7652 / 13565 / 13913; 10 of 20 | 2552 / 13405 / 13627; 10 of 20 |
| Cold start, services: median / p90 / max; within 3 s | 12929 / 14332 / 14647; 8 of 20 | 12395 / 13254 / 13665; 8 of 20 |

### What changed

- **Completion after a typed dot now meets the target on rundeck.** The p90 went from 5462 to 59 ms. As on the fixtures, completion uses the last compile that parsed. One plain completion took 250 ms, but the median and p90 are 0.
- **The symbol index** took 19.9 s instead of 29.8 s, and started 2 contexts, one for each Groovy jar. Before, it parsed in every source set's loader. It found 2,213 fewer symbols in the same 1714 files. The index's declaration scan also changed between the two measurements, and this difference was not broken down. Heap after the index went from 2047 to 1570 MB, and heap after the sweep from 1738 to 1673 MB. Both figures come from the full run's shared JVM.
- **Sweep compiles are 26 to 28 % slower at p90** in all three groups. This is at the edge of the run-to-run variation, which is about 30 %. The latency sample and the static test are as fast or faster. Each figure comes from a single run, and the slowdown was not investigated.
- **First diagnostics are unchanged.** The same 10 of 20 controllers and 8 of 20 services are within 3 s. The files still fall into two groups, at about 2 s and about 13 s. So the change in the controllers' median, from 7.7 to 2.6 s, only shows which group the median falls in.
- **Memory is unchanged within the variation.** RSS with ten files is still above 1.5 GB. On a JDK 17 server process, the class loader is still kept after close. This is the known JDK limit described [below](#known-problems-found).

## Corpus, 2026-11-18: against the performance target

- **Server:** commit `7224fc6`.
- **Date:** 2026-11-18. The first measurements were on 2026-11-15, on `d28b216`. See [Changes since the 2026-11-15 measurements](#changes-since-the-2026-11-15-measurements-d28b216).
- **Machine:** MacBook Air, Apple M5 (fanless), 16 GB RAM, macOS 26.5.
- **JVM:** test heap 4096 MB, which is a quarter of the RAM and what `java -jar` gets by default. Each project runs on its own build JDK (see [corpus.md](corpus.md)): cherokeedictionary on 11.0.28; mdm-core, mod-agreements, and rundeck on 17.0.19; hoist-core on 21.0.13. All are Amazon Corretto.
- **Units:** times are in milliseconds, as median / p90 / max. Heap is in MB after garbage collection. RSS is the process's resident set size, in MB.

| Target | Meets it | Misses it |
|---|---|---|
| First diagnostics within 3 s (warm start, one process per file) | cherokeedictionary: every file | mod-agreements: 17 of 20 controllers and 12 of 20 services within 3 s; rundeck: 10 and 8 of 20 (medians 7.7 and 12.9 s); hoist-core: 1 and 1 of 20 (medians 3.2 and 3.4 s); mdm-core: 0 and 4 of 20 |
| Completion within 300 ms, compiled document | all five (max 8 ms) | |
| Completion within 300 ms, after a typed dot (the editor waits for the compile of the copy) | cherokeedictionary (p90 113) | hoist-core (p90 396), mod-agreements (524), mdm-core (2357), rundeck (5462) |
| RSS under 1.5 GB with ten files | cherokeedictionary 448, mdm-core 872, mod-agreements 1137 (median of three runs), hoist-core 1145 | rundeck 2389 |
| Loader released on close | cherokeedictionary, mdm-core, mod-agreements, hoist-core | rundeck on JDK 17: a JDK limit (see Known problems); released once soft references are cleared when the server runs on JDK 21 |

Since then, rundeck meets the target for completion after a typed dot. See [rundeck, 2026-11-24](#rundeck-2026-11-24-after-the-latency-work).

Errors on valid code: none on cherokeedictionary, mod-agreements, and hoist-core. mdm-core has 4, and rundeck has 29 in the sweep and 1 in the static test. Each error is explained in [Errors on valid code](#errors-on-valid-code).

Grails members, both the artefact traits and the GORM methods, are found on every controller, domain class, taglib, and `@Transactional` service of every project.

## Corpus measurement caveats

- Every run was on mains power with sleep prevented, in one sequence: the five full runs (all four perf tests in one JVM), then the memory test alone on each project, then the cold starts. This took 2 hours in all, without sleep.
- Timings vary by about 30 % between runs on this fanless machine. RSS varies more. For example, mod-agreements' RSS with ten files was 1677, 927, and 1137 MB in three runs of the memory test alone, with the same live heap. The tables give the median.
- Cold starts use the files named `*Controller.groovy` and `*Service.groovy` under `grails-app/`. The sample from the 2026-11-15 runs was not kept, so the two samples may differ.
- rundeck's service times fall into two groups, at about 2 s and about 13 s. The 13 s group is most of the `rundeckapp` services. So rundeck's median depends on the sample.
- hoist-core's medians (3.2 and 3.4 s) are 10 to 15 % above those of 2026-11-15. That is within the variation, but it moves most of its files across the 3 s line. This was not investigated.
- In a full run, RSS includes the earlier tests, so the RSS figures come from the memory test run alone. The heap figures of the full runs are taken in that shared JVM.

## Sweep: every Groovy file compiled as the open file

`LatencyTest.artefactSweep` compiles every Groovy file, one at a time, as the open file. The results are grouped by artefact kind. The "Members" rows give the files where Grails's members were found, out of all artefact files. Files that Grails does not treat as artefacts, such as classes in the controllers directory that are not named `*Controller`, are left out.

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

For each sampled file, the queries run at its first member access. Files without a member access are not measured.

In the "typed dot" rows, the member name is deleted, leaving `receiver.` as if you had just typed it. That text does not parse, so completion compiles a copy with a placeholder.

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

`LatencyTest.keptFilesRepeatsAndClose`, run alone. It uses the compile context with the most artefact files, and:

1. compiles ten files, controllers first, and keeps them
2. compiles the median file and the slowest file 50 times each
3. closes the context and checks that its class loader is collected: first by garbage collection alone, then after filling the heap until the JVM clears soft references

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

On every project, the live heap stays flat over the repeats. RSS keeps growing after the ten files, because the JVM keeps the heap memory that it has committed. So after 100 more compiles, mod-agreements and hoist-core are at or above 1.5 GB, although their live heap stays under 450 MB. Heap flags for the launcher are a question for distribution.

## Cold start: time to first diagnostics

Each file gets its own server process, started over stdio the way an editor starts it: `initialize`, then `didOpen` at once. The server is the fat jar of `7224fc6`. A first run, which is not timed, fills the project model cache, so every timed run is a warm start. The time is the server's own `startup: first diagnostics after N ms` log line.

For each project, the sample is up to 20 controllers (`*Controller.groovy`) and 20 services (`*Service.groovy`) under `grails-app/` in all modules, spread evenly over the sorted file list.

| | Controllers: n, median / p90 / max | Within 3 s | Services: n, median / p90 / max | Within 3 s |
|---|---|---|---|---|
| cherokeedictionary | 20, 779 / 847 / 1054 | 20 | 13, 711 / 825 / 908 | 13 |
| mdm-core | 20, 5729 / 6908 / 7283 | 0 | 20, 4049 / 5935 / 6559 | 4 |
| mod-agreements | 20, 2472 / 3290 / 3938 | 17 | 20, 2688 / 4054 / 4148 | 12 |
| rundeck | 20, 7652 / 13565 / 13913 | 10 | 20, 12929 / 14332 / 14647 | 8 |
| hoist-core | 20, 3224 / 3519 / 4007 | 1 | 20, 3414 / 3757 / 3941 | 1 |

Every timed file got diagnostics, and none of them had errors.

## Errors on valid code

- **mdm-core: 4 errors, all known limits.**
  - 2 bound violations, `The type D is not a valid substitute for the bounded parameter <D extends ...Diffable>`, in `DataType` and `ReferenceDataType`. `D` is unbounded in the source. Groovy 4 checks bounds against class files, but Groovy 2.5 and 3 do not.
  - 2 Spock errors, `Only @Shared and static fields may be accessed from here`, in `mdm-testing-functional` (`UserAccessAndPermissionChangingFunctionalSpec` and `UserAccessAndCopyingInDataModelsFunctionalSpec`). groovyc gives the same 2 errors when the sibling trait `SecurityDefinition` is compiled from source, and none against the built jar. The server compiles sibling sources.
- **rundeck sweep: 29 errors in 20 files, all in test source sets.** They name Java test classes that were not built, because the corpus only builds `classes`, which covers the main source sets. The classes are `AbstractBaseTest` (18), `TestDirPluginScanner` (2), and `TestJarPluginProviderLoader` (7) in `core/src/test/java`, and `WorkflowExecutionListenerTest.java` under `rundeckapp/src/test/groovy` (2).
- **rundeck static test: 1 error**, on `AbstractBaseTest`, an unbuilt test class as above.

## Changes since the 2026-11-15 measurements (`d28b216`)

Between the two measurements, the server gained library sources for definition and hover, GORM finders and criteria, and one parser cache per Groovy jar, shared by all compile contexts. It also stopped writing compile output into the working directory. Before that fix, each compile rewrote a `grails-plugin.xml` below the working directory, and the file grew with every artefact compiled. So the 2026-11-15 compile times include a cost that grew during each run. The 2026-11-15 full runs were also on battery power, while their memory and cold start runs were on mains.

- **Heap after the sweep:** mdm-core 3385 → 1981 MB and rundeck 2565 → 1738 MB, from the shared parser cache. hoist-core 891 → 654 MB, and mod-agreements 436 → 524 MB.
- **Compile times:** lower in most groups on every project, by up to about 40 % at the median. The exception is rundeck's latency sample (compile p90 4731 → 5491, completion after a typed dot p90 4597 → 5462), which is within the run-to-run variation.
- **Cold starts:** faster on cherokeedictionary, mdm-core, and mod-agreements, where controllers within 3 s went from 7 to 17. Slower on hoist-core, where controllers within 3 s went from 16 to 1. On rundeck, the controllers were faster (median 10.5–14 s → 7.7 s) and the services slower (14 → 8 within 3 s), perhaps because of a different sample (see [Corpus measurement caveats](#corpus-measurement-caveats)).
- **RSS with ten files:** mdm-core 1087 → 872 MB, rundeck 2874 → 2389 MB. The others changed within the variation.
- **Class loader release:** hoist-core's class loader is now collected by garbage collection alone. Before, it was only collected once soft references were cleared.
- **rundeck sweep:** 30 → 29 errors. The fixed error came from Java build output that names a Groovy source (see [Known problems found](#known-problems-found)).
- **Hover max:** 1 → 66 ms. The first hover on a library class may read its sources jar for the documentation.

## Changes since the first measurements (2026-11-14)

The first measurements were taken before the first ten fixes.

- **mdm-core:** the sweep now completes. Before, it timed out after 613 files. Errors went from 111 in 58 files to the 4 known limits, and static test errors from 10 to 0. The services p90 went from 11029 to 2364 ms, because Groovy's parser cache is now kept.
- **mod-agreements:** the latency test passes. Before, it hung on an Elvis expression that was its own parent. Errors went from 1 to 0.
- **hoist-core:** errors went from 2 to 0. Its class loader, which was not collected then, is held only by soft references, as the new check confirms.
- **rundeck:** first results. Before, the static test ran out of heap.

## Known problems found

### Known limit: on JDK 17, rundeck's class loader is not released

On a JDK 17 server process, rundeck's compile context loader survives close, even once soft references are cleared. The heap goes from 1643 to 690 MB, and only 599 of 11345 loaded classes are unloaded.

A heap dump after close shows that the cause is in the JDK, not in the server. On JDK 17, `MethodHandle.asTypeCache` is a strong field. Some method handles are kept in JDK statics:

- `java.lang.runtime.ObjectMethods.OBJECT_EQ` and `OBJECTS_HASHCODE`, used by record classes
- `MethodHandleImpl.ARRAYS`, reached from Groovy 4's `invokedynamic` call sites

These handles keep the last type they were adapted to, and that type's classes belong to the context's loader. Examples are Micronaut's source generation records and Groovy's AST classes.

JDK 21 keeps such types behind a soft reference. The same test with the server's JVM on 21.0.13, and the project still on JDK 17 with 0 errors, gives a different result. The loader is not collected by garbage collection alone, but it is collected once soft references are cleared. The heap after close is 10 MB, and loaded classes go from 11245 to 4698. That run compiled each repeated file once, not 50 times.

This was only seen on rundeck, the one Groovy 4 project measured on JDK 17. There is nothing to fix in the server. The launcher should prefer JDK 21 or newer for the server process.

### Fixed (2026-11-24): the JDK check failed on a module with only Java in its main source set

At startup, the server checks that the project's Groovy can read the JDK's class files. It does this in the first compile context of each Groovy version. rundeck's `core` module applies the groovy plugin, but its main source set holds only Java, and Groovy is only on its test class path. That context has no Groovy to load the analysis layer with (`NoClassDefFoundError: org/codehaus/groovy/ast/AnnotatedNode`). So the server reported `failed` and published no diagnostics.

The check now only runs in contexts that have a Groovy jar. The bug was introduced after the 2026-11-18 measurements, and was never released. The perf tests skip the check, so only the cold starts showed it.

### Fixed: Java build output that names Groovy sources

A compile failed when a Java class from the build output had member types that name a Groovy class not yet in the compile. On rundeck, this was `JettyCombinedLdapLoginModuleSpec`, with `NoClassDefFoundError: rundeck.services.ConfigurationService`. It was the 30th sweep error of 2026-11-15. A document that hits this is now compiled twice.

### Missed targets on larger projects

- First diagnostics take more than 3 s on mdm-core, rundeck, and hoist-core, and on part of mod-agreements.
- Completion after a typed dot takes more than 300 ms at p90 on every project except cherokeedictionary (2026-11-18). Since the latency work, rundeck meets the target.
- RSS with ten files is above 1.5 GB on rundeck.

## Rerunning

Clone and build each project as described in [corpus.md](corpus.md). Then run the perf tests. They are not part of `build`, and they only write counts and timings, to `server/build/perf/`:

```sh
JAVA_HOME=<JDK 17> GLS_TEST_JDK11=<JDK 11> GLS_TEST_JDK17=<JDK 17> \
GLS_PERF_PROJECT=<built project> GLS_PERF_JDK=<project JDK> GLS_PERF_DETAILS=<error texts file, outside the repo> \
  ./gradlew :server:perfTest -PtestJavaHome=<project JDK>
```

Without `GLS_PERF_PROJECT`, the tests measure the `grails4` fixture. To measure `grails7`, set `GLS_PERF_PROJECT=$PWD/fixtures/grails7 GLS_PERF_JDK=<JDK 17>`. The fixture figures above were taken this way, with `--repeat 10` for the cold starts. The tests write `sweep.txt`, `static.txt`, `latency.txt`, and `memory.txt`.

To measure RSS, run the memory test alone by adding `--tests '*LatencyTest.keptFilesRepeatsAndClose'`. `GLS_PERF_HEAP` sets the heap, for example `4g`. `GLS_PERF_FILES` sets the size of the latency sample (default 40).

To measure cold starts, build the fat jar with `./gradlew :server:shadowJar`, then run:

```sh
python3 benchmarks/cold-start.py --project <built project> --java <project JDK>/bin/java
```

`--files` sets the sample size for each group (default 20), and `--repeat` sets the number of timed runs for each file (default 1).

Take measurements on mains power with sleep prevented (`caffeinate -i` on macOS), and with no other measurement running.
