package fixture.library

class BookTagLib {
    static namespace = "library"

    // untyped injection: the bean named bookService
    def bookService

    /**
     * Writes the books whose name starts with a prefix.
     *
     * @attr wanted REQUIRED the prefix
     * @attr max how many at most
     */
    def titles = { attrs ->
        for (Book book in bookService.titled(attrs.wanted)) {
            out << book.title
        }
    }
}
