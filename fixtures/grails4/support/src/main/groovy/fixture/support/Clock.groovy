package fixture.support

import org.apache.commons.text.WordUtils

class Clock {
    static Date now() {
        new Date()
    }

    static String weekday(Date date) {
        WordUtils.capitalizeFully(date.format("EEEE"))
    }
}
