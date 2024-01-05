package org.listenbrainz.android.repository.scrobblemanager

import android.app.Notification
import android.content.Context
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.listenbrainz.android.model.PlayingTrack
import org.listenbrainz.android.model.PlayingTrack.Companion.toPlayingTrack
import org.listenbrainz.android.repository.preferences.AppPreferences
import org.listenbrainz.android.util.ListenSubmissionState
import org.listenbrainz.android.util.ListenSubmissionState.Companion.extractTitle
import javax.inject.Inject

/** The sole responsibility of this layer is to maintain mutual exclusion between [onMetadataChanged] and
 * [onNotificationPosted] and filter out repetitive submissions.
 *
 * FUTURE: Call notification popups here as well.*/
class ScrobbleManagerImpl @Inject constructor(
    workManager: WorkManager,
    private val appPreferences: AppPreferences,
    @ApplicationContext private val context: Context
): ScrobbleManager {
    
    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val listenSubmissionState = ListenSubmissionState(workManager, context)
    private val scope = MainScope()
    
    /** Used to avoid repetitive submissions.*/
    private var lastCallbackTs = System.currentTimeMillis()
    
    /** Used to avoid repetitive submissions.*/
    private var lastNotificationPostTs = System.currentTimeMillis()
    private lateinit var blackList: List<String>
    
    init {
        scope.launch(Dispatchers.Default) {
            appPreferences.getListeningBlacklistFlow().collect {
                blackList = it
            }
        }
    }
    
    override fun onMetadataChanged(metadata: MediaMetadata?, player: String) {
        handler.post {
            if (metadata == null) return@post
    
            val newTimestamp = System.currentTimeMillis()
            with(listenSubmissionState) {
        
                // Repetitive submissions blocker
                if (playingTrack.isCallbackTrack() &&
                    newTimestamp in lastCallbackTs..lastCallbackTs + CALLBACK_SUBMISSION_TIMEOUT_INTERVAL
                    && metadata.extractTitle() == playingTrack.title
                ) return@post
        
                lastCallbackTs = newTimestamp
        
                val newTrack =
                    metadata.toPlayingTrack(player).apply { timestamp = newTimestamp }
                
                onControllerCallback(newTrack)
            }
            // Log.e("META")
        }
    }
    
    override fun onPlaybackStateChanged(state: PlaybackState?) {
        // No need of this right now.
        /*scope.launch {
            timerMutex.withLock {
                listenSubmissionState.toggleTimer(state?.state)
            }
        }*/
    }
    
    /** NOTE FOR FUTURE USE: When onNotificationPosted is called twice within 300..600ms delay, it usually
     * means the track has been changed.*/
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        handler.post {
            // Only CATEGORY_TRANSPORT contain media player metadata.
            if (sbn?.notification?.category != Notification.CATEGORY_TRANSPORT) return@post
    
            val newTrack = PlayingTrack(
                title = sbn.notification.extras.getString(Notification.EXTRA_TITLE)
                    ?: return@post,
                artist = sbn.notification.extras.getString(Notification.EXTRA_TEXT)
                    ?: return@post,
                pkgName = sbn.packageName,
                timestamp = sbn.notification.`when`
            )
    
            // Avoid repetitive submissions
            with(listenSubmissionState) {
                if (newTrack.timestamp in lastNotificationPostTs..lastNotificationPostTs + NOTI_SUBMISSION_TIMEOUT_INTERVAL
                    && newTrack.pkgName == playingTrack.pkgName
                    && newTrack.title == playingTrack.title
                ) return@post
    
                // Check for blacklisted apps
                if (sbn.packageName in blackList) return@post
    
                lastNotificationPostTs = newTrack.timestamp
    
                // Alert submission state
                alertMediaNotificationUpdate(newTrack)
            }
            // Log.e("NOTI")
        }
    }
    
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        scope.launch {
            if (sbn?.notification?.category == Notification.CATEGORY_TRANSPORT
                && sbn.packageName !in appPreferences.getListeningBlacklist()
            ) {
                listenSubmissionState.alertMediaPlayerRemoved(sbn)
            }
        }
    }
    
    companion object {
        /** Because some notification posts are repetitive and in close proximity to each other, these variables
         * are used to mitigate those cases.*/
        const val NOTI_SUBMISSION_TIMEOUT_INTERVAL = 300
    
        /** Because some callbacks are repetitive and in close proximity to each other, these variables
         * are used to mitigate those cases.*/
        const val CALLBACK_SUBMISSION_TIMEOUT_INTERVAL = 500
    }
}