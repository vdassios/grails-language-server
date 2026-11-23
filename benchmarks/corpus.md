# Public benchmark corpus

Public Grails projects the server is measured on, besides its own fixtures. They were picked to cover different shapes (Grails and Groovy versions, multi-module builds, Java compiled together with Groovy, artefact kinds, size), not for popularity. Each one is pinned to a commit and must build with its own Gradle wrapper on a JDK available here.

## Projects

| Project | Commit | Grails / Groovy | Build JDK | Licence | Shapes it covers |
|---|---|---|---|---|---|
| [CherokeeLanguage/cherokeedictionary](https://github.com/CherokeeLanguage/cherokeedictionary) | `53dfffbdd173` | 4.0.11 / 2.5.14 | 11 | MIT | Grails 4 on Groovy 2.5; taglibs (8) and GSP (106); one module |
| [MauroDataMapper/mdm-core](https://github.com/MauroDataMapper/mdm-core) | `a5ec88aeb0b7` | 5.3.2 / 3.0.11 | 17 | Apache-2.0 | Grails 5; 16 Gradle builds, 12 with `grails-app`, plugin modules depending on `mdm-core`, `mdm-security` and each other; 132 controllers; 3 Java files compiled with Groovy |
| [folio-org/mod-agreements](https://github.com/folio-org/mod-agreements) (`service/`) | `2f6bd141ee5f` | 6.2.3 / 3.0.23 | 17 | Apache-2.0 | Grails 6; heavy GORM use (61 domain classes, Hibernate with schema multi-tenancy) |
| [rundeck/rundeck](https://github.com/rundeck/rundeck) | `21f4101fcf98` | 7.2.2 / 4.0.33 | 17 | Apache-2.0 | Grails 7 on Groovy 4; largest: 49 Gradle builds, 1787 Groovy files, 1251 Java files (48 inside `src/main/groovy`); all four artefact kinds, taglibs (9) and GSP (260) |
| [xh/hoist-core](https://github.com/xh/hoist-core) | `95fa3b3c6429` | 8.0.0 / 5.1.3 | 21, toolchains 17 and 25 | Apache-2.0 | Grails 8 on Groovy 5; a Grails plugin, not an app; 14 Java files compiled with Groovy |

Artefact counts (Groovy files under `grails-app/`, all modules):

| Project | Controllers | Domain | Services | Taglibs | GSP | Groovy files |
|---|---|---|---|---|---|---|
| cherokeedictionary | 22 | 33 | 13 | 8 | 106 | 104 |
| mdm-core | 132 | 58 | 107 | 0 | 0 | 1297 |
| mod-agreements | 35 | 61 | 32 | 0 | 0 | 276 |
| rundeck | 67 | 23 | 120 | 9 | 260 | 1787 |
| hoist-core | 33 | 9 | 44 | 0 | 0 | 312 |

## Building

Each project is cloned at its commit (`git fetch --depth 1 origin <sha>`) and its main classes are built, so that the server finds class files for the Java sources it cannot compile per file:

```sh
JAVA_HOME=<JDK 11> ./gradlew --no-daemon --continue classes                      # cherokeedictionary
JAVA_HOME=<JDK 17> ./gradlew --no-daemon --continue classes                      # mdm-core; mod-agreements inside service/
JAVA_HOME=<JDK 17> ./gradlew --no-daemon --continue -x runNpmBuild -x vendorAssetDeps classes   # rundeck: skips its npm front-end builds
JAVA_HOME=<JDK 21> ./gradlew --no-daemon --continue \
  -Porg.gradle.java.installations.paths=<JDK 17>,<JDK 25> \
  -Porg.gradle.java.installations.auto-detect=false classes                      # hoist-core: its toolchains ask for 17 and 25
```

All five exit 0 (2026-11-14). Only main source sets are built; test source sets are measured but their Java files may be reported as not built.

## Candidates left out

| Candidate | Grails | Reason |
|---|---|---|
| openboxes/openboxes | 3.3.16 / Groovy 2.4.21 | Needs JDK 8 to build; Groovy 2.4 is below the server's minimum (2.5). The largest public Grails app found (170 controllers, 119 domain classes, 23 taglibs, 622 GSP): the first candidate if Grails 3 is ever supported. |
| rundeck/rundeck v3.4.10 | 4.0.3 / 2.5.6 | Needs JDK 8 (its plugin modules inherit the build JVM's version, the app asks for 8); the server needs Java 11 or later. |
| cbmarcum/mybusiness | 4.1.2 | Two dependencies are gone from their repository; does not build. |
| GMOD/Apollo3Server | 4.0.4 | A plugin's repository is gone (its POM no longer parses); does not build. Would have covered Neo4j GORM. |
| AtlasOfLivingAustralia ecodata, fieldcapture, volunteer-portal | 5.3 to 6.2 | No licence file. volunteer-portal (taglibs and GSP on Grails 5) and ecodata (MongoDB GORM) would have filled gaps below. |
| streamaserver/streama, dellermann/springcrm | 3.2 | Grails 3 (Groovy 2.4), as openboxes. |
| openlibraryenvironment/mod-rs | 5.3.2 | Not tried; spare candidate with many services (183). |

## Shapes no project covers

- Grails 3 and Groovy 2.4, and any project that builds only on Java 8.
- A Grails 4 build with several modules and Java compiled together with Groovy (only the private project the server was developed against has it).
- Taglibs and GSP on Grails 5 or 6.
- GORM for datastores other than Hibernate (MongoDB, Neo4j).
