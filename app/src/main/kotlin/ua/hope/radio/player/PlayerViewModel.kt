package ua.hope.radio.player

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import com.google.android.exoplayer2.Player
import timber.log.Timber
import ua.hope.radio.utils.SingleLiveEvent

class PlayerViewModel : ViewModel() {
    private val connection = object : ServiceConnection {
        var audioPlaybackService: AudioPlaybackService? = null

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioPlaybackService.AudioServiceBinder
            audioPlaybackService = binder.service
            Timber.d("Connected to ${AudioPlaybackService::class.java} service")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioPlaybackService = null
            Timber.d("Disconnected from ${AudioPlaybackService::class.java} service")
        }
    }

    private val _playerStatus: SingleLiveEvent<PlayerState> = AudioPlaybackService.status
    val playerStatus: LiveData<PlayerState> = _playerStatus

    private val _serviceStatus: SingleLiveEvent<ServiceState> = SingleLiveEvent()
    val serviceStatus: LiveData<ServiceState> = _serviceStatus

    private val _streamInfo: SingleLiveEvent<StreamInfo> = AudioPlaybackService.trackInfo
    val streamInfo: LiveData<StreamInfo> = _streamInfo

    init {
        bindToAudioService()
    }

    private fun bindToAudioService() {
        if (connection.audioPlaybackService == null) {
            _serviceStatus.postValue(ServiceState.Bind(connection))
        }
    }

    private fun unbindAudioService() {
        if (connection.audioPlaybackService != null) {
            _serviceStatus.postValue(ServiceState.UnBind(connection))
            connection.audioPlaybackService = null
        }
    }

    fun play(url: String) {
        try {
            connection.audioPlaybackService?.play(url)
        } catch (t: Throwable) {
            Timber.e(t)
            _playerStatus.postValue(PlayerState.Error)
        }
    }

    fun getTracksMetadata() = connection.audioPlaybackService?.getTracksMetadata()

    fun selectTrack(id: Int) = connection.audioPlaybackService?.selectTrack(id)

    override fun onCleared() {
        unbindAudioService()
    }
}

sealed interface PlayerState {
    object Buffering : PlayerState
    class Playing(val player: Player) : PlayerState
    object Stopped : PlayerState
    object Error : PlayerState
}

sealed interface ServiceState {
    class Bind(val connection: ServiceConnection) : ServiceState
    class UnBind(val connection: ServiceConnection) : ServiceState
}
