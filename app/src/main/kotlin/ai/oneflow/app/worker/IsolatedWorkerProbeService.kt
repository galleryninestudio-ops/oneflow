package ai.oneflow.app.worker

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.io.File

/**
 * Phase 0 Isolated Worker Boundary POC.
 * Declared with android:isolatedProcess="true" in AndroidManifest.xml.
 * Uses typed Binder/Parcel transactions (no x86_64 aidl compiler needed on ARM64 host).
 * Demonstrates:
 *  - Isolated process UID distinct from app UID
 *  - App-private filesDir cannot be accessed from isolatedProcess
 */
class IsolatedWorkerProbeService : Service() {

    companion object {
        const val DESCRIPTOR = "ai.oneflow.app.worker.IIsolatedProbe"
        const val TX_PING = IBinder.FIRST_CALL_TRANSACTION
        const val TX_PROBE_ISOLATION = IBinder.FIRST_CALL_TRANSACTION + 1
    }

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR)
                    true
                }
                TX_PING -> {
                    data.enforceInterface(DESCRIPTOR)
                    reply?.writeNoException()
                    reply?.writeString("pong:uid=${Process.myUid()}:pid=${Process.myPid()}")
                    true
                }
                TX_PROBE_ISOLATION -> {
                    data.enforceInterface(DESCRIPTOR)
                    val sentinelPath = data.readString().orEmpty()
                    val canReadSentinel = try {
                        if (sentinelPath.isBlank()) false else File(sentinelPath).canRead()
                    } catch (_: SecurityException) {
                        false
                    }
                    reply?.writeNoException()
                    reply?.writeInt(Process.myUid())
                    reply?.writeInt(if (canReadSentinel) 1 else 0)
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
