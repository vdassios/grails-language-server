package fixture.library

// A test helper that an integration test uses: Grails puts test's output on integrationTest's classpath.
class BookFactory {
    Book paperback(String title) {
        new Book(title: title, format: Book.Format.PAPERBACK)
    }
}
