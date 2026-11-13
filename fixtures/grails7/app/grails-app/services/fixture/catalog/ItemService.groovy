package fixture.catalog

import fixture.support.Clock
import grails.gorm.transactions.Transactional

@Transactional
class ItemService {
    Item add(String name) {
        log.info("added at ${Clock.now()}")
        new Item(name: name).save(flush: true)
    }
}
