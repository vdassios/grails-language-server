package fixture.library

class BookTagLib {
    static namespace = "library"

    // untyped injection: the bean named bookService
    def bookService

    def titles = { attrs ->
        for (Book book in bookService.titled(attrs.wanted)) {
            out << book.title
        }
    }
}
