package ai.oneflow.app.computeruse

import ai.oneflow.capabilities.computeruse.ShizukuBridgeStatus
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import rikka.shizuku.Shizuku

/**
 * Raw Binder UserService for read-only / scoped Shizuku queries without requiring x86-64 aidl.
 */
class OneflowShizukuUserService : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == TRANSACTION_QUERY_UID) {
            data.enforceInterface(DESCRIPTOR)
            reply?.writeNoException()
            reply?.writeInt(Process.myUid())
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    companion object {
        const val DESCRIPTOR = "ai.oneflow.app.computeruse.IOneflowShizukuService"
        const val TRANSACTION_QUERY_UID = IBinder.FIRST_CALL_TRANSACTION
    }
}

class ShizukuBridge(
    private val context: Context,
    private val onStatusChanged: (ShizukuBridgeStatus) -> Unit
) {
    private var boundBinder: IBinder? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, OneflowShizukuUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shizuku_ro")
            .debuggable(false)
            .version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            boundBinder = service
            val uid = queryRemoteUid(service)
            onStatusChanged(
                ShizukuBridgeStatus(
                    available = true,
                    permissionGranted = true,
                    userServiceBound = service != null,
                    uid = uid,
                    note = "Shizuku UserService bound (uid=$uid, read-only)"
                )
            )
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundBinder = null
            refreshStatus()
        }
    }

    fun refreshStatus(): ShizukuBridgeStatus {
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAlive) {
            val st = ShizukuBridgeStatus(
                available = false,
                permissionGranted = false,
                userServiceBound = false,
                note = "Shizuku service not running"
            )
            onStatusChanged(st)
            return st
        }
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val uid = boundBinder?.let { queryRemoteUid(it) }
        val st = ShizukuBridgeStatus(
            available = true,
            permissionGranted = granted,
            userServiceBound = boundBinder != null,
            uid = uid,
            note = if (granted) "Shizuku ready (bound=${boundBinder != null})" else "Shizuku running; permission not granted"
        )
        onStatusChanged(st)
        return st
    }

    fun requestPermissionOrBind(requestCode: Int = 601) {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) {
            refreshStatus()
            return
        }
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!granted) {
            runCatching { Shizuku.requestPermission(requestCode) }
            refreshStatus()
            return
        }
        runCatching { Shizuku.bindUserService(userServiceArgs, connection) }
    }

    fun unbind() {
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        boundBinder = null
        refreshStatus()
    }

    private fun queryRemoteUid(binder: IBinder?): Int? {
        if (binder == null) return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(OneflowShizukuUserService.DESCRIPTOR)
            binder.transact(OneflowShizukuUserService.TRANSACTION_QUERY_UID, data, reply, 0)
            reply.readException()
            reply.readInt()
        } catch (_: Throwable) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
