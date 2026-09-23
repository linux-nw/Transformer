package app.transformer.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "NsdCoordinator"
private const val SERVICE_TYPE = "_transformer._tcp."
private const val NAME_PREFIX = "transformer-"

/**
 * This is what makes "automatically reconnect while on the same network"
 * real instead of a QR-scan-every-time chore: every already-paired device
 * advertises one mDNS service per pairing id it knows, named
 * "transformer-<pairingId>". Any other Transformer install on the same LAN
 * that also knows that pairing id (because it's the other half of that same
 * pairing) recognizes the name, resolves the current host:port — which
 * survives DHCP handing out a new IP — and can dial back in with the key it
 * already has, no QR code involved.
 */
class NsdCoordinator(context: Context) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val registrations = ConcurrentHashMap<String, NsdManager.RegistrationListener>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun advertise(peerId: String, port: Int) {
        if (registrations.containsKey(peerId)) return
        val info = NsdServiceInfo().apply {
            serviceName = NAME_PREFIX + peerId
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "advertising ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "advertise failed for $peerId: $errorCode")
                registrations.remove(peerId)
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registrations[peerId] = listener
        runCatching { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { registrations.remove(peerId) }
    }

    fun stopAdvertising(peerId: String) {
        registrations.remove(peerId)?.let { runCatching { nsdManager.unregisterService(it) } }
    }

    fun stopAllAdvertising() {
        registrations.keys.toList().forEach(::stopAdvertising)
    }

    /** [onFound] is called (possibly repeatedly) with a resolved peer id + address. */
    fun startDiscovery(onFound: (peerId: String, host: String, port: Int) -> Unit) {
        if (discoveryListener != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceName.startsWith(NAME_PREFIX)) return
                nsdManager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "resolve failed for ${serviceInfo.serviceName}: $errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val peerId = serviceInfo.serviceName.removePrefix(NAME_PREFIX)
                            val host = serviceInfo.host?.hostAddress ?: return
                            onFound(peerId, host, serviceInfo.port)
                        }
                    },
                )
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "start discovery failed: $errorCode")
                runCatching { nsdManager.stopServiceDiscovery(this) }
                discoveryListener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        runCatching { nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun stopDiscovery() {
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        discoveryListener = null
    }
}
