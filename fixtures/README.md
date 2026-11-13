# Test fixtures

Invented projects that the tests build with their own Gradle wrappers. Each must compile with `./gradlew classes`.

| Fixture | Grails | Groovy | Gradle | JDK | Modules |
|---|---|---|---|---|---|
| `grails4` | 4.1.4 | 2.5.14 | 6.9.4 | 11 | `app` (Grails web), `support` (plain Groovy library) |
| `grails7` | 7.2.4 | 4.0.33 | 8.14.3 | 17 | `app` (Grails web), `support` (plain Groovy library) |
| `groovy-library` | none | 3.0.25 | 8.14.3 | 17 | one |

Shapes in `grails4/app` that Groovy's own per-file compile gets wrong (each needs the server's declaration index, path-based resolver or Java class view):

- Joint compilation: `src/main/java` is compiled by `compileGroovy` (`compileJava` has no sources), and `src/main/groovy/fixture/text/Slug.java` sits in a Groovy source dir.
- A nested enum in a domain class used from a controller: `Book.Format` in `BookController`.
- Classes in a file of another name: `TitleCase` and `Initials` in `Formatting.groovy`.
- A package that differs from its directory: `fixture.storage.Shelf` in `fixture/misc/`.
