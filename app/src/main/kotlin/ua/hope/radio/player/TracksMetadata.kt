package ua.hope.radio.player

class TracksMetadata {
    val tracks = mutableMapOf<Int, Int>()
    var selected = ADAPTIVE

    companion object {
        const val ADAPTIVE = 100
    }
}
