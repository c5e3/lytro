package app.refocus.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Watches WiFi networks and finds the camera's hotspot: the WiFi network whose
 * gateway (or DHCP server) is the camera's address. Sockets to the camera are
 * bound to that network, so they work even though the hotspot has no internet
 * and Android keeps mobile data as the default route.
 */
class CameraNetwork(context: Context, private val hostProvider: () -> String) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = ConcurrentHashMap<Network, LinkProperties>()

    /** The WiFi network that leads to the camera, if the phone is on its hotspot. */
    private val _cameraNetwork = MutableStateFlow<Network?>(null)
    val cameraNetwork: StateFlow<Network?> = _cameraNetwork.asStateFlow()

    private val initialised = MutableStateFlow(false)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            wifi[network] = linkProperties
            reevaluate()
        }

        override fun onLost(network: Network) {
            wifi.remove(network)
            reevaluate()
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
            .build()
        cm.registerNetworkCallback(request, callback)
    }

    fun reevaluate() {
        val host = hostProvider()
        _cameraNetwork.value = wifi.entries.firstOrNull { (_, lp) -> leadsTo(lp, host) }?.key
        initialised.value = true
    }

    private fun leadsTo(lp: LinkProperties, host: String): Boolean {
        val gateway = lp.routes.any { it.isDefaultRoute && it.gateway?.hostAddress == host }
        val dhcp = lp.dhcpServerAddress?.hostAddress == host
        return gateway || dhcp
    }

    /** Waits briefly for the first network callbacks, then tells whether the gateway is the camera. */
    suspend fun isOnCameraWifiAtStartup(): Boolean {
        withTimeoutOrNull(1500) { initialised.first { it } }
        // the initial callbacks for all current networks arrive in a burst
        kotlinx.coroutines.delay(150)
        reevaluate()
        return _cameraNetwork.value != null
    }

    /**
     * Opens a socket to the camera, bound to the camera's WiFi network when known.
     * Without a matching network (e.g. a custom address in settings) the default
     * route is used.
     */
    fun openSocket(host: String, port: Int): Socket {
        val network = _cameraNetwork.value
        val socket = network?.socketFactory?.createSocket() ?: Socket()
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 20_000
            socket.receiveBufferSize = 256 * 1024
            socket.connect(InetSocketAddress(host, port), 6_000)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
        return socket
    }
}
