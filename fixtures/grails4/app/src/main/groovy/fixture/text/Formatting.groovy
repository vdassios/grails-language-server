package fixture.text

// The file is named after neither class: Groovy's default lookup (a/b/C.groovy) cannot find them.
class TitleCase {
    static String of(String text) {
        text?.split(' ')?.collect { it.capitalize() }?.join(' ')
    }
}

class Initials {
    static String of(String text) {
        text?.split(' ')?.collect { it[0] }?.join('')
    }
}
