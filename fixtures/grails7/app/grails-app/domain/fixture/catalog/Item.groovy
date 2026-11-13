package fixture.catalog

class Item {
    enum Kind { TOOL, PART }

    String name
    Kind kind = Kind.PART

    static constraints = {
        name blank: false
    }
}
