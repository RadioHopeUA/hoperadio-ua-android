package ua.hope.radio.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

internal class StreamInfoTest {

    @ParameterizedTest
    @CsvSource(
        "Adele - Hello, Adele, Hello",
        "Adele - Hello - 25, Adele, Hello - 25",
        "Радіо \"Голос Надії\" - На максимум (прямий ефір), Радіо \"Голос Надії\", На максимум (прямий ефір)",
        "' - ', '', ''",
        "'', '', ''",
        "Unexpected title, Unexpected title, ''",
    )
    fun testFrom(input: String, expectedArtist: String, expectedTitle: String) {
        val streamInfo = StreamInfo.from(input)

        assertEquals(expectedArtist, streamInfo.artist)
        assertEquals(expectedTitle, streamInfo.title)
    }
}