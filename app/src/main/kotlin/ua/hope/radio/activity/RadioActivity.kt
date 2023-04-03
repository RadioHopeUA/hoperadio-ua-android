package ua.hope.radio.activity

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.koin.androidx.viewmodel.ext.android.viewModel
import ua.hope.radio.BuildConfig
import ua.hope.radio.R
import ua.hope.radio.databinding.ActivityMainBinding
import ua.hope.radio.player.AudioPlaybackService
import ua.hope.radio.player.PlayerState
import ua.hope.radio.player.PlayerViewModel
import ua.hope.radio.player.ServiceState
import ua.hope.radio.player.TracksMetadata

/**
 * Created by vitalii on 8/7/17.
 */
open class RadioActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val playerViewModel: PlayerViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        playerViewModel.serviceStatus.observe(this) {
            when (it) {
                is ServiceState.Bind -> bindService(
                    Intent(this, AudioPlaybackService::class.java),
                    it.connection,
                    Context.BIND_AUTO_CREATE
                )
                is ServiceState.UnBind -> unbindService(it.connection)
            }
        }
        playerViewModel.playerStatus.observe(this) {
            binding.audioPrefIv.visibility = View.INVISIBLE
            binding.playerStatusTv.text = when (it) {
                is PlayerState.Buffering -> getString(R.string.status_buffering)
                is PlayerState.Playing -> {
                    binding.playerControlView.player = it.player
                    binding.audioPrefIv.visibility = View.VISIBLE
                    ""
                }
                is PlayerState.Stopped -> {
                    binding.playerControlView.player?.stop()
                    binding.playerControlView.player?.clearMediaItems()
                    ""
                }
                is PlayerState.Error -> {
                    Toast.makeText(this, getString(R.string.status_error), Toast.LENGTH_LONG).show()
                    getString(R.string.status_error)
                }
            }
        }
        playerViewModel.streamInfo.observe(this) {
            binding.songNameTv.text = it.title
            binding.artistNameTv.text = it.artist
        }
        binding.playerControlView.findViewById<ImageView>(R.id.exo_play).setOnClickListener {
            playerViewModel.play(getString(R.string.radio_stream_url))
        }

        binding.socialIv.setOnClickListener {
            //Creating the instance of PopupMenu
            val wrapper: Context = ContextThemeWrapper(baseContext, R.style.AppTheme)
            val popup = PopupMenu(wrapper, binding.socialIv)
            //Inflating the Popup using xml file
            popup.menuInflater.inflate(R.menu.www_popup, popup.menu)

            //registering popup with OnMenuItemClickListener
            popup.setOnMenuItemClickListener { item ->
                val url = when (item.itemId) {
                    R.id.website -> getString(R.string.website_url)
                    R.id.fb -> getString(R.string.fb_url)
                    R.id.twitter -> getString(R.string.twitter_url)
                    R.id.write_us -> getString(R.string.mailto_url)
                    else -> null
                }
                if (url != null) {
                    val intent = Intent()
                    if (item.itemId == R.id.write_us) {
                        intent.action = Intent.ACTION_SEND
                        intent.type = "text/plain"
                        intent.putExtra(Intent.EXTRA_EMAIL, arrayOf(url))
                        intent.putExtra(
                            Intent.EXTRA_SUBJECT,
                            "\"" + getString(R.string.app_name) + "\" (Android) ${BuildConfig.VERSION_NAME}.${BuildConfig.VERSION_CODE}"
                        )
                        intent.putExtra(
                            Intent.EXTRA_TEXT,
                            "---------------------\n" +
                                    "Android: ${Build.VERSION.RELEASE}\n" +
                                    "Manufacturer: ${Build.MANUFACTURER}\n" +
                                    "Model: ${Build.MODEL}\n" +
                                    "---------------------\n\n"
                        )
                    } else {
                        intent.action = Intent.ACTION_VIEW
                        intent.data = Uri.parse(url)
                    }
                    if (packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                        startActivity(intent)
                    }
                }
                true
            }
            popup.show()
        }

        val mPopupMenu = PopupMenu(this, binding.audioPrefIv)
        mPopupMenu.setOnMenuItemClickListener { item: MenuItem ->
            if (item.groupId == MENU_GROUP_TRACKS) {
                playerViewModel.selectTrack(item.itemId)
                return@setOnMenuItemClickListener true
            }
            false
        }
        binding.audioPrefIv.setOnClickListener {
            val tracksMetadata = playerViewModel.getTracksMetadata()
            val tracks = tracksMetadata?.tracks
            if (tracks != null) {
                mPopupMenu.menu.clear()
                tracks.forEach {
                    if (it.key == TracksMetadata.ADAPTIVE) {
                        mPopupMenu.menu.add(
                            MENU_GROUP_TRACKS,
                            it.key,
                            Menu.NONE,
                            getString(R.string.audio_auto)
                        )
                    } else {
                        mPopupMenu.menu.add(
                            MENU_GROUP_TRACKS,
                            it.key,
                            Menu.NONE,
                            getString(R.string.audio_kbits, it.value)
                        )
                    }
                }
                mPopupMenu.menu.setGroupCheckable(MENU_GROUP_TRACKS, true, true)
                mPopupMenu.menu.findItem(tracksMetadata.selected).isChecked = true
                mPopupMenu.show()
            }
        }
    }

    companion object {
        private const val MENU_GROUP_TRACKS = 1
    }
}
