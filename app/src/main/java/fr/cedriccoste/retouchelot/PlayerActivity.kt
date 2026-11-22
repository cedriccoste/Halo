package fr.cedriccoste.retouchelot

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Lecteur vidéo plein écran de Halo.
 * Il utilise les décodeurs du téléphone (comme Google Photos ou la Galerie), donc il lit aussi les
 * formats que la visionneuse intégrée ne sait pas lire : HEVC / H.265, HDR, vidéos d'iPhone, .mov…
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) { finish(); return }
        val view = PlayerView(this)
        view.setBackgroundColor(Color.BLACK)
        view.keepScreenOn = true
        setContentView(view)
        player = ExoPlayer.Builder(this).build().also {
            view.player = it
            it.setMediaItem(MediaItem.fromUri(uri))
            it.prepare()
            it.playWhenReady = true
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
