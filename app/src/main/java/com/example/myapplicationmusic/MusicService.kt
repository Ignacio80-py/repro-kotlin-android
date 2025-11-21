package com.example.myapplicationmusic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle

enum class RepeatMode {
    OFF, ONE, ALL
}

class MusicService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private val binder = MusicBinder()
    private var mainActivity: MainActivity? = null
    var playlist = mutableListOf<Uri>()
    var currentSongIndex = -1
    var isShuffleOn = false
    var repeatMode = RepeatMode.OFF
    private var isForegroundServiceStarted = false

    private lateinit var mediaSession: MediaSessionCompat
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var progressUpdater: Runnable

    companion object {
        const val CHANNEL_ID = "MusicServiceChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY = "ACTION_PLAY"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_NEXT = "ACTION_NEXT"
        const val ACTION_PREVIOUS = "ACTION_PREVIOUS"
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        mediaSession = MediaSessionCompat(this, "MusicService")
        mediaSession.setCallback(mediaSessionCallback)
        mediaSession.isActive = true

        setupProgressUpdater()
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            resumeMusic()
        }

        override fun onPause() {
            pauseMusic()
        }

        override fun onSkipToNext() {
            playNextSong()
        }

        override fun onSkipToPrevious() {
            playPreviousSong()
        }
    }

    private fun setupProgressUpdater() {
        progressUpdater = Runnable {
            if (isPlaying()) {
                updatePlaybackState()
                handler.postDelayed(progressUpdater, 1000) 
            }
        }
    }

    private fun startProgressUpdater() {
        handler.removeCallbacks(progressUpdater) 
        handler.post(progressUpdater)
    }

    private fun stopProgressUpdater() {
        handler.removeCallbacks(progressUpdater)
    }

    private fun updatePlaybackState() {
        val state = if (isPlaying()) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val position = mediaPlayer?.currentPosition?.toLong() ?: 0

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, position, 1.0f)
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .build()
        )
    }

    private fun updateMetadata(title: String, duration: Long) {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handleIntent(intent)
        return START_NOT_STICKY
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_PLAY -> resumeMusic()
            ACTION_PAUSE -> pauseMusic()
            ACTION_NEXT -> playNextSong()
            ACTION_PREVIOUS -> playPreviousSong()
        }
    }

    fun setMainActivity(activity: MainActivity?) {
        mainActivity = activity
        mainActivity?.updateUIFromService()
    }

    fun playSong(index: Int) {
        if (index < 0 || index >= playlist.size) return

        currentSongIndex = index
        val uri = playlist[currentSongIndex]
        val title = getSongName(uri)

        mediaPlayer?.release()
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(applicationContext, uri)
                setOnPreparedListener { mp ->
                    updateMetadata(title, mp.duration.toLong())
                    mp.start()
                    updatePlaybackState()
                    startProgressUpdater()
                    showNotification()
                    mainActivity?.onSongChanged()
                }
                setOnCompletionListener { onSongCompletion() }
                prepareAsync()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(applicationContext, "Error playing song", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun getMediaPlayer(): MediaPlayer? = mediaPlayer

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying ?: false

    fun togglePlayPause() {
        if (isPlaying()) pauseMusic() else resumeMusic()
    }

    fun rewind() {
        mediaPlayer?.let {
            val newPosition = it.currentPosition - 10000
            it.seekTo(if (newPosition < 0) 0 else newPosition)
        }
    }

    fun fastForward() {
        mediaPlayer?.let {
            val newPosition = it.currentPosition + 10000
            if (newPosition < it.duration) it.seekTo(newPosition) else it.seekTo(it.duration)
        }
    }

    private fun pauseMusic() {
        mediaPlayer?.pause()
        showNotification()
        mainActivity?.updatePlayPauseButton()
        updatePlaybackState()
        stopProgressUpdater()
    }

    private fun resumeMusic() {
        mediaPlayer?.start()
        showNotification()
        mainActivity?.updatePlayPauseButton()
        updatePlaybackState()
        startProgressUpdater()
    }

    fun setPlaylist(newPlaylist: List<Uri>, index: Int) {
        playlist.clear()
        playlist.addAll(newPlaylist)
        currentSongIndex = index
    }

    fun playNextSong() {
        if (playlist.isEmpty()) return
        currentSongIndex = if (isShuffleOn) {
            (0 until playlist.size).filter { it != currentSongIndex }.randomOrNull() ?: 0
        } else {
            (currentSongIndex + 1) % playlist.size
        }
        playSong(currentSongIndex)
    }

    fun playPreviousSong() {
        if (playlist.isEmpty()) return
        currentSongIndex = if (isShuffleOn) {
            (0 until playlist.size).filter { it != currentSongIndex }.randomOrNull() ?: 0
        } else {
            if (currentSongIndex - 1 < 0) playlist.size - 1 else currentSongIndex - 1
        }
        playSong(currentSongIndex)
    }

    private fun onSongCompletion() {
        when (repeatMode) {
            RepeatMode.OFF -> {
                if (currentSongIndex < playlist.size - 1 || isShuffleOn) {
                    playNextSong()
                } else {
                    pauseMusic()
                    mainActivity?.onPlaylistEnded()
                }
            }
            RepeatMode.ONE -> playSong(currentSongIndex)
            RepeatMode.ALL -> playNextSong()
        }
    }

    fun getSongName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
        } catch (_: SecurityException) {
            return getString(R.string.access_denied)
        }
        return getString(R.string.unknown_song)
    }

    fun showNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        val currentTitle = if (currentSongIndex != -1) getSongName(playlist[currentSongIndex]) else ""

        val prevIntent = Intent(this, MusicService::class.java).apply { action = ACTION_PREVIOUS }
        val prevPendingIntent = PendingIntent.getService(this, 1, prevIntent, PendingIntent.FLAG_IMMUTABLE)

        val playPauseIntent = if (isPlaying()) {
            Intent(this, MusicService::class.java).apply { action = ACTION_PAUSE }
        } else {
            Intent(this, MusicService::class.java).apply { action = ACTION_PLAY }
        }
        val playPausePendingIntent = PendingIntent.getService(this, 2, playPauseIntent, PendingIntent.FLAG_IMMUTABLE)

        val nextIntent = Intent(this, MusicService::class.java).apply { action = ACTION_NEXT }
        val nextPendingIntent = PendingIntent.getService(this, 3, nextIntent, PendingIntent.FLAG_IMMUTABLE)

        val playPauseAction = if (isPlaying()) {
            NotificationCompat.Action(R.drawable.ic_pause, getString(R.string.pause), playPausePendingIntent)
        } else {
            NotificationCompat.Action(R.drawable.ic_play, getString(R.string.play), playPausePendingIntent)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(ContextCompat.getColor(this, R.color.red))
            .setColorized(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_previous, getString(R.string.previous), prevPendingIntent)
            .addAction(playPauseAction)
            .addAction(R.drawable.ic_next, getString(R.string.next), nextPendingIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()

        if (!isForegroundServiceStarted) {
            startForeground(NOTIFICATION_ID, notification)
            isForegroundServiceStarted = true
        } else {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
        }
    }

    fun hideNotification() {
        if (isForegroundServiceStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundServiceStarted = false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Music Service Channel", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaSession.release()
        hideNotification()
        stopProgressUpdater()
    }
}
