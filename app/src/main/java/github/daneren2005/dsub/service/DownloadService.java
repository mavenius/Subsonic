/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package github.daneren2005.dsub.service;

import static android.support.v7.media.MediaRouter.RouteInfo;
import static github.daneren2005.dsub.domain.PlayerState.COMPLETED;
import static github.daneren2005.dsub.domain.PlayerState.DOWNLOADING;
import static github.daneren2005.dsub.domain.PlayerState.IDLE;
import static github.daneren2005.dsub.domain.PlayerState.PAUSED;
import static github.daneren2005.dsub.domain.PlayerState.PAUSED_TEMP;
import static github.daneren2005.dsub.domain.PlayerState.PREPARED;
import static github.daneren2005.dsub.domain.PlayerState.PREPARING;
import static github.daneren2005.dsub.domain.PlayerState.STARTED;
import static github.daneren2005.dsub.domain.PlayerState.STOPPED;
import static github.daneren2005.dsub.domain.RemoteControlState.LOCAL;

import github.daneren2005.dsub.R;
import github.daneren2005.dsub.activity.SubsonicActivity;
import github.daneren2005.dsub.audiofx.AudioEffectsController;
import github.daneren2005.dsub.audiofx.EqualizerController;
import github.daneren2005.dsub.domain.Bookmark;
import github.daneren2005.dsub.domain.MusicDirectory;
import github.daneren2005.dsub.domain.PlayerState;
import github.daneren2005.dsub.domain.PodcastEpisode;
import github.daneren2005.dsub.domain.RemoteControlState;
import github.daneren2005.dsub.domain.RepeatMode;
import github.daneren2005.dsub.domain.ServerInfo;
import github.daneren2005.dsub.receiver.MediaButtonIntentReceiver;
import github.daneren2005.dsub.util.ArtistRadioBuffer;
import github.daneren2005.dsub.util.ImageLoader;
import github.daneren2005.dsub.util.Notifications;
import github.daneren2005.dsub.util.SilentBackgroundTask;
import github.daneren2005.dsub.util.Constants;
import github.daneren2005.dsub.util.MediaRouteManager;
import github.daneren2005.dsub.util.ShufflePlayBuffer;
import github.daneren2005.dsub.util.SimpleServiceBinder;
import github.daneren2005.dsub.util.UpdateHelper;
import github.daneren2005.dsub.util.Util;
import github.daneren2005.dsub.util.compat.RemoteControlClientBase;
import github.daneren2005.dsub.util.tags.BastpUtil;
import github.daneren2005.dsub.view.UpdateView;
import github.daneren2005.serverproxy.BufferProxy;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import android.annotation.TargetApi;
import android.app.Service;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.AudioEffect;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v7.media.MediaRouteSelector;
import android.support.v7.media.MediaRouter;
import android.util.Log;
import android.support.v4.util.LruCache;

/**
 * @author Sindre Mehus
 * @version $Id$
 */
public class DownloadService extends Service {
	private static final String TAG = DownloadService.class.getSimpleName();

	public static final String CMD_PLAY = "github.daneren2005.dsub.CMD_PLAY";
	public static final String CMD_TOGGLEPAUSE = "github.daneren2005.dsub.CMD_TOGGLEPAUSE";
	public static final String CMD_PAUSE = "github.daneren2005.dsub.CMD_PAUSE";
	public static final String CMD_STOP = "github.daneren2005.dsub.CMD_STOP";
	public static final String CMD_PREVIOUS = "github.daneren2005.dsub.CMD_PREVIOUS";
	public static final String CMD_NEXT = "github.daneren2005.dsub.CMD_NEXT";
	public static final String CANCEL_DOWNLOADS = "github.daneren2005.dsub.CANCEL_DOWNLOADS";
	public static final String START_PLAY = "github.daneren2005.dsub.START_PLAYING";
	public static final int FAST_FORWARD = 30000;
	public static final int REWIND = 10000;
	private static final double DELETE_CUTOFF = 0.84;
	private static final int REQUIRED_ALBUM_MATCHES = 4;
	private static final int REMOTE_PLAYLIST_TOTAL = 3;
	private static final int SHUFFLE_MODE_NONE = 0;
	private static final int SHUFFLE_MODE_ALL = 1;
	private static final int SHUFFLE_MODE_ARTIST = 2;

	public static final int METADATA_UPDATED_ALL = 0;
	public static final int METADATA_UPDATED_STAR = 1;
	public static final int METADATA_UPDATED_RATING = 2;
	public static final int METADATA_UPDATED_BOOKMARK = 4;
	public static final int METADATA_UPDATED_COVER_ART = 8;

	private RemoteControlClientBase mRemoteControl;

	private final IBinder binder = new SimpleServiceBinder<DownloadService>(this);
	private Looper mediaPlayerLooper;
	private MediaPlayer mediaPlayer;
	private MediaPlayer nextMediaPlayer;
	private int audioSessionId;
	private boolean nextSetup = false;
	private final List<DownloadFile> downloadList = new ArrayList<DownloadFile>();
	private final List<DownloadFile> backgroundDownloadList = new ArrayList<DownloadFile>();
	private final List<DownloadFile> toDelete = new ArrayList<DownloadFile>();
	private final Handler handler = new Handler();
	private Handler mediaPlayerHandler;
	private final DownloadServiceLifecycleSupport lifecycleSupport = new DownloadServiceLifecycleSupport(this);
	private ShufflePlayBuffer shufflePlayBuffer;
	private ArtistRadioBuffer artistRadioBuffer;

	private final LruCache<MusicDirectory.Entry, DownloadFile> downloadFileCache = new LruCache<MusicDirectory.Entry, DownloadFile>(100);
	private final List<DownloadFile> cleanupCandidates = new ArrayList<DownloadFile>();
	private final Scrobbler scrobbler = new Scrobbler();
	private RemoteController remoteController;
	private DownloadFile currentPlaying;
	private int currentPlayingIndex = -1;
	private DownloadFile nextPlaying;
	private DownloadFile currentDownloading;
	private SilentBackgroundTask bufferTask;
	private SilentBackgroundTask nextPlayingTask;
	private PlayerState playerState = IDLE;
	private PlayerState nextPlayerState = IDLE;
	private boolean removePlayed;
	private boolean shufflePlay;
	private boolean artistRadio;
	private final List<OnSongChangedListener> onSongChangedListeners = new ArrayList<>();
	private long revision;
	private static DownloadService instance;
	private String suggestedPlaylistName;
	private String suggestedPlaylistId;
	private PowerManager.WakeLock wakeLock;
	private WifiManager.WifiLock wifiLock;
	private boolean keepScreenOn;
	private int cachedPosition = 0;
	private boolean downloadOngoing = false;
	private float volume = 1.0f;

	private AudioEffectsController effectsController;
	private RemoteControlState remoteState = LOCAL;
	private PositionCache positionCache;
	private BufferProxy proxy;

	private Timer sleepTimer;
	private int timerDuration;
	private long timerStart;
	private boolean autoPlayStart = false;
	private boolean runListenersOnInit = false;

	private MediaRouteManager mediaRouter;
	
	// Variables to manage getCurrentPosition sometimes starting from an arbitrary non-zero number
	private long subtractNextPosition = 0;
	private int subtractPosition = 0;

