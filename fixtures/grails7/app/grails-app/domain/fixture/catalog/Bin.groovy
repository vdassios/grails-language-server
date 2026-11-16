package fixture.catalog

class Bin {
    String label

    static hasMany = [items: Item]
}
