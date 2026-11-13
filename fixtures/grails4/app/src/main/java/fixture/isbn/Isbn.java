package fixture.isbn;

/** Java source in src/main/java, compiled by the Groovy compiler together with the Groovy sources. */
public final class Isbn {
    private Isbn() {}

    public static String normalize(String raw) {
        return raw == null ? null : raw.replace("-", "").trim();
    }
}
