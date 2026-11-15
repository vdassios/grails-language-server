package fixture.library

class BookShelving {
    BookFactory factory = new BookFactory()

    String firstTitle() {
        factory.paperback('Dune').title
    }
}
