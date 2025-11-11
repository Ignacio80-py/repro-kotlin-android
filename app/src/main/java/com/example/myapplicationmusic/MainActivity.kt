package com.example.myapplicationmusic

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private var musicService: MusicService? = null
    private var isBound = false

    private lateinit var playPauseButton: ImageButton
    private lateinit var songTitleTextView: TextView
    private lateinit var progressBar: SeekBar
    private lateinit var currentTimeTextView: TextView
    private lateinit var totalTimeTextView: TextView
    private lateinit var repeatButton: ImageButton
    private lateinit var shuffleButton: ImageButton
    private lateinit var createPlaylistButton: ImageButton
    private lateinit var openFilesButton: ImageButton
    private lateinit var timerButton: ImageButton
    private lateinit var currentPlaylistButton: ImageButton
    private lateinit var timerCountdownTextView: TextView
    private lateinit var fastForwardButton: ImageButton
    private lateinit var fastRewindButton: ImageButton

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var runnable: Runnable

    private var sleepTimer: CountDownTimer? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true
            musicService?.setMainActivity(this@MainActivity)
            musicService?.hideNotification()
            initializeSeekBar()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
        }
    }

    private val openFilesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uris = mutableListOf<Uri>()
            result.data?.clipData?.let { clipData ->
                for (i in 0 until clipData.itemCount) {
                    val uri = clipData.getItemAt(i).uri
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    uris.add(uri)
                }
            } ?: result.data?.data?.let {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                uris.add(it)
            }

            if (uris.isNotEmpty()) {
                musicService?.setPlaylist(uris, 0)
                musicService?.playSong(0)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        playPauseButton = findViewById(R.id.play_pause_button)
        songTitleTextView = findViewById(R.id.song_title)
        progressBar = findViewById(R.id.progress_bar)
        currentTimeTextView = findViewById(R.id.current_time)
        totalTimeTextView = findViewById(R.id.total_time)
        openFilesButton = findViewById(R.id.open_files_button)
        val previousButton: ImageButton = findViewById(R.id.previous_button)
        val nextButton: ImageButton = findViewById(R.id.next_button)
        shuffleButton = findViewById(R.id.shuffle_button)
        repeatButton = findViewById(R.id.repeat_button)
        createPlaylistButton = findViewById(R.id.create_playlist_button)
        timerButton = findViewById(R.id.timer_button)
        currentPlaylistButton = findViewById(R.id.current_playlist_button)
        timerCountdownTextView = findViewById(R.id.timer_countdown_text)
        fastForwardButton = findViewById(R.id.fast_forward_button)
        fastRewindButton = findViewById(R.id.fast_rewind_button)

        // Set listeners
        openFilesButton.setOnClickListener { openFilePickerWithPermissionCheck() }
        createPlaylistButton.setOnClickListener { showPlaylistManagementDialog() }
        playPauseButton.setOnClickListener { musicService?.togglePlayPause() }
        previousButton.setOnClickListener { musicService?.playPreviousSong() }
        nextButton.setOnClickListener { musicService?.playNextSong() }
        shuffleButton.setOnClickListener { toggleShuffle() }
        repeatButton.setOnClickListener { toggleRepeatMode() }
        timerButton.setOnClickListener { showSleepTimerDialog() }
        currentPlaylistButton.setOnClickListener { showCurrentPlaylistDialog() }
        fastForwardButton.setOnClickListener { musicService?.fastForward() }
        fastRewindButton.setOnClickListener { musicService?.rewind() }

        progressBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) musicService?.getMediaPlayer()?.seekTo(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        loadLastSession()
    }

    override fun onStart() {
        super.onStart()
        Intent(this, MusicService::class.java).also { intent ->
            startService(intent)
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        saveLastSession()
        if (isBound) {
            if (musicService?.isPlaying() == true) {
                musicService?.showNotification()
            }
            musicService?.setMainActivity(null)
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private inner class PlaylistAdapter(
        context: Context,
        private val songs: MutableList<Uri>,
        private val onPlay: (Int) -> Unit,
        private val onDelete: (Int, ArrayAdapter<Uri>) -> Unit
    ) : ArrayAdapter<Uri>(context, R.layout.playlist_item, songs) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.playlist_item, parent, false)
            val songTitleText = view.findViewById<TextView>(R.id.song_title_text)
            val deleteButton = view.findViewById<ImageButton>(R.id.delete_song_button)

            val uri = songs[position]
            songTitleText.text = musicService?.getSongName(uri) ?: ""
            deleteButton.setColorFilter(ContextCompat.getColor(context, R.color.red), PorterDuff.Mode.SRC_IN)

            view.setOnClickListener { onPlay(position) }
            deleteButton.setOnClickListener { onDelete(position, this) }

            return view
        }
    }

    private fun showCurrentPlaylistDialog() {
        val playlist = musicService?.playlist ?: return
        if (playlist.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_songs_loaded), Toast.LENGTH_SHORT).show()
            return
        }

        lateinit var dialog: AlertDialog

        val adapter = PlaylistAdapter(this, playlist, { position ->
            musicService?.playSong(position)
            dialog.dismiss()
        }, { position, adapter ->
            removeSongAt(position)
            adapter.notifyDataSetChanged()
        })

        dialog = AlertDialog.Builder(this, R.style.CustomDialogTheme)
            .setTitle(getString(R.string.current_playlist))
            .setAdapter(adapter, null)
            .setNegativeButton(getString(R.string.close), null)
            .create()

        dialog.show()
    }

    private fun removeSongAt(position: Int) {
        musicService?.playlist?.removeAt(position)
        // Actualizar la UI si es necesario
    }

    private fun showSleepTimerDialog() {
        if (sleepTimer != null) {
            cancelSleepTimer()
            return
        }

        val timerOptions = arrayOf(getString(R.string.minutes_15), getString(R.string.minutes_30), getString(R.string.minutes_60))
        AlertDialog.Builder(this, R.style.CustomDialogTheme)
            .setTitle(getString(R.string.sleep_timer))
            .setItems(timerOptions) { _, which ->
                val minutes = when (which) {
                    0 -> 15L
                    1 -> 30L
                    2 -> 60L
                    else -> return@setItems
                }
                startSleepTimer(minutes)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
            .show()
    }

    private fun startSleepTimer(minutes: Long) {
        val millis = TimeUnit.MINUTES.toMillis(minutes)
        sleepTimer = object : CountDownTimer(millis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                timerCountdownTextView.text = formatTime(millisUntilFinished)
            }

            override fun onFinish() {
                musicService?.togglePlayPause()
                playPauseButton.setImageResource(R.drawable.ic_play)
                cancelSleepTimer()
                Toast.makeText(this@MainActivity, getString(R.string.timer_finished), Toast.LENGTH_LONG).show()
            }
        }.start()

        timerButton.setColorFilter(ContextCompat.getColor(this, android.R.color.white), PorterDuff.Mode.SRC_IN)
        timerCountdownTextView.visibility = View.VISIBLE
        Toast.makeText(this, getString(R.string.timer_set, minutes), Toast.LENGTH_SHORT).show()
    }

    private fun cancelSleepTimer() {
        sleepTimer?.cancel()
        sleepTimer = null
        timerButton.clearColorFilter()
        timerCountdownTextView.visibility = View.GONE
        Toast.makeText(this, getString(R.string.timer_cancelled), Toast.LENGTH_SHORT).show()
    }

    private fun showPlaylistManagementDialog() {
        val sharedPrefs = getSharedPreferences("playlists", MODE_PRIVATE)
        val playlistNames = sharedPrefs.getStringSet("playlist_names", emptySet())?.toMutableList() ?: mutableListOf()

        val dialogOptions = playlistNames.toTypedArray<CharSequence>() + getString(R.string.create_new_playlist)

        AlertDialog.Builder(this, R.style.CustomDialogTheme)
            .setTitle(getString(R.string.favorite_playlists))
            .setItems(dialogOptions) { _, which ->
                if (which == dialogOptions.size - 1) {
                    promptForNewPlaylistName()
                } else {
                    showLoadOrDeletePlaylistDialog(playlistNames[which])
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showLoadOrDeletePlaylistDialog(playlistName: String) {
        val options = arrayOf(getString(R.string.load), getString(R.string.delete))
        AlertDialog.Builder(this, R.style.CustomDialogTheme)
            .setTitle(playlistName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> loadNamedPlaylist(playlistName)
                    1 -> showDeleteConfirmationDialog(playlistName)
                }
            }
            .show()
    }

    private fun showDeleteConfirmationDialog(playlistName: String) {
        AlertDialog.Builder(this, R.style.CustomDialogTheme)
            .setTitle(getString(R.string.delete_playlist_title))
            .setMessage(getString(R.string.delete_playlist_message, playlistName))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                deleteNamedPlaylist(playlistName)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun deleteNamedPlaylist(name: String) {
        val sharedPrefs = getSharedPreferences("playlists", MODE_PRIVATE)
        sharedPrefs.edit().apply {
            val currentNames = sharedPrefs.getStringSet("playlist_names", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            currentNames.remove(name)
            putStringSet("playlist_names", currentNames)
            remove("playlist_$name")
            apply()
        }

        Toast.makeText(this, getString(R.string.playlist_deleted, name), Toast.LENGTH_SHORT).show()
    }

    private fun promptForNewPlaylistName() {
        val playlist = musicService?.playlist ?: return
        if (playlist.isEmpty()) {
            Toast.makeText(this, getString(R.string.load_some_songs_first), Toast.LENGTH_SHORT).show()
            return
        }

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.playlist_name_hint)
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.black))
            backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.black)
        }

        AlertDialog.Builder(this, R.style.CustomDialogTheme)
            .setTitle(getString(R.string.create_new_playlist))
            .setView(input)
            .setPositiveButton(getString(R.string.next)) { _, _ ->
                val playlistName = input.text.toString()
                if (playlistName.isNotBlank()) {
                    showSongSelectionDialog(playlistName)
                } else {
                    Toast.makeText(this, getString(R.string.please_enter_a_name), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showSongSelectionDialog(playlistName: String) {
        val playlist = musicService?.playlist ?: return
        val songTitles = playlist.map { musicService?.getSongName(it) ?: "" }.toTypedArray()
        val selectedItems = BooleanArray(songTitles.size)
        val selectedSongs = mutableListOf<Uri>()

        AlertDialog.Builder(this, R.style.CustomDialogTheme)
            .setTitle(getString(R.string.select_songs_for_playlist, playlistName))
            .setMultiChoiceItems(songTitles, selectedItems) { _, which, isChecked ->
                selectedItems[which] = isChecked
            }
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                for (i in selectedItems.indices) {
                    if (selectedItems[i]) {
                        selectedSongs.add(playlist[i])
                    }
                }
                if (selectedSongs.isNotEmpty()) {
                    saveNamedPlaylist(playlistName, selectedSongs)
                } else {
                    Toast.makeText(this, getString(R.string.no_songs_selected), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun saveNamedPlaylist(name: String, songsToSave: List<Uri>) {
        val sharedPrefs = getSharedPreferences("playlists", MODE_PRIVATE)
        sharedPrefs.edit().apply {
            val currentNames = sharedPrefs.getStringSet("playlist_names", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            currentNames.add(name)
            val uriStrings = songsToSave.joinToString(";;;") { it.toString() }
            putStringSet("playlist_names", currentNames)
            putString("playlist_$name", uriStrings)
            apply()
        }

        Toast.makeText(this, getString(R.string.playlist_saved, name), Toast.LENGTH_SHORT).show()
    }

    private fun loadNamedPlaylist(name: String) {
        val sharedPrefs = getSharedPreferences("playlists", MODE_PRIVATE)
        val uriString = sharedPrefs.getString("playlist_$name", null)

        if (uriString.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.could_not_load_playlist, name), Toast.LENGTH_SHORT).show()
            return
        }
        val playlist = uriString.split(";;;").map { it.toUri() }

        if (playlist.isNotEmpty()) {
            musicService?.setPlaylist(playlist, 0)
            musicService?.playSong(0)
            Toast.makeText(this, getString(R.string.playlist_loaded, name), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.playlist_is_empty, name), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFilePickerWithPermissionCheck() {
        if (hasRequiredPermissions()) openFilePicker() else requestRequiredPermissions()
    }

    private fun hasRequiredPermissions(): Boolean {
        val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }

        return ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED &&
                (notificationPermission == null || ContextCompat.checkSelfPermission(this, notificationPermission) == PackageManager.PERMISSION_GRANTED)
    }

    private fun requestRequiredPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        permissionsToRequest.add(audioPermission)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            openFilePicker()
        } else {
            Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        openFilesLauncher.launch(intent)
    }

    private fun toggleShuffle() {
        musicService?.let {
            it.isShuffleOn = !it.isShuffleOn
            if (it.isShuffleOn) {
                shuffleButton.setColorFilter(ContextCompat.getColor(this, android.R.color.white), PorterDuff.Mode.SRC_IN)
            } else {
                shuffleButton.clearColorFilter()
            }
            val message = if (it.isShuffleOn) getString(R.string.shuffle_on) else getString(R.string.shuffle_off)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleRepeatMode() {
        musicService?.let {
            it.repeatMode = when (it.repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
            updateRepeatButtonIcon()
        }
    }

    private fun updateRepeatButtonIcon(showToast: Boolean = true) {
        musicService?.let {
            when (it.repeatMode) {
                RepeatMode.OFF -> {
                    repeatButton.setImageResource(R.drawable.ic_repeat)
                    repeatButton.clearColorFilter()
                    if (showToast) Toast.makeText(this, getString(R.string.repeat_off), Toast.LENGTH_SHORT).show()
                }
                RepeatMode.ALL -> {
                    repeatButton.setImageResource(R.drawable.ic_repeat)
                    repeatButton.setColorFilter(ContextCompat.getColor(this, android.R.color.white), PorterDuff.Mode.SRC_IN)
                    if (showToast) Toast.makeText(this, getString(R.string.repeat_all), Toast.LENGTH_SHORT).show()
                }
                RepeatMode.ONE -> {
                    repeatButton.setImageResource(R.drawable.ic_repeat_one)
                    repeatButton.setColorFilter(ContextCompat.getColor(this, android.R.color.white), PorterDuff.Mode.SRC_IN)
                    if (showToast) Toast.makeText(this, getString(R.string.repeat_one), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun initializeSeekBar() {
        runnable = Runnable {
            musicService?.getMediaPlayer()?.let {
                if (it.isPlaying) {
                    val currentPosition = it.currentPosition
                    progressBar.progress = currentPosition
                    currentTimeTextView.text = formatTime(currentPosition.toLong())
                    val remainingTime = it.duration - currentPosition
                    totalTimeTextView.text = getString(R.string.time_format_with_minus, formatTime(remainingTime.toLong()))
                }
            }
            handler.postDelayed(runnable, 1000)
        }
        handler.postDelayed(runnable, 1000)
    }

    private fun formatTime(millis: Long): String {
        return String.format(
            Locale.getDefault(),
            getString(R.string.time_format),
            TimeUnit.MILLISECONDS.toMinutes(millis),
            TimeUnit.MILLISECONDS.toSeconds(millis) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis))
        )
    }

    private fun saveLastSession() {
        val sharedPrefs = getSharedPreferences("session_prefs", MODE_PRIVATE)
        musicService?.let {
            sharedPrefs.edit().apply {
                val uriStrings = it.playlist.joinToString(";;;") { it.toString() }
                putString("last_playlist_uris", uriStrings)
                putInt("last_song_index", it.currentSongIndex)
                putBoolean("last_shuffle_state", it.isShuffleOn)
                putInt("last_repeat_mode", it.repeatMode.ordinal)
                apply()
            }
        }
    }

    private fun loadLastSession() {
        val sharedPrefs = getSharedPreferences("session_prefs", MODE_PRIVATE)
        val uriString = sharedPrefs.getString("last_playlist_uris", null)
        if (!uriString.isNullOrEmpty()) {
            val playlist = uriString.split(";;;").map { it.toUri() }.toMutableList()
            val currentSongIndex = sharedPrefs.getInt("last_song_index", -1)
            musicService?.setPlaylist(playlist, currentSongIndex)
        }

        musicService?.isShuffleOn = sharedPrefs.getBoolean("last_shuffle_state", false)
        musicService?.repeatMode = RepeatMode.entries[sharedPrefs.getInt("last_repeat_mode", 0)]

        updateUIFromService()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        handler.removeCallbacks(runnable)
        sleepTimer?.cancel()
    }

    fun updatePlayPauseButton() {
        musicService?.let {
            if (it.isPlaying()) {
                playPauseButton.setImageResource(R.drawable.ic_pause)
            } else {
                playPauseButton.setImageResource(R.drawable.ic_play)
            }
        }
    }

    fun onSongChanged() {
        musicService?.let { service ->
            if (service.currentSongIndex != -1) {
                songTitleTextView.text = service.getSongName(service.playlist[service.currentSongIndex])
                service.getMediaPlayer()?.let { mp ->
                    progressBar.max = mp.duration
                    totalTimeTextView.text = getString(R.string.time_format_with_minus, formatTime(mp.duration.toLong()))
                    progressBar.progress = 0
                    currentTimeTextView.text = formatTime(0)
                }
                updatePlayPauseButton()
            }
        }
    }

    fun updateUIFromService() {
        updatePlayPauseButton()
        onSongChanged()
        updateShuffleAndRepeatButtons()
    }

    private fun updateShuffleAndRepeatButtons() {
        musicService?.let {
            if (it.isShuffleOn) {
                shuffleButton.setColorFilter(ContextCompat.getColor(this, android.R.color.white), PorterDuff.Mode.SRC_IN)
            } else {
                shuffleButton.clearColorFilter()
            }
            updateRepeatButtonIcon(showToast = false)
        }
    }

    fun onPlaylistEnded() {
        playPauseButton.setImageResource(R.drawable.ic_play)
        progressBar.progress = 0
        currentTimeTextView.text = formatTime(0)
        totalTimeTextView.text = getString(R.string.time_format_with_minus, formatTime(0))
    }
}
