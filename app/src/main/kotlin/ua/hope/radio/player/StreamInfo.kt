package ua.hope.radio.player

import timber.log.Timber

data class StreamInfo(val title: String, val artist: String) {
    companion object {
        fun from(info: String): StreamInfo {
            val splitted = info.split(" - ")
            return if (splitted.size >= 2) {
                StreamInfo(splitted[1], splitted[0])
            } else {
                Timber.e("Unable to parse stream info: $info")
                StreamInfo(info, "")
            }
        }

        val EMPTY = StreamInfo("", "")
    }
}
