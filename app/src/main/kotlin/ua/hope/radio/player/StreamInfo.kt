package ua.hope.radio.player

import timber.log.Timber

data class StreamInfo(val artist: String, val title: String) {
    companion object {
        fun from(info: String): StreamInfo {
            val delimiterIdx = info.indexOf(DELIMITER)
            return if (delimiterIdx == -1) {
                Timber.e("Unable to parse stream info: \"$info\"")
                StreamInfo(info, "")
            } else {
                return StreamInfo(info.substring(0 until delimiterIdx), info.substring(delimiterIdx + DELIMITER.length until info.length))
            }
        }

        val EMPTY = StreamInfo("", "")
        private const val DELIMITER = " - "
    }
}
