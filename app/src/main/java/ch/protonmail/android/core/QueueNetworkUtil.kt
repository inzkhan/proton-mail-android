/*
 * Copyright (c) 2020 Proton Technologies AG
 *
 * This file is part of ProtonMail.
 *
 * ProtonMail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonMail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonMail. If not, see https://www.gnu.org/licenses/.
 */
package ch.protonmail.android.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import ch.protonmail.android.api.NetworkConfigurator
import com.birbit.android.jobqueue.network.NetworkEventProvider
import com.birbit.android.jobqueue.network.NetworkUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QueueNetworkUtil @Inject constructor(
    private val context: Context,
    internal val networkConfigurator: NetworkConfigurator
) : NetworkUtil, NetworkEventProvider {

    private var listener: NetworkEventProvider.Listener? = null
    private var isInternetAccessible: Boolean = false

    /**
     * Flow that emits false when backend replies with an error, or true when
     * a correct reply is received.
     */
    val isBackendRespondingWithoutErrorFlow: StateFlow<Boolean>
        get() = backendExceptionFlow

    private val backendExceptionFlow = MutableStateFlow(true)

    @Synchronized
    private fun updateRealConnectivity(internetAccessible: Boolean) {
        isInternetAccessible = internetAccessible
        backendExceptionFlow.value = internetAccessible
    }

    init {
        updateRealConnectivity(true) // initially we assume there is connectivity
        context.applicationContext.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (listener == null) { // shall not be but just be safe
                        return
                    }
                    // so in this moment, our hardware connectivity has changed
                    if (hasConn(false)) {
                        // if we really have connectivity, then we are informing the queue to try to
                        // execute itself
                        listener?.onNetworkChange(getNetworkStatus(context))
                        ProtonMailApplication.getApplication().startJobManager()
                    }
                }
            },
            IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        )
    }

    fun isConnected(): Boolean = hasConn(false)

    fun setCurrentlyHasConnectivity(currentlyHasConnectivity: Boolean) =
        updateRealConnectivity(currentlyHasConnectivity)

    override fun setListener(netListener: NetworkEventProvider.Listener) {
        listener = netListener
    }

    private fun hasConn(checkReal: Boolean): Boolean {
        synchronized(isInternetAccessible) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val netInfo = cm.activeNetworkInfo
            var hasConnection = netInfo != null && netInfo.isConnectedOrConnecting
            val currentStatus = isInternetAccessible
            if (checkReal) {
                hasConnection = hasConnection && isInternetAccessible
            }
            if (checkReal && currentStatus != hasConnection) {
                Timber.d("Network statuses differs hasConnection $hasConnection currentStatus $currentStatus")
            } else if (checkReal) {
                if (hasConnection) {
                    networkConfigurator.startAutoRetry()
                } else {
                    networkConfigurator.stopAutoRetry()
                }
            }
            return hasConnection


        }
    }

    override fun getNetworkStatus(context: Context): Int =
        if (hasConn(true)) NetworkUtil.METERED else NetworkUtil.DISCONNECTED
}
