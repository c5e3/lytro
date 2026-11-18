package app.refocus.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.refocus.core.camera.LytroClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.dataStore by preferencesDataStore("settings")

data class CameraAddress(val host: String = LytroClient.DEFAULT_HOST, val port: Int = LytroClient.DEFAULT_PORT) {
    val isDefault get() = host == LytroClient.DEFAULT_HOST && port == LytroClient.DEFAULT_PORT
}

class Settings(private val context: Context, scope: CoroutineScope) {
    private val hostKey = stringPreferencesKey("camera_host")
    private val portKey = intPreferencesKey("camera_port")

    private val flow = context.dataStore.data.map {
        CameraAddress(it[hostKey] ?: LytroClient.DEFAULT_HOST, it[portKey] ?: LytroClient.DEFAULT_PORT)
    }

    val address: StateFlow<CameraAddress> = flow.stateIn(scope, SharingStarted.Eagerly, CameraAddress())

    suspend fun current(): CameraAddress = flow.first()

    suspend fun setAddress(address: CameraAddress) {
        context.dataStore.edit {
            it[hostKey] = address.host.trim()
            it[portKey] = address.port
        }
    }
}
