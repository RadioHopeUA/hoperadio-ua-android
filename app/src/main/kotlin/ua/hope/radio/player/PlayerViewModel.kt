package ua.hope.radio.player

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import com.google.android.exoplayer2.Player
import timber.log.Timber
import ua.hope.radio.utils.SingleLiveEvent
import javax.inject.Inject

class PlayerViewModel @Inject constructor() : ViewModel() {
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

    private val _playerStatus: SingleLiveEvent<PlayerStatus> = AudioPlaybackService.status
    val playerStatus: LiveData<PlayerStatus> = _playerStatus

    private val _serviceStatus: SingleLiveEvent<ServiceStatus> = SingleLiveEvent()
    val serviceStatus: LiveData<ServiceStatus> = _serviceStatus

    private val _streamInfo: SingleLiveEvent<StreamInfo> = AudioPlaybackService.trackInfo
    val streamInfo: LiveData<StreamInfo> = _streamInfo

    init {
        bindToAudioService()
    }

    private fun bindToAudioService() {
        if (connection.audioPlaybackService == null) {
            _serviceStatus.postValue(ServiceStatus.Bind(connection))
        }
    }

    private fun unbindAudioService() {
        if (connection.audioPlaybackService != null) {
            _serviceStatus.postValue(ServiceStatus.UnBind(connection))
            connection.audioPlaybackService = null
        }
    }

    fun play(url: String) {
        try {
            connection.audioPlaybackService?.play(url)
        } catch (t: Throwable) {
            Timber.e(t)
            _playerStatus.postValue(PlayerStatus.Error)
        }
    }

    fun getTracksMetadata() = connection.audioPlaybackService?.getTracksMetadata()

    fun selectTrack(id: Int) = connection.audioPlaybackService?.selectTrack(id)

    override fun onCleared() {
        unbindAudioService()
    }
}

sealed class PlayerStatus() {
    object Bufferring : PlayerStatus()
    class Playing(val player: Player) : PlayerStatus()
    object Stopped : PlayerStatus()
    object Error : PlayerStatus()
}

sealed class ServiceStatus {
    class Bind(val connection: ServiceConnection) : ServiceStatus()
    class UnBind(val connection: ServiceConnection) : ServiceStatus()
}
