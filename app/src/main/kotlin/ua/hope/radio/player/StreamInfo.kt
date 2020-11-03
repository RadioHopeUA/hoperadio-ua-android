package ua.hope.radio.player

data class StreamInfo(val title: String, val artist: String) {
    companion object {
        fun from(info: String): StreamInfo {
            val splitted = info.split(" - ")
            return StreamInfo(splitted[1], splitted[0])
        }

        val EMPTY = StreamInfo("", "")
    }
}
