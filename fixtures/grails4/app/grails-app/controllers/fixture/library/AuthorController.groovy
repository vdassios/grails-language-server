package fixture.library

class AuthorController {
    static layout = "main"
    static allowedMethods = [save: "POST"]

    def index() {
        render(template: "/book/row")
    }

    def legacy() {
        render(view: "/book/legacy")
    }

    def latest() {
        respond([], view: "/book/index")
    }

    def save() {
        redirect(controller: "book", action: "index")
    }

    def again() {
        chain(action: "index")
    }

    def inside() {
        forward(controller: "book", action: "show")
    }

    def links() {
        [list: createLink(controller: "book"), first: g.link(action: "legacy") { "first" }]
    }
}
