package fixture.catalog

class ItemTagLib {
    static namespace = "catalog"

    // untyped injection: the bean named itemService
    def itemService

    def names = { attrs ->
        for (Item item in itemService.named(attrs.wanted)) {
            out << item.name
        }
    }
}
