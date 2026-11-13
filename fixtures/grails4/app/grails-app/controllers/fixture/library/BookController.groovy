package fixture.library

import fixture.isbn.Isbn
import fixture.storage.Shelf
import fixture.text.TitleCase

class BookController {
    BookService bookService

    def index() {
        render(view: "index", model: [books: Book.list()])
    }

    def show(Long id) {
        Book book = Book.get(id)
        Book.Format format = book?.format ?: Book.Format.PAPERBACK
        [book: book, format: format, shelf: new Shelf(format.name())]
    }

    def save(String title, String isbn) {
        Book book = new Book(title: TitleCase.of(title), isbn: Isbn.normalize(isbn))
        bookService.store(book)
        redirect(action: "show", id: book.id)
    }
}
