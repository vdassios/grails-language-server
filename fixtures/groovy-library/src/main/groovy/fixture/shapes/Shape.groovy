package fixture.shapes

trait Named {
    String name
}

abstract class Shape implements Named {
    abstract double area()
}

class Square extends Shape {
    double side

    double area() {
        side * side
    }
}