	@Override
	public void onCreate() {
		super.onCreate();

		final SharedPreferences prefs = Util.getPreferences(this);
		new Thread(new Runnable() {
			public void run() {
				Looper.prepare();

				mediaPlayer = new MediaPlayer();
				mediaPlayer.setWakeMode(DownloadService.this, PowerManager.PARTIAL_WAKE_LOCK);

				audioSessionId = -1;
				Integer id = prefs.getInt(Constants.CACHE_AUDIO_SESSION_ID, -1);
				if(id != -1) {
					try {
						audioSessionId = id;
						mediaPlayer.setAudioSessionId(audioSessionId);
					} catch (Throwable e) {
						audioSessionId = -1;
					}
				}

				if(audioSessionId == -1) {
					mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
					try {
						audioSessionId = mediaPlayer.getAudioSessionId();
						prefs.edit().putInt(Constants.CACHE_AUDIO_SESSION_ID, audioSessionId).commit();
					} catch (Throwable t) {
						// Froyo or lower
					}
				}

				mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
					@Override
					public boolean onError(MediaPlayer mediaPlayer, int what, int more) {
						handleError(new Exception("MediaPlayer error: " + what + " (" + more + ")"));
						return false;
					}
				});

				/*try {
					Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
					i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId);
					i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
					sendBroadcast(i);
				} catch(Throwable e) {
					// Froyo or lower
				}*/

				effectsController = new AudioEffectsController(DownloadService.this, audioSessionId);
				if(prefs.getBoolean(Constants.PREFERENCES_EQUALIZER_ON, false)) {
					getEqualizerController();
				}

				mediaPlayerLooper = Looper.myLooper();
				mediaPlayerHandler = new Handler(mediaPlayerLooper);

				if(runListenersOnInit) {
					onSongsChanged();
					onSongProgress();
					onStateUpdate();
				}

				Looper.loop();
			}
		}, "DownloadService").start();

		Util.registerMediaButtonEventReceiver(this);

		if (mRemoteControl == null) {
			// Use the remote control APIs (if available) to set the playback state
			mRemoteControl = RemoteControlClientBase.createInstance();
			ComponentName mediaButtonReceiverComponent = new ComponentName(getPackageName(), MediaButtonIntentReceiver.class.getName());
			mRemoteControl.register(this, mediaButtonReceiverComponent);
		}

		PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
		wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, this.getClass().getName());
		wakeLock.setReferenceCounted(false);

		WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
		wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "downloadServiceLock");

		try {
			timerDuration = Integer.parseInt(prefs.getString(Constants.PREFERENCES_KEY_SLEEP_TIMER_DURATION, "5"));
		} catch(Throwable e) {
			timerDuration = 5;
		}
		sleepTimer = null;

		keepScreenOn = prefs.getBoolean(Constants.PREFERENCES_KEY_KEEP_SCREEN_ON, false);

		mediaRouter = new MediaRouteManager(this);

		instance = this;
		shufflePlayBuffer = new ShufflePlayBuffer(this);
		artistRadioBuffer = new ArtistRadioBuffer(this);
		lifecycleSupport.onCreate();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		super.onStartCommand(intent, flags, startId);
		lifecycleSupport.onStart(intent);
		return START_NOT_STICKY;
	}

	@Override
	public void onTrimMemory(int level) {
		ImageLoader imageLoader = SubsonicActivity.getStaticImageLoader(this);
		if(imageLoader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
			Log.i(TAG, "Memory Trim Level: " + level);
			if (level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
				if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
					imageLoader.onLowMemory(0.75f);
				} else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
					imageLoader.onLowMemory(0.50f);
				} else if(level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
					imageLoader.onLowMemory(0.25f);
				}
			} else if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
				imageLoader.onLowMemory(0.25f);
			} else if(level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
				imageLoader.onLowMemory(0.75f);
			}
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		instance = null;

		if(currentPlaying != null) currentPlaying.setPlaying(false);
		if(sleepTimer != null){
			sleepTimer.cancel();
			sleepTimer.purge();
		}
		lifecycleSupport.onDestroy();

		try {
			Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
			i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId);
			i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
			sendBroadcast(i);
		} catch(Throwable e) {
			// Froyo or lower
		}

		mediaPlayer.release();
		if(nextMediaPlayer != null) {
			nextMediaPlayer.release();
		}
		mediaPlayerLooper.quit();
		shufflePlayBuffer.shutdown();
		effectsController.release();
		if (mRemoteControl != null) {
			mRemoteControl.unregister(this);
			mRemoteControl = null;
		}

		if(bufferTask != null) {
			bufferTask.cancel();
			bufferTask = null;
		}
		if(nextPlayingTask != null) {
			nextPlayingTask.cancel();
			nextPlayingTask = null;
		}
		if(remoteController != null) {
			remoteController.stop();
			remoteController.shutdown();
		}
		if(proxy != null) {
			proxy.stop();
			proxy = null;
		}
		mediaRouter.destroy();
		Notifications.hidePlayingNotification(this, this, handler);
		Notifications.hideDownloadingNotification(this, this, handler);
	}

	public static DownloadService getInstance() {
		return instance;
	}

	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}
	
	public void post(Runnable r) {
		handler.post(r);
	}
	public void postDelayed(Runnable r, long millis) {
		handler.postDelayed(r, millis);
	}

	public synchronized void download(List<MusicDirectory.Entry> songs, boolean save, boolean autoplay, boolean playNext, boolean shuffle) {
		download(songs, save, autoplay, playNext, shuffle, 0, 0);
	}
	public synchronized void download(List<MusicDirectory.Entry> songs, boolean save, boolean autoplay, boolean playNext, boolean shuffle, int start, int position) {
		setShufflePlayEnabled(false);
		setArtistRadio(null);
		int offset = 1;
		boolean noNetwork = !Util.isOffline(this) && !Util.isNetworkConnected(this);
		boolean warnNetwork = false;

		if (songs.isEmpty()) {
			return;
		}
		if (playNext) {
			if (autoplay && getCurrentPlayingIndex() >= 0) {
				offset = 0;
			}
			for (MusicDirectory.Entry song : songs) {
				if(song != null) {
					DownloadFile downloadFile = new DownloadFile(this, song, save);
					addToDownloadList(downloadFile, getCurrentPlayingIndex() + offset);
					if(noNetwork && !warnNetwork) {
						if(!downloadFile.isCompleteFileAvailable()) {
							warnNetwork = true;
						}
					}
					offset++;
				}
			}

			if(remoteState == LOCAL || (remoteController != null && remoteController.isNextSupported())) {
				setNextPlaying();
			}
		} else {
			int size = size();
			int index = getCurrentPlayingIndex();
			for (MusicDirectory.Entry song : songs) {
				if(song == null) {
					continue;
				}

				DownloadFile downloadFile = new DownloadFile(this, song, save);
				addToDownloadList(downloadFile, -1);
				if(noNetwork && !warnNetwork) {
					if(!downloadFile.isCompleteFileAvailable()) {
						warnNetwork = true;
					}
				}
			}
			if(!autoplay && (size - 1) == index) {
				setNextPlaying();
			}
		}
		revision++;
		onSongsChanged();
		updateRemotePlaylist();

		if(shuffle) {
			shuffle();
		}
		if(warnNetwork) {
			Util.toast(this, R.string.select_album_no_network);
		}

		if (autoplay) {
			play(start, true, position);
		} else if(start != 0 || position != 0) {
			play(start, false, position);
		} else {
			if (currentPlaying == null) {
				currentPlaying = downloadList.get(0);
				currentPlayingIndex = 0;
				currentPlaying.setPlaying(true);
			} else {
				currentPlayingIndex = downloadList.indexOf(currentPlaying);
			}
			checkDownloads();
		}
		lifecycleSupport.serializeDownloadQueue();
	}
	private void addToDownloadList(DownloadFile file, int offset) {
		if(offset == -1) {
			downloadList.add(file);
		} else {
			downloadList.add(offset, file);
		}
	}
	public synchronized void downloadBackground(List<MusicDirectory.Entry> songs, boolean save) {
		for (MusicDirectory.Entry song : songs) {
			DownloadFile downloadFile = new DownloadFile(this, song, save);
			if(!downloadFile.isWorkDone() || (downloadFile.shouldSave() && !downloadFile.isSaved())) {
				// Only add to list if there is work to be done
				backgroundDownloadList.add(downloadFile);
			} else if(downloadFile.isSaved() && !save) {
				// Quickly unpin song instead of adding it to work to be done
				downloadFile.unpin();
			}
		}
		revision++;

		if(!Util.isOffline(this) && !Util.isNetworkConnected(this)) {
			Util.toast(this, R.string.select_album_no_network);
		}

		checkDownloads();
		lifecycleSupport.serializeDownloadQueue();
	}

	private synchronized void updateRemotePlaylist() {
		List<DownloadFile> playlist = new ArrayList<>();
		if(currentPlaying != null) {
			int index = downloadList.indexOf(currentPlaying);
			if(index == -1) {
				index = 0;
			}

			int size = size();
			int end = index + REMOTE_PLAYLIST_TOTAL;
			for(int i = index; i < size && i < end; i++) {
				playlist.add(downloadList.get(i));
			}
		}

		if (remoteState != LOCAL && remoteController != null) {
			remoteController.updatePlaylist();
		}
		mRemoteControl.updatePlaylist(playlist);
	}

	public synchronized void restore(List<MusicDirectory.Entry> songs, List<MusicDirectory.Entry> toDelete, int currentPlayingIndex, int currentPlayingPosition) {
		SharedPreferences prefs = Util.getPreferences(this);
		RemoteControlState newState = RemoteControlState.values()[prefs.getInt(Constants.PREFERENCES_KEY_CONTROL_MODE, 0)];
		if(newState != LOCAL) {
			String id = prefs.getString(Constants.PREFERENCES_KEY_CONTROL_ID, null);
			setRemoteState(newState, null, id);
		}
		if(prefs.getBoolean(Constants.PREFERENCES_KEY_REMOVE_PLAYED, false)) {
			removePlayed = true;
		}
		int startShufflePlay = prefs.getInt(Constants.PREFERENCES_KEY_SHUFFLE_MODE, SHUFFLE_MODE_NONE);
		download(songs, false, false, false, false);
		if(startShufflePlay != SHUFFLE_MODE_NONE) {
			if(startShufflePlay == SHUFFLE_MODE_ALL) {
				shufflePlay = true;
			} else if(startShufflePlay == SHUFFLE_MODE_ARTIST) {
				artistRadio = true;
				artistRadioBuffer.restoreArtist(prefs.getString(Constants.PREFERENCES_KEY_SHUFFLE_MODE_EXTRA, null));
			}
			SharedPreferences.Editor editor = prefs.edit();
			editor.putInt(Constants.PREFERENCES_KEY_SHUFFLE_MODE, startShufflePlay);
			editor.commit();
		}
		if (currentPlayingIndex != -1) {
			while(mediaPlayer == null) {
				Util.sleepQuietly(50L);
			}

			play(currentPlayingIndex, autoPlayStart, currentPlayingPosition);
			autoPlayStart = false;
		}

		if(toDelete != null) {
			for(MusicDirectory.Entry entry: toDelete) {
				this.toDelete.add(forSong(entry));
			}
		}
		
		suggestedPlaylistName = prefs.getString(Constants.PREFERENCES_KEY_PLAYLIST_NAME, null);
		suggestedPlaylistId = prefs.getString(Constants.PREFERENCES_KEY_PLAYLIST_ID, null);
	}

	public boolean isInitialized() {
		return lifecycleSupport != null && lifecycleSupport.isInitialized();
	}

	public synchronized Date getLastStateChanged() {
		return lifecycleSupport.getLastChange();
	}

	public synchronized void setRemovePlayed(boolean enabled) {
		removePlayed = enabled;
		if(removePlayed) {
			checkDownloads();
			lifecycleSupport.serializeDownloadQueue();
		}
		SharedPreferences.Editor editor = Util.getPreferences(this).edit();
		editor.putBoolean(Constants.PREFERENCES_KEY_REMOVE_PLAYED, enabled);
		editor.commit();
	}
	public boolean isRemovePlayed() {
		return removePlayed;
	}

	public synchronized void setShufflePlayEnabled(boolean enabled) {
		shufflePlay = enabled;
		if (shufflePlay) {
			clear();
			checkDownloads();
		}
		SharedPreferences.Editor editor = Util.getPreferences(this).edit();
		editor.putInt(Constants.PREFERENCES_KEY_SHUFFLE_MODE, enabled ? SHUFFLE_MODE_ALL : SHUFFLE_MODE_NONE);
		editor.commit();
	}

	public boolean isShufflePlayEnabled() {
		return shufflePlay;
	}

	public void setArtistRadio(String artistId) {
		if(artistId == null) {
			artistRadio = false;
		} else {
			artistRadio = true;
			artistRadioBuffer.setArtist(artistId);
		}

		SharedPreferences.Editor editor = Util.getPreferences(this).edit();
		editor.putInt(Constants.PREFERENCES_KEY_SHUFFLE_MODE, (artistId != null) ? SHUFFLE_MODE_ARTIST : SHUFFLE_MODE_NONE);
		if(artistId != null) {
			editor.putString(Constants.PREFERENCES_KEY_SHUFFLE_MODE_EXTRA, artistId);
		}
		editor.commit();
	}
	public boolean isArtistRadio() {
		return artistRadio;
	}

	public synchronized void shuffle() {
		Collections.shuffle(downloadList);
		currentPlayingIndex = downloadList.indexOf(currentPlaying);
		if (currentPlaying != null) {
			downloadList.remove(getCurrentPlayingIndex());
			downloadList.add(0, currentPlaying);
			currentPlayingIndex = 0;
		}
		revision++;
		onSongsChanged();
		lifecycleSupport.serializeDownloadQueue();
		updateRemotePlaylist();
		setNextPlaying();
	}

	public RepeatMode getRepeatMode() {
		return Util.getRepeatMode(this);
	}

	public void setRepeatMode(RepeatMode repeatMode) {
		Util.setRepeatMode(this, repeatMode);
		setNextPlaying();
	}

	public boolean getKeepScreenOn() {
		return keepScreenOn;
	}

	public void setKeepScreenOn(boolean keepScreenOn) {
		this.keepScreenOn = keepScreenOn;

		SharedPreferences prefs = Util.getPreferences(this);
		SharedPreferences.Editor editor = prefs.edit();
		editor.putBoolean(Constants.PREFERENCES_KEY_KEEP_SCREEN_ON, keepScreenOn);
		editor.commit();
	}

	public synchronized DownloadFile forSong(MusicDirectory.Entry song) {
		DownloadFile returnFile = null;
		for (DownloadFile downloadFile : downloadList) {
			if (downloadFile.getSong().equals(song)) {
				if(((downloadFile.isDownloading() && !downloadFile.isDownloadCancelled() && downloadFile.getPartialFile().exists()) || downloadFile.isWorkDone())) {
					// If downloading, return immediately
					return downloadFile;
				} else {
					// Otherwise, check to make sure there isn't a background download going on first
					returnFile = downloadFile;
				}
			}
		}
		for (DownloadFile downloadFile : backgroundDownloadList) {
			if (downloadFile.getSong().equals(song)) {
				return downloadFile;
			}
		}

		if(returnFile != null) {
			return returnFile;
		}

		DownloadFile downloadFile = downloadFileCache.get(song);
		if (downloadFile == null) {
			downloadFile = new DownloadFile(this, song, false);
			downloadFileCache.put(song, downloadFile);
		}
		return downloadFile;
	}

	public synchronized void clearBackground() {
		if(currentDownloading != null && backgroundDownloadList.contains(currentDownloading)) {
			currentDownloading.cancelDownload();
			currentDownloading = null;
		}
		backgroundDownloadList.clear();
		revision++;
		Notifications.hideDownloadingNotification(this, this, handler);
	}

	public synchronized void clearIncomplete() {
		Iterator<DownloadFile> iterator = downloadList.iterator();
		while (iterator.hasNext()) {
			DownloadFile downloadFile = iterator.next();
			if (!downloadFile.isCompleteFileAvailable()) {
				iterator.remove();
				
				// Reset if the current playing song has been removed
				if(currentPlaying == downloadFile) {
					reset();
				}

				currentPlayingIndex = downloadList.indexOf(currentPlaying);
			}
		}
		lifecycleSupport.serializeDownloadQueue();
		updateRemotePlaylist();
		onSongsChanged();
	}

	public void setOnline(final boolean online) {
		if(online) {
			mediaRouter.addOnlineProviders();
		} else {
			mediaRouter.removeOnlineProviders();
		}
		if(shufflePlay) {
			setShufflePlayEnabled(false);
		}
		if(artistRadio) {
			setArtistRadio(null);
		}

		lifecycleSupport.post(new Runnable() {
			@Override
			public void run() {
				if (online) {
					checkDownloads();
				} else {
					clearIncomplete();
				}
			}
		});
	}
	public void userSettingsChanged() {
		mediaRouter.buildSelector();
	}

	public synchronized int size() {
		return downloadList.size();
	}

	public synchronized void clear() {
		clear(true);
	}
	public synchronized void clear(boolean serialize) {
		// Delete podcast if fully listened to
		int position = getPlayerPosition();
		int duration = getPlayerDuration();
		boolean cutoff = isPastCutoff(position, duration, true);
		if(currentPlaying != null && currentPlaying.getSong() instanceof PodcastEpisode) {
			if(cutoff) {
				currentPlaying.delete();
			}
		}
		for(DownloadFile podcast: toDelete) {
			podcast.delete();
		}
		toDelete.clear();
		
		// Clear bookmarks from current playing if past a certain point
		if(cutoff) {
			clearCurrentBookmark(true);
		} else {
			// Check if we should be adding a new bookmark here
			checkAddBookmark();
		}
		if(currentPlaying != null) {
			scrobbler.conditionalScrobble(this, currentPlaying, position, duration);
		}

		reset();
		downloadList.clear();
		onSongsChanged();
		if (currentDownloading != null && !backgroundDownloadList.contains(currentDownloading)) {
			currentDownloading.cancelDownload();
			currentDownloading = null;
		}
		setCurrentPlaying(null, false);

		if (serialize) {
			lifecycleSupport.serializeDownloadQueue();
		}
		updateRemotePlaylist();
		setNextPlaying();
		if(proxy != null) {
			proxy.stop();
			proxy = null;
		}

		suggestedPlaylistName = null;
		suggestedPlaylistId = null;
	}

	public synchronized void remove(int which) {
		downloadList.remove(which);
		currentPlayingIndex = downloadList.indexOf(currentPlaying);
	}

	public synchronized void remove(DownloadFile downloadFile) {
		if (downloadFile == currentDownloading) {
			currentDownloading.cancelDownload();
			currentDownloading = null;
		}
		if (downloadFile == currentPlaying) {
			reset();
			setCurrentPlaying(null, false);
		}
		downloadList.remove(downloadFile);
		currentPlayingIndex = downloadList.indexOf(currentPlaying);
		backgroundDownloadList.remove(downloadFile);
		revision++;
		onSongsChanged();
		lifecycleSupport.serializeDownloadQueue();
		updateRemotePlaylist();
		if(downloadFile == nextPlaying) {
			setNextPlaying();
		}
	}
	public synchronized void removeBackground(DownloadFile downloadFile) {
		if (downloadFile == currentDownloading && downloadFile != currentPlaying && downloadFile != nextPlaying) {
			currentDownloading.cancelDownload();
			currentDownloading = null;
		}

		backgroundDownloadList.remove(downloadFile);
		revision++;
		checkDownloads();
	}

	public synchronized void delete(List<MusicDirectory.Entry> songs) {
		for (MusicDirectory.Entry song : songs) {
			forSong(song).delete();
		}
	}

	public synchronized void unpin(List<MusicDirectory.Entry> songs) {
		for (MusicDirectory.Entry song : songs) {
			forSong(song).unpin();
		}
	}

	synchronized void setCurrentPlaying(int currentPlayingIndex, boolean showNotification) {
		try {
			setCurrentPlaying(downloadList.get(currentPlayingIndex), showNotification);
		} catch (IndexOutOfBoundsException x) {
			// Ignored
		}
	}

	synchronized void setCurrentPlaying(DownloadFile currentPlaying, boolean showNotification) {
		if(this.currentPlaying != null) {
			this.currentPlaying.setPlaying(false);
		}
		this.currentPlaying = currentPlaying;
		if(currentPlaying == null) {
			currentPlayingIndex = -1;
			setPlayerState(IDLE);
		} else {
			currentPlayingIndex = downloadList.indexOf(currentPlaying);
		}

		if (currentPlaying != null && currentPlaying.getSong() != null) {
			Util.broadcastNewTrackInfo(this, currentPlaying.getSong());
			mRemoteControl.updateMetadata(this, currentPlaying.getSong());
		} else {
			Util.broadcastNewTrackInfo(this, null);
			Notifications.hidePlayingNotification(this, this, handler);
		}
		onSongChanged();
	}

	synchronized void setNextPlaying() {
		SharedPreferences prefs = Util.getPreferences(DownloadService.this);

		// Only obey gapless playback for local
		if(remoteState == LOCAL) {
			boolean gaplessPlayback = prefs.getBoolean(Constants.PREFERENCES_KEY_GAPLESS_PLAYBACK, true);
			if (!gaplessPlayback) {
				nextPlaying = null;
				nextPlayerState = IDLE;
				return;
			}
		}
		setNextPlayerState(IDLE);

		int index = getNextPlayingIndex();

		if(nextPlayingTask != null) {
			nextPlayingTask.cancel();
			nextPlayingTask = null;
		}
		resetNext();

		if(index < size() && index != -1 && index != currentPlayingIndex) {
			nextPlaying = downloadList.get(index);

			if(remoteState == LOCAL) {
				nextPlayingTask = new CheckCompletionTask(nextPlaying);
				nextPlayingTask.execute();
			} else if(remoteController != null && remoteController.isNextSupported()) {
				remoteController.changeNextTrack(nextPlaying);
			}
		} else {
			if(remoteState == LOCAL) {
				// resetNext();
			} else if(remoteController != null && remoteController.isNextSupported()) {
				remoteController.changeNextTrack(nextPlaying);
			}
			nextPlaying = null;
		}
	}

	public int getCurrentPlayingIndex() {
		return currentPlayingIndex;
	}
	private int getNextPlayingIndex() {
		int index = getCurrentPlayingIndex();
		if (index != -1) {
			RepeatMode repeatMode = getRepeatMode();
			switch (repeatMode) {
				case OFF:
					index = index + 1;
					break;
				case ALL:
					index = (index + 1) % size();
					break;
				case SINGLE:
					break;
				default:
					break;
			}

			index = checkNextIndexValid(index, repeatMode);
		}
		return index;
	}
	private int checkNextIndexValid(int index, RepeatMode repeatMode) {
		int startIndex = index;
		int size = size();
		if(index < size && index != -1) {
			if(!Util.isAllowedToDownload(this)){
				DownloadFile next = downloadList.get(index);
				while(!next.isCompleteFileAvailable()) {
					index++;

					if (index >= size) {
						if(repeatMode == RepeatMode.ALL) {
							index = 0;
						} else {
							return -1;
						}
					} else if(index == startIndex) {
						handler.post(new Runnable() {
							@Override
							public void run() {
								Util.toast(DownloadService.this, R.string.download_playerstate_mobile_disabled);
							}
						});
						return -1;
					}

					next = downloadList.get(index);
				}
			}
		}

		return index;
	}

	public DownloadFile getCurrentPlaying() {
		return currentPlaying;
	}

	public DownloadFile getCurrentDownloading() {
		return currentDownloading;
	}

	public DownloadFile getNextPlaying() {
		return nextPlaying;
	}

	public List<DownloadFile> getSongs() {
		return downloadList;
	}

	public List<DownloadFile> getToDelete() { return toDelete; }

	public synchronized List<DownloadFile> getDownloads() {
		List<DownloadFile> temp = new ArrayList<DownloadFile>();
		temp.addAll(downloadList);
		temp.addAll(backgroundDownloadList);
		return temp;
	}

	public List<DownloadFile> getBackgroundDownloads() {
		return backgroundDownloadList;
	}

	/** Plays either the current song (resume) or the first/next one in queue. */
	public synchronized void play()
	{
		int current = getCurrentPlayingIndex();
		if (current == -1) {
			play(0);
		} else {
			play(current);
		}
	}

	public synchronized void play(int index) {
		play(index, true);
	}
	public synchronized void play(DownloadFile downloadFile) {
		play(downloadList.indexOf(downloadFile));
	}
	private synchronized void play(int index, boolean start) {
		play(index, start, 0);
	}
	private synchronized void play(int index, boolean start, int position) {
		int size = this.size();
		cachedPosition = 0;
		if (index < 0 || index >= size) {
			reset();
			if(index >= size && size != 0) {
				setCurrentPlaying(0, false);
				Notifications.hidePlayingNotification(this, this, handler);
			} else {
				setCurrentPlaying(null, false);
			}
			lifecycleSupport.serializeDownloadQueue();
		} else {
			if(nextPlayingTask != null) {
				nextPlayingTask.cancel();
				nextPlayingTask = null;
			}
			setCurrentPlaying(index, start);
			if (start && remoteState != LOCAL) {
				remoteController.changeTrack(index, currentPlaying);
			}
			if (remoteState == LOCAL) {
				bufferAndPlay(position, start);
				checkDownloads();
				setNextPlaying();
			}
		}
	}
	private synchronized void playNext() {
		if(nextPlaying != null && nextPlayerState == PlayerState.PREPARED) {
			if(!nextSetup) {
				playNext(true);
			} else {
				nextSetup = false;
				playNext(false);
			}
		} else {
			onSongCompleted();
		}
	}
	private synchronized void playNext(boolean start) {
		Util.broadcastPlaybackStatusChange(this, currentPlaying.getSong(), PlayerState.PREPARED);
		
		// Swap the media players since nextMediaPlayer is ready to play
		subtractPosition = 0;
		if(start) {
			nextMediaPlayer.start();
		} else if(!nextMediaPlayer.isPlaying()) {
			Log.w(TAG, "nextSetup lied about it's state!");
			nextMediaPlayer.start();
		} else {
			Log.i(TAG, "nextMediaPlayer already playing");
			
			// Next time the cachedPosition is updated, use that as position 0
			subtractNextPosition = System.currentTimeMillis();
		}
		MediaPlayer tmp = mediaPlayer;
		mediaPlayer = nextMediaPlayer;
		nextMediaPlayer = tmp;
		setCurrentPlaying(nextPlaying, true);
		setPlayerState(PlayerState.STARTED);
		setupHandlers(currentPlaying, false, start);
		setNextPlaying();

		// Proxy should not be being used here since the next player was already setup to play
		if(proxy != null) {
			proxy.stop();
			proxy = null;
		}
		checkDownloads();
		updateRemotePlaylist();
	}

	/** Plays or resumes the playback, depending on the current player state. */
	public synchronized void togglePlayPause() {
		if (playerState == PAUSED || playerState == COMPLETED || playerState == STOPPED) {
			start();
		} else if (playerState == STOPPED || playerState == IDLE) {
			autoPlayStart = true;
			play();
		} else if (playerState == STARTED) {
			pause();
		}
	}

	public synchronized void seekTo(int position) {
		if(position < 0) {
			position = 0;
		}

		try {
			if (remoteState != LOCAL) {
				remoteController.changePosition(position / 1000);
			} else {
				if(proxy != null && currentPlaying.isCompleteFileAvailable()) {
					doPlay(currentPlaying, position, playerState == STARTED);
					return;
				}

				mediaPlayer.seekTo(position);
				subtractPosition = 0;
			}
			cachedPosition = position;

			onSongProgress();
			if(playerState == PAUSED) {
				lifecycleSupport.serializeDownloadQueue();
			}
		} catch (Exception x) {
			handleError(x);
		}
	}

	public synchronized void previous() {
		int index = getCurrentPlayingIndex();
		if (index == -1) {
			return;
		}

		// If only one song, just skip within song
		if(size() == 1) {
			seekTo(getPlayerPosition() - REWIND);
			return;
		}


		// Restart song if played more than five seconds.
		if (getPlayerPosition() > 5000 || (index == 0 && getRepeatMode() != RepeatMode.ALL)) {
			seekTo(0);
		} else {
			if(index == 0) {
				index = size();
			}

			play(index - 1, playerState != PAUSED && playerState != STOPPED && playerState != IDLE);
		}
	}

	public synchronized void next() {
		next(false);
	}
	public synchronized void next(boolean forceCutoff) {
		next(forceCutoff, false);
	}
	public synchronized void next(boolean forceCutoff, boolean forceStart) {
		// If only one song, just skip within song
		if(size() == 1) {
			seekTo(getPlayerPosition() + FAST_FORWARD);
			return;
		} else if(playerState == PREPARING || playerState == PREPARED) {
			return;
		}

		// Delete podcast if fully listened to
		int position = getPlayerPosition();
		int duration = getPlayerDuration();
		boolean cutoff;
		if(forceCutoff) {
			cutoff = true;
		} else {
			cutoff = isPastCutoff(position, duration);
		}
		if(currentPlaying != null && currentPlaying.getSong() instanceof PodcastEpisode) {
			if(cutoff) {
				toDelete.add(currentPlaying);
			}
		}
		if(cutoff) {
			clearCurrentBookmark(true);
		}
		if(currentPlaying != null) {
			scrobbler.conditionalScrobble(this, currentPlaying, position, duration);
		}

		int index = getCurrentPlayingIndex();
		int nextPlayingIndex = getNextPlayingIndex();
		// Make sure to actually go to next when repeat song is on
		if(index == nextPlayingIndex) {
			nextPlayingIndex++;
		}
		if (index != -1 && nextPlayingIndex < size()) {
			play(nextPlayingIndex, playerState != PAUSED && playerState != STOPPED && playerState != IDLE || forceStart);
		}
	}

	public void onSongCompleted() {
		setPlayerStateCompleted();
		postPlayCleanup();
		play(getNextPlayingIndex());
	}
	public void onNextStarted(DownloadFile nextPlaying) {
		setPlayerStateCompleted();
		postPlayCleanup();
		setCurrentPlaying(nextPlaying, true);
		setPlayerState(STARTED);
		setNextPlayerState(IDLE);
	}

	public synchronized void pause() {
		pause(false);
	}
	public synchronized void pause(boolean temp) {
		try {
			if (playerState == STARTED) {
				if (remoteState != LOCAL) {
					remoteController.stop();
				} else {
					mediaPlayer.pause();
				}
				setPlayerState(temp ? PAUSED_TEMP : PAUSED);
			} else if(playerState == PAUSED_TEMP) {
				setPlayerState(temp ? PAUSED_TEMP : PAUSED);
			}
		} catch (Exception x) {
			handleError(x);
		}
	}

	public synchronized void stop() {
		try {
			if (playerState == STARTED) {
				if (remoteState != LOCAL) {
					remoteController.stop();
					setPlayerState(STOPPED);
					handler.post(new Runnable() {
						@Override
						public void run() {
							mediaRouter.setDefaultRoute();
						}
					});
				} else {
					mediaPlayer.pause();
					setPlayerState(STOPPED);
				}
			} else if(playerState == PAUSED) {
				setPlayerState(STOPPED);
			}
		} catch(Exception x) {
			handleError(x);
		}
	}

	public synchronized void start() {
		try {
			if (remoteState != LOCAL) {
				remoteController.start();
			} else {
				// Only start if done preparing
				if(playerState != PREPARING) {
					mediaPlayer.start();
				} else {
					// Otherwise, we need to set it up to start when done preparing
					autoPlayStart = true;
				}
			}
			setPlayerState(STARTED);
		} catch (Exception x) {
			handleError(x);
		}
	}

	@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
	public synchronized void reset() {
		if (bufferTask != null) {
			bufferTask.cancel();
			bufferTask = null;
		}
		try {
			// Only set to idle if it's not being killed to start RemoteController
			if(remoteState == LOCAL) {
				setPlayerState(IDLE);
			}
			mediaPlayer.setOnErrorListener(null);
			mediaPlayer.setOnCompletionListener(null);
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN && nextSetup) {
				mediaPlayer.setNextMediaPlayer(null);
				nextSetup = false;
			}
			mediaPlayer.reset();
			subtractPosition = 0;
		} catch (Exception x) {
			handleError(x);
		}
	}

	@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
	public synchronized void resetNext() {
		try {
			if (nextMediaPlayer != null) {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN && nextSetup) {
					mediaPlayer.setNextMediaPlayer(null);
				}
				nextSetup = false;

				nextMediaPlayer.setOnCompletionListener(null);
				nextMediaPlayer.setOnErrorListener(null);
				nextMediaPlayer.reset();
				nextMediaPlayer.release();
				nextMediaPlayer = null;
			} else if(nextSetup) {
				nextSetup = false;
			}
		} catch (Exception e) {
			Log.w(TAG, "Failed to reset next media player");
		}
	}

	public int getPlayerPosition() {
		try {
			if (playerState == IDLE || playerState == DOWNLOADING || playerState == PREPARING) {
				return 0;
			}
			if (remoteState != LOCAL) {
				return remoteController.getRemotePosition() * 1000;
			} else {
				return Math.max(0, cachedPosition - subtractPosition);
			}
		} catch (Exception x) {
			handleError(x);
			return 0;
		}
	}

	public synchronized int getPlayerDuration() {
		if (playerState != IDLE && playerState != DOWNLOADING && playerState != PlayerState.PREPARING) {
			int duration = 0;
			if(remoteState == LOCAL) {
				try {
					duration = mediaPlayer.getDuration();
				} catch (Exception x) {
					duration = 0;
				}
			} else {
				duration = remoteController.getRemoteDuration() * 1000;
			}

			if(duration != 0) {
				return duration;
			}
		}

		if (currentPlaying != null) {
			Integer duration = currentPlaying.getSong().getDuration();
			if (duration != null) {
				return duration * 1000;
			}
		}

		return 0;
	}

	public PlayerState getPlayerState() {
		return playerState;
	}

	public PlayerState getNextPlayerState() {
		return nextPlayerState;
	}

	public synchronized void setPlayerState(final PlayerState playerState) {
		Log.i(TAG, this.playerState.name() + " -> " + playerState.name() + " (" + currentPlaying + ")");

		if (playerState == PAUSED) {
			lifecycleSupport.serializeDownloadQueue();
			checkAddBookmark(true);
		}

		boolean show = playerState == PlayerState.STARTED;
		boolean pause = playerState == PlayerState.PAUSED;
		boolean hide = playerState == PlayerState.STOPPED;
		Util.broadcastPlaybackStatusChange(this, (currentPlaying != null) ? currentPlaying.getSong() : null, playerState);

		this.playerState = playerState;

		if(playerState == STARTED) {
			Util.requestAudioFocus(this);
		}

		if (show) {
			Notifications.showPlayingNotification(this, this, handler, currentPlaying.getSong());
		} else if (pause) {
			SharedPreferences prefs = Util.getPreferences(this);
			if(prefs.getBoolean(Constants.PREFERENCES_KEY_PERSISTENT_NOTIFICATION, false)) {
				Notifications.showPlayingNotification(this, this, handler, currentPlaying.getSong());
			} else {
				Notifications.hidePlayingNotification(this, this, handler);
			}
		} else if(hide) {
			Notifications.hidePlayingNotification(this, this, handler);
		}
		if(mRemoteControl != null) {
			mRemoteControl.setPlaybackState(playerState.getRemoteControlClientPlayState());
		}

		if (playerState == STARTED) {
			scrobbler.scrobble(this, currentPlaying, false);
		} else if (playerState == COMPLETED) {
			scrobbler.scrobble(this, currentPlaying, true);
		}

		if(playerState == STARTED && positionCache == null) {
			if(remoteState == LOCAL) {
				positionCache = new LocalPositionCache();
			} else {
				positionCache = new PositionCache();
			}
			Thread thread = new Thread(positionCache, "PositionCache");
			thread.start();
		} else if(playerState != STARTED && positionCache != null) {
			positionCache.stop();
			positionCache = null;
		}


		if(remoteState != LOCAL) {
			if(playerState == STARTED) {
				if (!wifiLock.isHeld()) {
					wifiLock.acquire();
				}
			} else if(playerState == PAUSED && wifiLock.isHeld()) {
				wifiLock.release();
			}
		}

		if(remoteController != null && remoteController.isNextSupported()) {
			if(playerState == PREPARING || playerState == IDLE) {
				nextPlayerState = IDLE;
			}
		}

		onStateUpdate();
	}

	public void setPlayerStateCompleted() {
		// Acquire a temporary wakelock
		acquireWakelock();

		Log.i(TAG, this.playerState.name() + " -> " + PlayerState.COMPLETED + " (" + currentPlaying + ")");
		this.playerState = PlayerState.COMPLETED;
		if(positionCache != null) {
			positionCache.stop();
			positionCache = null;
		}
		scrobbler.scrobble(this, currentPlaying, true);

		onStateUpdate();
	}

	private class PositionCache implements Runnable {
		boolean isRunning = true;

		public void stop() {
			isRunning = false;
		}

		@Override
		public void run() {
			// Stop checking position before the song reaches completion
			while(isRunning) {
				try {
					onSongProgress();
					Thread.sleep(1000L);
				}
				catch(Exception e) {
					isRunning = false;
					positionCache = null;
				}
			}
		}
	}
	private class LocalPositionCache extends PositionCache {
		boolean isRunning = true;

		public void stop() {
			isRunning = false;
		}

		@Override
		public void run() {
			// Stop checking position before the song reaches completion
			while(isRunning) {
				try {
					if(mediaPlayer != null && playerState == STARTED) {
						int newPosition = mediaPlayer.getCurrentPosition();

						// If sudden jump in position, something is wrong
						if(subtractNextPosition == 0 && newPosition > (cachedPosition + 5000)) {
							// Only 1 second should have gone by, subtract the rest
							subtractPosition += (newPosition - cachedPosition) - 1000;
						}

						cachedPosition = newPosition;

						if(subtractNextPosition > 0) {
							// Subtraction amount is current position - how long ago onCompletionListener was called
							subtractPosition = cachedPosition - (int) (System.currentTimeMillis() - subtractNextPosition);
							if(subtractPosition < 0) {
								subtractPosition = 0;
							}
							subtractNextPosition = 0;
						}
					}
					onSongProgress(cachedPosition < 2000 ? true: false);
					Thread.sleep(1000L);
				}
				catch(Exception e) {
					Log.w(TAG, "Crashed getting current position", e);
					isRunning = false;
					positionCache = null;
				}
			}
		}
	}

	public synchronized void setNextPlayerState(PlayerState playerState) {
		Log.i(TAG, "Next: " + this.nextPlayerState.name() + " -> " + playerState.name() + " (" + nextPlaying + ")");
		this.nextPlayerState = playerState;
	}

	public void setSuggestedPlaylistName(String name, String id) {
		this.suggestedPlaylistName = name;
		this.suggestedPlaylistId = id;

		SharedPreferences.Editor editor = Util.getPreferences(this).edit();
		editor.putString(Constants.PREFERENCES_KEY_PLAYLIST_NAME, name);
		editor.putString(Constants.PREFERENCES_KEY_PLAYLIST_ID, id);
		editor.commit();
	}

	public String getSuggestedPlaylistName() {
		return suggestedPlaylistName;
	}

	public String getSuggestedPlaylistId() {
		return suggestedPlaylistId;
	}

	public boolean getEqualizerAvailable() {
		return effectsController.isAvailable();
	}

	public EqualizerController getEqualizerController() {
		EqualizerController controller = null;
		try {
			controller = effectsController.getEqualizerController();
			if(controller.getEqualizer() == null) {
				throw new Exception("Failed to get EQ");
			}
		} catch(Exception e) {
			Log.w(TAG, "Failed to start EQ, retrying with new mediaPlayer: " + e);

			// If we failed, we are going to try to reinitialize the MediaPlayer
			boolean playing = playerState == STARTED;
			int pos = getPlayerPosition();
			mediaPlayer.pause();
			Util.sleepQuietly(10L);
			reset();

			try {
				// Resetup media player
				mediaPlayer.setAudioSessionId(audioSessionId);
				mediaPlayer.setDataSource(currentPlaying.getFile().getCanonicalPath());

				controller = effectsController.getEqualizerController();
				if(controller.getEqualizer() == null) {
					throw new Exception("Failed to get EQ");
				}
			} catch(Exception e2) {
				Log.w(TAG, "Failed to setup EQ even after reinitialization");
				// Don't try again, just resetup media player and continue on
				controller = null;
			}

			// Restart from same position and state we left off in
			play(getCurrentPlayingIndex(), false, pos);
		}

		return controller;
	}

	public MediaRouteSelector getRemoteSelector() {
		return mediaRouter.getSelector();
	}

	public boolean isSeekable() {
		if(remoteState == LOCAL) {
			return currentPlaying != null && currentPlaying.isWorkDone() && playerState != PREPARING;
		} else if(remoteController != null) {
			return remoteController.isSeekable();
		} else {
			return false;
		}
	}

	public boolean isRemoteEnabled() {
		return remoteState != LOCAL;
	}

	public RemoteController getRemoteController() {
		return remoteController;
	}

	public void setRemoteEnabled(RemoteControlState newState) {
		if(instance != null) {
			setRemoteEnabled(newState, null);
		}
	}
	public void setRemoteEnabled(RemoteControlState newState, Object ref) {
		setRemoteState(newState, ref);

		RouteInfo info = mediaRouter.getSelectedRoute();
		String routeId = info.getId();

		SharedPreferences.Editor editor = Util.getPreferences(this).edit();
		editor.putInt(Constants.PREFERENCES_KEY_CONTROL_MODE, newState.getValue());
		editor.putString(Constants.PREFERENCES_KEY_CONTROL_ID, routeId);
		editor.commit();
	}
	private void setRemoteState(RemoteControlState newState, Object ref) {
		setRemoteState(newState, ref, null);
	}
	private void setRemoteState(final RemoteControlState newState, final Object ref, final String routeId) {
		// Don't try to do anything if already in the correct state
		if(remoteState == newState) {
			return;
		}

		boolean isPlaying = playerState == STARTED;
		int position = getPlayerPosition();

		if(remoteController != null) {
			remoteController.stop();
			setPlayerState(PlayerState.IDLE);
			remoteController.shutdown();
			remoteController = null;

			if(newState == LOCAL) {
				mediaRouter.setDefaultRoute();
			}
		}

		Log.i(TAG, remoteState.name() + " => " + newState.name() + " (" + currentPlaying + ")");
		remoteState = newState;
		switch(newState) {
			case JUKEBOX_SERVER:
				remoteController = new JukeboxController(this, handler);
				break;
			case CHROMECAST: case DLNA:
				if(ref == null) {
					remoteState = LOCAL;
					break;
				}
				remoteController = (RemoteController) ref;
				break;
			case LOCAL: default:
				if(wifiLock.isHeld()) {
					wifiLock.release();
				}
				break;
		}

		if(remoteState != LOCAL) {
			if(!wifiLock.isHeld()) {
				wifiLock.acquire();
			}
		} else if(wifiLock.isHeld()) {
			wifiLock.release();
		}

		if(remoteController != null) {
			remoteController.create(isPlaying, position / 1000);
		} else {
			play(getCurrentPlayingIndex(), isPlaying, position);
		}

		if (remoteState != LOCAL) {
			reset();

			// Cancel current download, if necessary.
			if (currentDownloading != null) {
				currentDownloading.cancelDownload();
			}

			// Cancels current setup tasks
			if(bufferTask != null && bufferTask.isRunning()) {
				bufferTask.cancel();
				bufferTask = null;
			}
			if(nextPlayingTask != null && nextPlayingTask.isRunning()) {
				nextPlayingTask.cancel();
				nextPlayingTask = null;
			}

			if(nextPlayerState != IDLE) {
				setNextPlayerState(IDLE);
			}
		}

		if(remoteState == LOCAL) {
			checkDownloads();
		}

		if(routeId != null) {
			final Runnable delayedReconnect = new Runnable() {
				@Override
				public void run() {
					RouteInfo info = mediaRouter.getRouteForId(routeId);
					if(info == null) {
						setRemoteState(LOCAL, null);
					} else if(newState == RemoteControlState.CHROMECAST) {
						RemoteController controller = mediaRouter.getRemoteController(info);
						if(controller != null) {
							setRemoteState(RemoteControlState.CHROMECAST, controller);
						}
					}
					mediaRouter.stopScan();
				}
			};

			handler.post(new Runnable() {
				@Override
				public void run() {
					mediaRouter.startScan();
					RouteInfo info = mediaRouter.getRouteForId(routeId);
					if(info == null) {
						handler.postDelayed(delayedReconnect, 2000L);
					} else if(newState == RemoteControlState.CHROMECAST) {
						RemoteController controller = mediaRouter.getRemoteController(info);
						if(controller != null) {
							setRemoteState(RemoteControlState.CHROMECAST, controller);
						}
					}
				}
			});
		}
	}

	public void registerRoute(MediaRouter router) {
		if (mRemoteControl != null) {
			mRemoteControl.registerRoute(router);
		}
	}
	public void unregisterRoute(MediaRouter router) {
		if(mRemoteControl != null) {
			mRemoteControl.unregisterRoute(router);
		}
	}

	public void updateRemoteVolume(boolean up) {
		AudioManager audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
		audioManager.adjustVolume(up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
	}

	public void startRemoteScan() {
		mediaRouter.startScan();
	}

	public void stopRemoteScan() {
		mediaRouter.stopScan();
	}

	private synchronized void bufferAndPlay() {
		bufferAndPlay(0);
	}
	private synchronized void bufferAndPlay(int position) {
		bufferAndPlay(position, true);
	}
	private synchronized void bufferAndPlay(int position, boolean start) {
		if(!currentPlaying.isCompleteFileAvailable()) {
			if(Util.isAllowedToDownload(this)) {
				reset();

				bufferTask = new BufferTask(currentPlaying, position, start);
				bufferTask.execute();
			} else {
				next(false, start);
			}
		} else {
			doPlay(currentPlaying, position, start);
		}
	}

	private synchronized void doPlay(final DownloadFile downloadFile, final int position, final boolean start) {
		try {
			downloadFile.setPlaying(true);
			final File file = downloadFile.isCompleteFileAvailable() ? downloadFile.getCompleteFile() : downloadFile.getPartialFile();
			boolean isPartial = file.equals(downloadFile.getPartialFile());
			downloadFile.updateModificationDate();

			subtractPosition = 0;
			mediaPlayer.setOnCompletionListener(null);
			mediaPlayer.setOnPreparedListener(null);
			mediaPlayer.setOnErrorListener(null);
			mediaPlayer.reset();
			setPlayerState(IDLE);
			try {
				mediaPlayer.setAudioSessionId(audioSessionId);
			} catch(Throwable e) {
				mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
			}
			String dataSource = file.getAbsolutePath();
			if(isPartial && !Util.isOffline(this)) {
				if (proxy == null) {
					proxy = new BufferProxy(this);
					proxy.start();
				}
				proxy.setBufferFile(downloadFile);
				dataSource = proxy.getPrivateAddress(dataSource);
				Log.i(TAG, "Data Source: " + dataSource);
			} else if(proxy != null) {
				proxy.stop();
				proxy = null;
			}
			mediaPlayer.setDataSource(dataSource);
			setPlayerState(PREPARING);

			mediaPlayer.setOnBufferingUpdateListener(new MediaPlayer.OnBufferingUpdateListener() {
				public void onBufferingUpdate(MediaPlayer mp, int percent) {
					Log.i(TAG, "Buffered " + percent + "%");
					if (percent == 100) {
						mediaPlayer.setOnBufferingUpdateListener(null);
					}
				}
			});

			mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
				public void onPrepared(MediaPlayer mediaPlayer) {
					try {
						setPlayerState(PREPARED);

						synchronized (DownloadService.this) {
							if (position != 0) {
								Log.i(TAG, "Restarting player from position " + position);
								mediaPlayer.seekTo(position);
							}
							cachedPosition = position;

							applyReplayGain(mediaPlayer, downloadFile);

							if (start || autoPlayStart) {
								mediaPlayer.start();
								setPlayerState(STARTED);

								// Disable autoPlayStart after done
								autoPlayStart = false;
							} else {
								setPlayerState(PAUSED);
								onSongProgress();
							}

							updateRemotePlaylist();
						}

						// Only call when starting, setPlayerState(PAUSED) already calls this
						if(start) {
							lifecycleSupport.serializeDownloadQueue();
						}
					} catch (Exception x) {
						handleError(x);
					}
				}
			});

			setupHandlers(downloadFile, isPartial, start);

			mediaPlayer.prepareAsync();
		} catch (Exception x) {
			handleError(x);
		}
	}

	@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
	private synchronized void setupNext(final DownloadFile downloadFile) {
		try {
			final File file = downloadFile.isCompleteFileAvailable() ? downloadFile.getCompleteFile() : downloadFile.getPartialFile();
			resetNext();

			// Exit when using remote controllers
			if(remoteState != LOCAL) {
				return;
			}

			nextMediaPlayer = new MediaPlayer();
			nextMediaPlayer.setWakeMode(DownloadService.this, PowerManager.PARTIAL_WAKE_LOCK);
			try {
				nextMediaPlayer.setAudioSessionId(audioSessionId);
			} catch(Throwable e) {
				nextMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
			}
			nextMediaPlayer.setDataSource(file.getPath());
			setNextPlayerState(PREPARING);

			nextMediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
				public void onPrepared(MediaPlayer mp) {
					// Changed to different while preparing so ignore
					if(nextMediaPlayer != mp) {
						return;
					}

					try {
						setNextPlayerState(PREPARED);

						if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN && (playerState == PlayerState.STARTED || playerState == PlayerState.PAUSED)) {
							mediaPlayer.setNextMediaPlayer(nextMediaPlayer);
							nextSetup = true;
						}

						applyReplayGain(nextMediaPlayer, downloadFile);
					} catch (Exception x) {
						handleErrorNext(x);
					}
				}
			});

			nextMediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
				public boolean onError(MediaPlayer mediaPlayer, int what, int extra) {
					Log.w(TAG, "Error on playing next " + "(" + what + ", " + extra + "): " + downloadFile);
					return true;
				}
			});

			nextMediaPlayer.prepareAsync();
		} catch (Exception x) {
			handleErrorNext(x);
		}
	}

	private void setupHandlers(final DownloadFile downloadFile, final boolean isPartial, final boolean isPlaying) {
		final int duration = downloadFile.getSong().getDuration() == null ? 0 : downloadFile.getSong().getDuration() * 1000;
		mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
			public boolean onError(MediaPlayer mediaPlayer, int what, int extra) {
				Log.w(TAG, "Error on playing file " + "(" + what + ", " + extra + "): " + downloadFile);
				int pos = getPlayerPosition();
				reset();
				if (!isPartial || (downloadFile.isWorkDone() && (Math.abs(duration - pos) < 10000))) {
					playNext();
				} else {
					downloadFile.setPlaying(false);
					doPlay(downloadFile, pos, isPlaying);
					downloadFile.setPlaying(true);
				}
				return true;
			}
		});

		mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
			@Override
			public void onCompletion(MediaPlayer mediaPlayer) {
				setPlayerStateCompleted();

				int pos = getPlayerPosition();
				Log.i(TAG, "Ending position " + pos + " of " + duration);
				if (!isPartial || (downloadFile.isWorkDone() && (Math.abs(duration - pos) < 10000)) || nextSetup) {
					playNext();
					postPlayCleanup(downloadFile);
				} else {
					// If file is not completely downloaded, restart the playback from the current position.
					synchronized (DownloadService.this) {
						if (downloadFile.isWorkDone()) {
							// Complete was called early even though file is fully buffered
							Log.i(TAG, "Requesting restart from " + pos + " of " + duration);
							reset();
							downloadFile.setPlaying(false);
							doPlay(downloadFile, pos, true);
							downloadFile.setPlaying(true);
						} else {
							Log.i(TAG, "Requesting restart from " + pos + " of " + duration);
							reset();
							bufferTask = new BufferTask(downloadFile, pos, true);
							bufferTask.execute();
						}
					}
					checkDownloads();
				}
			}
		});
	}

	public void setSleepTimerDuration(int duration){
		timerDuration = duration;
	}

	public void startSleepTimer(){
		if(sleepTimer != null){
			sleepTimer.cancel();
			sleepTimer.purge();
		}

		sleepTimer = new Timer();

		sleepTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				pause();
				sleepTimer.cancel();
				sleepTimer.purge();
				sleepTimer = null;
			}

		}, timerDuration * 60 * 1000);
		timerStart = System.currentTimeMillis();
	}

	public int getSleepTimeRemaining() {
		return (int) (timerStart + (timerDuration * 60 * 1000) - System.currentTimeMillis()) / 1000;
	}

	public void stopSleepTimer() {
		if(sleepTimer != null){
			sleepTimer.cancel();
			sleepTimer.purge();
		}
		sleepTimer = null;
	}

	public boolean getSleepTimer() {
		return sleepTimer != null;
	}

	public void setVolume(float volume) {
		if(mediaPlayer != null && (playerState == STARTED || playerState == PAUSED || playerState == STOPPED)) {
			try {
				this.volume = volume;
				reapplyVolume();
			} catch(Exception e) {
				Log.w(TAG, "Failed to set volume");
			}
		}
	}

	public void reapplyVolume() {
		applyReplayGain(mediaPlayer, currentPlaying);
	}

	public synchronized void swap(boolean mainList, DownloadFile from, DownloadFile to) {
		List<DownloadFile> list = mainList ? downloadList : backgroundDownloadList;
		swap(mainList, list.indexOf(from), list.indexOf(to));
	}
	public synchronized void swap(boolean mainList, int from, int to) {
		List<DownloadFile> list = mainList ? downloadList : backgroundDownloadList;
		int max = list.size();
		if(to >= max) {
			to = max - 1;
		}
		else if(to < 0) {
			to = 0;
		}

		DownloadFile movedSong = list.remove(from);
		list.add(to, movedSong);
		currentPlayingIndex = downloadList.indexOf(currentPlaying);
		if(mainList) {
			if(remoteState == LOCAL || (remoteController != null && remoteController.isNextSupported())) {
				// Moving next playing, current playing, or moving a song to be next playing
				if(movedSong == nextPlaying || movedSong == currentPlaying || (currentPlayingIndex + 1) == to) {
					setNextPlaying();
				}
			} else {
				updateRemotePlaylist();
			}
		}
	}

	public synchronized void serializeQueue() {
		serializeQueue(true);
	}
	public synchronized void serializeQueue(boolean serializeRemote) {
		if(playerState == PlayerState.PAUSED) {
			lifecycleSupport.serializeDownloadQueue(serializeRemote);
		}
	}

	private void handleError(Exception x) {
		Log.w(TAG, "Media player error: " + x, x);
		if(mediaPlayer != null) {
			try {
				mediaPlayer.reset();
			} catch(Exception e) {
				Log.e(TAG, "Failed to reset player in error handler");
			}
		}
		setPlayerState(IDLE);
	}
	private void handleErrorNext(Exception x) {
		Log.w(TAG, "Next Media player error: " + x, x);
		try {
			nextMediaPlayer.reset();
		} catch(Exception e) {
			Log.e(TAG, "Failed to reset next media player", x);
		}
		setNextPlayerState(IDLE);
	}

	public synchronized void checkDownloads() {
		if (!Util.isExternalStoragePresent() || !lifecycleSupport.isExternalStorageAvailable()) {
			return;
		}

		if(removePlayed) {
			checkRemovePlayed();
		}
		if (shufflePlay) {
			checkShufflePlay();
		}
		if(artistRadio) {
			checkArtistRadio();
		}

		if (!Util.isNetworkConnected(this, true) || Util.isOffline(this)) {
			return;
		}

		if (downloadList.isEmpty() && backgroundDownloadList.isEmpty()) {
			return;
		}

		// Need to download current playing and not casting?
		if (currentPlaying != null && remoteState == LOCAL && currentPlaying != currentDownloading && !currentPlaying.isWorkDone()) {
			// Cancel current download, if necessary.
			if (currentDownloading != null) {
				currentDownloading.cancelDownload();
			}

			currentDownloading = currentPlaying;
			currentDownloading.download();
			cleanupCandidates.add(currentDownloading);
		}

		// Find a suitable target for download.
		else if (currentDownloading == null || currentDownloading.isWorkDone() || currentDownloading.isFailed() && ((!downloadList.isEmpty() && remoteState == LOCAL) || !backgroundDownloadList.isEmpty())) {
			currentDownloading = null;
			int n = size();

			int preloaded = 0;

			if(n != 0 && remoteState == LOCAL) {
				int start = currentPlaying == null ? 0 : getCurrentPlayingIndex();
				if(start == -1) {
					start = 0;
				}
				int i = start;
				do {
					DownloadFile downloadFile = downloadList.get(i);
					if (!downloadFile.isWorkDone() && !downloadFile.isFailedMax()) {
						if (downloadFile.shouldSave() || preloaded < Util.getPreloadCount(this)) {
							currentDownloading = downloadFile;
							currentDownloading.download();
							cleanupCandidates.add(currentDownloading);
							if(i == (start + 1)) {
								setNextPlayerState(DOWNLOADING);
							}
							break;
						}
					} else if (currentPlaying != downloadFile) {
						preloaded++;
					}

					i = (i + 1) % n;
				} while (i != start);
			}

			if((preloaded + 1 == n || preloaded >= Util.getPreloadCount(this) || downloadList.isEmpty() || remoteState != LOCAL) && !backgroundDownloadList.isEmpty()) {
				for(int i = 0; i < backgroundDownloadList.size(); i++) {
					DownloadFile downloadFile = backgroundDownloadList.get(i);
					if(downloadFile.isWorkDone() && (!downloadFile.shouldSave() || downloadFile.isSaved()) || downloadFile.isFailedMax()) {
						// Don't need to keep list like active song list
						backgroundDownloadList.remove(i);
						revision++;
						i--;
					} else {
						currentDownloading = downloadFile;
						currentDownloading.download();
						cleanupCandidates.add(currentDownloading);
						break;
					}
				}
			}
		}

		if(!backgroundDownloadList.isEmpty()) {
			Notifications.showDownloadingNotification(this, this, handler, currentDownloading, backgroundDownloadList.size());
			downloadOngoing = true;
		} else if(backgroundDownloadList.isEmpty() && downloadOngoing) {
			Notifications.hideDownloadingNotification(this, this, handler);
			downloadOngoing = false;
		}

		// Delete obsolete .partial and .complete files.
		cleanup();
	}

	private synchronized void checkRemovePlayed() {
		boolean changed = false;
		SharedPreferences prefs = Util.getPreferences(this);
		int keepCount = Integer.parseInt(prefs.getString(Constants.PREFERENCES_KEY_KEEP_PLAYED_CNT, "0"));
		while(currentPlayingIndex > keepCount) {
			downloadList.remove(0);
			currentPlayingIndex = downloadList.indexOf(currentPlaying);
			changed = true;
		}

		if(changed) {
			revision++;
			onSongsChanged();
		}
	}

	private synchronized void checkShufflePlay() {

		// Get users desired random playlist size
		SharedPreferences prefs = Util.getPreferences(this);
		int listSize = Math.max(1, Integer.parseInt(prefs.getString(Constants.PREFERENCES_KEY_RANDOM_SIZE, "20")));
		boolean wasEmpty = downloadList.isEmpty();

		long revisionBefore = revision;

		// First, ensure that list is at least 20 songs long.
		int size = size();
		if (size < listSize) {
			for (MusicDirectory.Entry song : shufflePlayBuffer.get(listSize - size)) {
				DownloadFile downloadFile = new DownloadFile(this, song, false);
				downloadList.add(downloadFile);
				revision++;
			}
		}

		int currIndex = currentPlaying == null ? 0 : getCurrentPlayingIndex();

		// Only shift playlist if playing song #5 or later.
		if (currIndex > 4) {
			int songsToShift = currIndex - 2;
			for (MusicDirectory.Entry song : shufflePlayBuffer.get(songsToShift)) {
				downloadList.add(new DownloadFile(this, song, false));
				downloadList.get(0).cancelDownload();
				downloadList.remove(0);
				revision++;
			}
		}
		currentPlayingIndex = downloadList.indexOf(currentPlaying);

		if (revisionBefore != revision) {
			onSongsChanged();
			updateRemotePlaylist();
		}

		if (wasEmpty && !downloadList.isEmpty()) {
			play(0);
		}
	}

	private synchronized void checkArtistRadio() {
		// Get users desired random playlist size
		SharedPreferences prefs = Util.getPreferences(this);
		int listSize = Math.max(1, Integer.parseInt(prefs.getString(Constants.PREFERENCES_KEY_RANDOM_SIZE, "20")));
		boolean wasEmpty = downloadList.isEmpty();

		long revisionBefore = revision;

		// First, ensure that list is at least 20 songs long.
		int size = size();
		if (size < listSize) {
			for (MusicDirectory.Entry song : artistRadioBuffer.get(listSize - size)) {
				DownloadFile downloadFile = new DownloadFile(this, song, false);
				downloadList.add(downloadFile);
				revision++;
			}
		}

		int currIndex = currentPlaying == null ? 0 : getCurrentPlayingIndex();

		// Only shift playlist if playing song #5 or later.
		if (currIndex > 4) {
			int songsToShift = currIndex - 2;
			for (MusicDirectory.Entry song : artistRadioBuffer.get(songsToShift)) {
				downloadList.add(new DownloadFile(this, song, false));
				downloadList.get(0).cancelDownload();
				downloadList.remove(0);
				revision++;
			}
		}
		currentPlayingIndex = downloadList.indexOf(currentPlaying);

		if (revisionBefore != revision) {
			onSongsChanged();
			updateRemotePlaylist();
		}

		if (wasEmpty && !downloadList.isEmpty()) {
			play(0);
		}
	}

	public long getDownloadListUpdateRevision() {
		return revision;
	}

	private synchronized void cleanup() {
		Iterator<DownloadFile> iterator = cleanupCandidates.iterator();
		while (iterator.hasNext()) {
			DownloadFile downloadFile = iterator.next();
			if (downloadFile != currentPlaying && downloadFile != currentDownloading) {
				if (downloadFile.cleanup()) {
					iterator.remove();
				}
			}
		}
	}

	public void postPlayCleanup() {
		postPlayCleanup(currentPlaying);
	}
	public void postPlayCleanup(DownloadFile downloadFile) {
		if(downloadFile == null) {
			return;
		}

		// Finished loading, delete when list is cleared
		if (downloadFile.getSong() instanceof PodcastEpisode) {
			toDelete.add(downloadFile);
		}
		clearCurrentBookmark(downloadFile.getSong(), true);
	}

	public RemoteControlClientBase getRemoteControlClient() {
		return mRemoteControl;
	}
	
	private boolean isPastCutoff() {
		return isPastCutoff(getPlayerPosition(), getPlayerDuration());
	}
	private boolean isPastCutoff(int position, int duration) {
		return isPastCutoff(position, duration, false);
	}
	private boolean isPastCutoff(int position, int duration, boolean allowSkipping) {
		if(currentPlaying == null) {
			return false;
		}

		// Make cutoff a maximum of 10 minutes
		int cutoffPoint = Math.min((int) (duration * DELETE_CUTOFF), 10 * 60 * 1000);
		boolean isPastCutoff = duration > 0 && position > cutoffPoint;
		
		// Check to make sure song isn't within 10 seconds of where it was created
		MusicDirectory.Entry entry = currentPlaying.getSong();
		if(entry != null && entry.getBookmark() != null) {
			Bookmark bookmark = entry.getBookmark();
			if(position < (bookmark.getPosition() + 10000)) {
				isPastCutoff = false;
			}
		}
		
		// Check to make sure we aren't in a series of similar content before deleting bookmark
		if(isPastCutoff && allowSkipping) {
			// Check to make sure:
			// Is an audio book
			// Next playing exists and is not a wrap around or a shuffle
			// Next playing is from same context as current playing, so not at end of list
			if(entry.isAudioBook() && nextPlaying != null && downloadList.indexOf(nextPlaying) != 0 && !shufflePlay
					&& entry.getParent() != null && entry.getParent().equals(nextPlaying.getSong().getParent())) {
				isPastCutoff = false;
			}
		}
		
		return isPastCutoff;
	}
	
	private void clearCurrentBookmark() {
		clearCurrentBookmark(false);
	}
	private void clearCurrentBookmark(boolean checkDelete) {
		// If current is null, nothing to do
		if(currentPlaying == null) {
			return;
		}

		clearCurrentBookmark(currentPlaying.getSong(), checkDelete);
	}
	private void clearCurrentBookmark(final MusicDirectory.Entry entry, boolean checkDelete) {
		// If no bookmark, move on
		if(entry.getBookmark() == null) {
			return;
		}
		
		// If delete is not specified, check position
		if(!checkDelete) {
			checkDelete = isPastCutoff();
		}
		
		// If supposed to delete
		if(checkDelete) {
			new SilentBackgroundTask<Void>(this) {
				@Override
				public Void doInBackground() throws Throwable {
					MusicService musicService = MusicServiceFactory.getMusicService(DownloadService.this);
					entry.setBookmark(null);
					musicService.deleteBookmark(entry, DownloadService.this, null);

					MusicDirectory.Entry found = UpdateView.findEntry(entry);
					if(found != null) {
						found.setBookmark(null);
					}
					return null;
				}

				@Override
				public void error(Throwable error) {
					Log.e(TAG, "Failed to delete bookmark", error);
					
					String msg;
					if(error instanceof OfflineException || error instanceof ServerTooOldException) {
						msg = getErrorMessage(error);
					} else {
						msg = DownloadService.this.getResources().getString(R.string.bookmark_deleted_error, entry.getTitle()) + " " + getErrorMessage(error);
					}
					
					Util.toast(DownloadService.this, msg, false);
				}
			}.execute();
		}
	}

	private void checkAddBookmark() {
		checkAddBookmark(false);
	}
	private void checkAddBookmark(final boolean updateMetadata) {
		// Don't do anything if no current playing
		if(currentPlaying == null || !ServerInfo.canBookmark(this)) {
			return;
		}
		
		final MusicDirectory.Entry entry = currentPlaying.getSong();
		int duration = getPlayerDuration();
		
		// If song is podcast or long go ahead and auto add a bookmark
		if(entry.isPodcast() || entry.isAudioBook() || duration > (10L * 60L * 1000L)) {
			final Context context = this;
			final int position = getPlayerPosition();

			// Don't bother when at beginning
			if(position < 5000L) {
				return;
			}

			new SilentBackgroundTask<Void>(context) {
				@Override
				public Void doInBackground() throws Throwable {
					MusicService musicService = MusicServiceFactory.getMusicService(context);
					entry.setBookmark(new Bookmark(position));
					musicService.createBookmark(entry, position, "Auto created by DSub", context, null);

					MusicDirectory.Entry found = UpdateView.findEntry(entry);
					if(found != null) {
						found.setBookmark(new Bookmark(position));
					}
					if(updateMetadata) {
						onMetadataUpdate(METADATA_UPDATED_BOOKMARK);
					}
					
					return null;
				}
				
				@Override
				public void error(Throwable error) {
					Log.w(TAG, "Failed to create automatic bookmark", error);
					
					String msg;
					if(error instanceof OfflineException || error instanceof ServerTooOldException) {
						msg = getErrorMessage(error);
					} else {
						msg = context.getResources().getString(R.string.download_save_bookmark_failed) + getErrorMessage(error);
					}
					
					Util.toast(context, msg, false);
				}
			}.execute();
		}
	}

	private void applyReplayGain(MediaPlayer mediaPlayer, DownloadFile downloadFile) {
		if(currentPlaying == null) {
			return;
		}

		SharedPreferences prefs = Util.getPreferences(this);
		try {
			float adjust = 0f;
			if (prefs.getBoolean(Constants.PREFERENCES_KEY_REPLAY_GAIN, false)) {
				float[] rg = BastpUtil.getReplayGainValues(downloadFile.getFile().getCanonicalPath()); /* track, album */
				boolean singleAlbum = false;
				
				String replayGainType = prefs.getString(Constants.PREFERENCES_KEY_REPLAY_GAIN_TYPE, "1");
				// 1 => Smart replay gain
				if("1".equals(replayGainType)) {
					// Check if part of at least <REQUIRED_ALBUM_MATCHES> consequetive songs of the same album
					
					int index = downloadList.indexOf(downloadFile);
					if(index != -1) {
						String albumName = downloadFile.getSong().getAlbum();
						int matched = 0;
						
						// Check forwards
						for(int i = index + 1; i < downloadList.size() && matched < REQUIRED_ALBUM_MATCHES; i++) {
							if(albumName.equals(downloadList.get(i).getSong().getAlbum())) {
								matched++;
							} else {
								break;
							}
						}
						
						// Check backwards
						for(int i = index - 1; i >= 0 && matched < REQUIRED_ALBUM_MATCHES; i--) {
							if(albumName.equals(downloadList.get(i).getSong().getAlbum())) {
								matched++;
							} else {
								break;
							}
						}
						
						if(matched >= REQUIRED_ALBUM_MATCHES) {
							singleAlbum = true;
						}
					}
				}
				// 2 => Use album tags
				else if("2".equals(replayGainType)) {
					singleAlbum = true;
				}
				// 3 => Use track tags
				// Already false, no need to do anything here
				
				
				// If playing a single album or no track gain, use album gain
				if((singleAlbum || rg[0] == 0) && rg[1] != 0) {
					adjust = rg[1];
				} else {
					// Otherwise, give priority to track gain
					adjust = rg[0];
				}
			
				if (adjust == 0) {
					/* No RG value found: decrease volume for untagged song if requested by user */
					int untagged = Integer.parseInt(prefs.getString(Constants.PREFERENCES_KEY_REPLAY_GAIN_UNTAGGED, "0"));
					adjust = (untagged - 150) / 10f;
				} else {
					int bump = Integer.parseInt(prefs.getString(Constants.PREFERENCES_KEY_REPLAY_GAIN_BUMP, "150"));
					adjust += (bump - 150) / 10f;
				}
			}
			
			float rg_result = ((float) Math.pow(10, (adjust / 20))) * volume;
			if (rg_result > 1.0f) {
				rg_result = 1.0f; /* android would IGNORE the change if this is > 1 and we would end up with the wrong volume */
			} else if (rg_result < 0.0f) {
				rg_result = 0.0f;
			}
			mediaPlayer.setVolume(rg_result, rg_result);
		} catch(IOException e) {
			Log.w(TAG, "Failed to apply replay gain values", e);
		}
	}

	public void toggleStarred() {
		final DownloadFile currentPlaying = this.currentPlaying;
		if(currentPlaying == null) {
			return;
		}

		UpdateHelper.toggleStarred(this, currentPlaying.getSong(), new UpdateHelper.OnStarChange() {
			@Override
			public void starChange(boolean starred) {
				if(currentPlaying == DownloadService.this.currentPlaying) {
					onMetadataUpdate(METADATA_UPDATED_STAR);
				}
			}

			@Override
			public void starCommited(boolean starred) {

			}
		});
	}
	public void toggleRating(int rating) {
		if(currentPlaying == null) {
			return;
		}

		MusicDirectory.Entry entry = currentPlaying.getSong();
		if(entry.getRating() == rating) {
			setRating(0);
		} else {
			setRating(rating);
		}
	}
	public void setRating(int rating) {
		final DownloadFile currentPlaying = this.currentPlaying;
		if(currentPlaying == null) {
			return;
		}
		MusicDirectory.Entry entry = currentPlaying.getSong();

		// Immediately skip to the next song if down thumbed
		if(rating == 1) {
			next(true);
		}

		UpdateHelper.setRating(this, entry, rating, new UpdateHelper.OnRatingChange() {
			@Override
			public void ratingChange(int rating) {
				if (currentPlaying == DownloadService.this.currentPlaying) {
					onMetadataUpdate(METADATA_UPDATED_RATING);
				}
			}
		});
	}
	public void acquireWakelock() {
		acquireWakelock(30000);
	}
	public void acquireWakelock(int ms) {
		wakeLock.acquire(ms);
	}

	public void addOnSongChangedListener(OnSongChangedListener listener) {
		addOnSongChangedListener(listener, false);
	}
	public void addOnSongChangedListener(OnSongChangedListener listener, boolean run) {
		synchronized(onSongChangedListeners) {
			int index = onSongChangedListeners.indexOf(listener);
			if (index == -1) {
				onSongChangedListeners.add(listener);
			}
		}

		if(run) {
			if(mediaPlayerHandler != null) {
				mediaPlayerHandler.post(new Runnable() {
					@Override
					public void run() {
						onSongsChanged();
						onSongProgress();
						onStateUpdate();
						onMetadataUpdate(METADATA_UPDATED_ALL);
					}
				});
			} else {
				runListenersOnInit = true;
			}
		}
	}
	public void removeOnSongChangeListener(OnSongChangedListener listener) {
		synchronized(onSongChangedListeners) {
			int index = onSongChangedListeners.indexOf(listener);
			if (index != -1) {
				onSongChangedListeners.remove(index);
			}
		}
	}

	private void onSongChanged() {
		final long atRevision = revision;
		synchronized(onSongChangedListeners) {
			for (final OnSongChangedListener listener : onSongChangedListeners) {
				handler.post(new Runnable() {
					@Override
					public void run() {
						if (revision == atRevision && instance != null) {
							listener.onSongChanged(currentPlaying, currentPlayingIndex);

							MusicDirectory.Entry entry = currentPlaying != null ? currentPlaying.getSong() : null;
							listener.onMetadataUpdate(entry, METADATA_UPDATED_ALL);
						}
					}
				});
			}

			if (mediaPlayerHandler != null && !onSongChangedListeners.isEmpty()) {
				mediaPlayerHandler.post(new Runnable() {
					@Override
					public void run() {
						onSongProgress();
					}
				});
			}
		}
	}
	private void onSongsChanged() {
		final long atRevision = revision;
		synchronized(onSongChangedListeners) {
			for (final OnSongChangedListener listener : onSongChangedListeners) {
				handler.post(new Runnable() {
					@Override
					public void run() {
						if (revision == atRevision && instance != null) {
							listener.onSongsChanged(downloadList, currentPlaying, currentPlayingIndex);
						}
					}
				});
			}
		}
	}

	private void onSongProgress() {
		onSongProgress(true);
	}
	private synchronized void onSongProgress(boolean manual) {
		final long atRevision = revision;
		final Integer duration = getPlayerDuration();
		final boolean isSeekable = isSeekable();
		final int position = getPlayerPosition();

		synchronized(onSongChangedListeners) {
			for (final OnSongChangedListener listener : onSongChangedListeners) {
				handler.post(new Runnable() {
					@Override
					public void run() {
						if (revision == atRevision && instance != null) {
							listener.onSongProgress(currentPlaying, position, duration, isSeekable);
						}
					}
				});
			}
		}

		if(manual) {
			handler.post(new Runnable() {
				@Override
				public void run() {
					mRemoteControl.setPlaybackState(playerState.getRemoteControlClientPlayState());
				}
			});
		}

		// Setup next playing at least a couple of seconds into the song since both Chromecast and some DLNA clients report PLAYING when still PREPARING
		if(position > 2000 && remoteController != null && remoteController.isNextSupported()) {
			if(playerState == STARTED && nextPlayerState == IDLE) {
				setNextPlaying();
			}
		}
	}
	private void onStateUpdate() {
		final long atRevision = revision;
		synchronized(onSongChangedListeners) {
			for (final OnSongChangedListener listener : onSongChangedListeners) {
				handler.post(new Runnable() {
					@Override
					public void run() {
						if (revision == atRevision && instance != null) {
							listener.onStateUpdate(currentPlaying, playerState);
						}
					}
				});
			}
		}
	}
	public void onMetadataUpdate() {
		onMetadataUpdate(METADATA_UPDATED_ALL);
	}
	public void onMetadataUpdate(final int updateType) {
		synchronized(onSongChangedListeners) {
			for (final OnSongChangedListener listener : onSongChangedListeners) {
				handler.post(new Runnable() {
					@Override
					public void run() {
						if (instance != null) {
							MusicDirectory.Entry entry = currentPlaying != null ? currentPlaying.getSong() : null;
							listener.onMetadataUpdate(entry, updateType);
						}
					}
				});
			}
		}

		handler.post(new Runnable() {
			@Override
			public void run() {
				if(currentPlaying != null) {
					mRemoteControl.metadataChanged(currentPlaying.getSong());
				}
			}
		});
	}

	private class BufferTask extends SilentBackgroundTask<Void> {
		private final DownloadFile downloadFile;
		private final int position;
		private final long expectedFileSize;
		private final File partialFile;
		private final boolean start;

		public BufferTask(DownloadFile downloadFile, int position, boolean start) {
			super(instance);
			this.downloadFile = downloadFile;
			this.position = position;
			partialFile = downloadFile.getPartialFile();
			this.start = start;

			// Calculate roughly how many bytes BUFFER_LENGTH_SECONDS corresponds to.
			int bitRate = downloadFile.getBitRate();
			long byteCount = Math.max(100000, bitRate * 1024L / 8L * 5L);

			// Find out how large the file should grow before resuming playback.
			Log.i(TAG, "Buffering from position " + position + " and bitrate " + bitRate);
			expectedFileSize = (position * bitRate / 8) + byteCount;
		}

		@Override
		public Void doInBackground() throws InterruptedException {
			setPlayerState(DOWNLOADING);

			while (!bufferComplete()) {
				Thread.sleep(1000L);
				if (isCancelled() || downloadFile.isFailedMax()) {
					return null;
				} else if(!downloadFile.isFailedMax() && !downloadFile.isDownloading()) {
					checkDownloads();
				}
			}
			doPlay(downloadFile, position, start);

			return null;
		}

		private boolean bufferComplete() {
			boolean completeFileAvailable = downloadFile.isWorkDone();
			long size = partialFile.length();

			Log.i(TAG, "Buffering " + partialFile + " (" + size + "/" + expectedFileSize + ", " + completeFileAvailable + ")");
			return completeFileAvailable || size >= expectedFileSize;
		}

		@Override
		public String toString() {
			return "BufferTask (" + downloadFile + ")";
		}
	}

	private class CheckCompletionTask extends SilentBackgroundTask<Void> {
		private final DownloadFile downloadFile;
		private final File partialFile;

		public CheckCompletionTask(DownloadFile downloadFile) {
			super(instance);
			this.downloadFile = downloadFile;
			if(downloadFile != null) {
				partialFile = downloadFile.getPartialFile();
			} else {
				partialFile = null;
			}
		}

		@Override
		public Void doInBackground()  throws InterruptedException {
			if(downloadFile == null) {
				return null;
			}

			// Do an initial sleep so this prepare can't compete with main prepare
			Thread.sleep(5000L);
			while (!bufferComplete()) {
				Thread.sleep(5000L);
				if (isCancelled()) {
					return null;
				}
			}

			// Start the setup of the next media player
			mediaPlayerHandler.post(new Runnable() {
				public void run() {
					if(!CheckCompletionTask.this.isCancelled()) {
						setupNext(downloadFile);
					}
				}
			});
			return null;
		}

		private boolean bufferComplete() {
			boolean completeFileAvailable = downloadFile.isWorkDone();
			Log.i(TAG, "Buffering next " + partialFile + " (" + partialFile.length() + "): " + completeFileAvailable);
			return completeFileAvailable && (playerState == PlayerState.STARTED || playerState == PlayerState.PAUSED);
		}

		@Override
		public String toString() {
			return "CheckCompletionTask (" + downloadFile + ")";
		}
	}

	public interface OnSongChangedListener {
		void onSongChanged(DownloadFile currentPlaying, int currentPlayingIndex);
		void onSongsChanged(List<DownloadFile> songs, DownloadFile currentPlaying, int currentPlayingIndex);
		void onSongProgress(DownloadFile currentPlaying, int millisPlayed, Integer duration, boolean isSeekable);
		void onStateUpdate(DownloadFile downloadFile, PlayerState playerState);
		void onMetadataUpdate(MusicDirectory.Entry entry, int fieldChange);
	}
}
