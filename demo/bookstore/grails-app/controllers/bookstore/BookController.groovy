package bookstore

class BookController {
    static layout = 'main'

    BookService bookService

    def index(String author) {
        [books: bookService.byAuthor(author)]
    }

    def show(Long id) {
        Book book = Book.get(id)
        render(view: 'show', model: [book: book])
    }
}
