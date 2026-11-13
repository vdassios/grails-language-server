package fixture.library

class Book {
    // nested type used from a controller
    enum Format { HARDCOVER, PAPERBACK, EBOOK }

    String title
    String isbn
    Format format = Format.PAPERBACK
    Author author

    static constraints = {
        title blank: false
        isbn nullable: true
    }
}
