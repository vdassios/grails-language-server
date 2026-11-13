package fixture.text;

/** Java source inside src/main/groovy (joint-compiled by default). */
public final class Slug {
    private Slug() {}

    public static String of(String text) {
        return text.toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }
}
