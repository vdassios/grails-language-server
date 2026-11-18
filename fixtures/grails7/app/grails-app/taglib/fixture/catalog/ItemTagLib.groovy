package fixture.catalog

class ItemTagLib {
    static namespace = "catalog"

    // untyped injection: the bean named itemService
    def itemService

    /**
     * Writes the items whose name starts with a prefix.
     *
     * @attr wanted REQUIRED the prefix
     * @attr max how many at most
     */
    def names = { attrs ->
        for (Item item in itemService.named(attrs.wanted)) {
            out << item.name
        }
    }
}
