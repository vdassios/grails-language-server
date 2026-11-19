package bookstore

class Book {
    String title
    String isbn
    BigDecimal price
    Date published

    static belongsTo = [author: Author]

    static constraints = {
        isbn unique: true
        price min: 0.0
    }
}
