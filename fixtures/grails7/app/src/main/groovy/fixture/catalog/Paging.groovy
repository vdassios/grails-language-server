package fixture.catalog

/** Actions shared by controllers that extend it. */
abstract class Paging {
    def page(Integer max) {
        [max: Math.min(max ?: 10, 100)]
    }
}
