package fixture.catalog

class ItemController {
    ItemService itemService

    def index() {
        render(view: "index", model: [items: Item.list()])
    }

    def show(Long id) {
        [item: Item.get(id), kind: Item.Kind.TOOL]
    }
}
