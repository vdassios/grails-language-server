package fixture.library

import fixture.support.Clock
import grails.gorm.transactions.Transactional

@Transactional
class BookService {
    Book store(Book book) {
        book.save(flush: true)
        log.info("stored at ${Clock.now()}")
        book
    }
}
