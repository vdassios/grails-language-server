package bookstore

class Author {
    String name

    static hasMany = [books: Book]
}
