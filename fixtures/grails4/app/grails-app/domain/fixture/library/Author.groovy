package fixture.library

class Author {
    String name

    static hasMany = [books: Book]
}
