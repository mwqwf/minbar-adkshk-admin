package com.ali.ishaqiyin_admin.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 📶 مراقب الاتصال — مصدر واحد لحالة الشبكة تستعمله الواجهة (بانر «دون
 * اتصال») ومخزن الوسائط (استئناف التنزيلات المعلَّقة فور عودة الشبكة).
 *
 * ملاحظة مهمّة للشبكات الضعيفة: نعتبر الاتصال قائماً بمجرّد وجود شبكة
 * `NET_CAPABILITY_INTERNET` حتى لو لم تُثبَّت `VALIDATED`، لأن الشبكات
 * الضعيفة كثيراً ما تفشل في تحقّق النظام بينما تنقل البيانات فعلاً.
 */
object NetworkMonitor {
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online

    private var started = false
    private val activeNetworks = mutableSetOf<Network>()

    fun start(context: Context) {
        if (started) return
        started = true
        val manager = context.applicationContext
            .getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            manager.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        synchronized(activeNetworks) { activeNetworks.add(network) }
                        _online.value = true
                    }

                    override fun onLost(network: Network) {
                        val empty = synchronized(activeNetworks) {
                            activeNetworks.remove(network)
                            activeNetworks.isEmpty()
                        }
                        if (empty) _online.value = false
                    }
                },
            )
        }
        // الحالة الابتدائية قبل وصول أوّل نداء.
        _online.value = runCatching {
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(true)
    }
}
