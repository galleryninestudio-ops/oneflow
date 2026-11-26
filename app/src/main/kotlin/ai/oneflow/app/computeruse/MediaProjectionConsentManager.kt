package ai.oneflow.app.computeruse

import ai.oneflow.capabilities.computeruse.FlowAccessRouter
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager

class MediaProjectionConsentManager(
    private val context: Context,
    private val router: FlowAccessRouter
) {
    private var activeProjection: MediaProjection? = null

    fun createConsentIntent(): Intent {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mgr.createScreenCaptureIntent()
    }

    fun onConsentResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            router.markMediaProjectionDenied()
            return
        }
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = runCatching { mgr.getMediaProjection(resultCode, data) }.getOrNull()
        if (projection == null) {
            router.markMediaProjectionDenied()
            return
        }
        activeProjection?.stop()
        activeProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                activeProjection = null
                router.revokeMediaProjection("MediaProjection stopped by system/user")
            }
        }, null)
        router.updateMediaProjectionGranted()
    }

    fun stopAndRevoke() {
        activeProjection?.stop()
        activeProjection = null
        router.revokeMediaProjection("MediaProjection stopped by user")
    }
}
