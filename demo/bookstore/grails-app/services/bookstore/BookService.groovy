package bookstore

import grails.gorm.transactions.Transactional

@Transactional(readOnly = true)
class BookService {
    List<Book> byAuthor(String name) {
        Book.findAllByAuthor(Author.findByName(name), [sort: 'title'])
    }

    List<Book> search(String text) {
        Book.findAllByTitleIlike("%${text}%")
    }
}
