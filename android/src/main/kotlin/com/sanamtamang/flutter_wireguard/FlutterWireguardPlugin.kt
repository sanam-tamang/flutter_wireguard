package com.sanamtamang.flutter_wireguard

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

import com.beust.klaxon.Klaxon
import com.wireguard.android.backend.*
import com.wireguard.config.Config
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry

import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.util.*


const val PERMISSIONS_REQUEST_CODE = 10014
const val METHOD_CHANNEL_NAME = "sanamtamang.flutter_wireguard/wgcontrol"
const val METHOD_EVENT_NAME = "sanamtamang.flutter_wireguard/wgstage"

class FlutterWireguardPlugin : FlutterPlugin, MethodCallHandler, ActivityAware,
    PluginRegistry.ActivityResultListener {

    private lateinit var channel: MethodChannel
    private lateinit var events: EventChannel
    private lateinit var tunnelName: String
    private val futureBackend = CompletableDeferred<Backend>()
    private var vpnStageSink: EventChannel.EventSink? = null
    private val scope = CoroutineScope(Job() + Dispatchers.Main.immediate)
    private var backend: Backend? = null
    private var havePermission = false
    private lateinit var context: Context
    private var activity: Activity? = null
    private var config: Config? = null
    private var tunnel: WireGuardTunnel? = null
    private val TAG = "NVPN"
    var isVpnChecked = false
    private var notificationTitle: String? = null
    private var notificationBody: String? = null

    // ✅ BroadcastReceiver to handle "Stop" from notification
    private lateinit var stopVpnReceiver: BroadcastReceiver

    companion object {
        private var state: String = "no_connection"
        private var pluginInstance: FlutterWireguardPlugin? = null

        fun getStatus(): String {
            return state
        }

        fun getInstance(): FlutterWireguardPlugin? {
            return pluginInstance
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        this.havePermission =
            (requestCode == PERMISSIONS_REQUEST_CODE) && (resultCode == Activity.RESULT_OK)
        return havePermission
    }

    override fun onAttachedToActivity(activityPluginBinding: ActivityPluginBinding) {
        this.activity = activityPluginBinding.activity as FlutterActivity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        this.activity = null
    }

    override fun onReattachedToActivityForConfigChanges(activityPluginBinding: ActivityPluginBinding) {
        this.activity = activityPluginBinding.activity as FlutterActivity
    }

    override fun onDetachedFromActivity() {
        this.activity = null
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, METHOD_CHANNEL_NAME)
        events = EventChannel(flutterPluginBinding.binaryMessenger, METHOD_EVENT_NAME)
        context = flutterPluginBinding.applicationContext
        pluginInstance = this

        // ✅ Register broadcast receiver for "Stop VPN" from notification
        stopVpnReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.i(TAG, "Received STOP_VPN_REQUEST broadcast from notification")
                scope.launch(Dispatchers.IO) {
                    disconnect(object : Result {
                        override fun success(result: Any?) {
                            Log.i(TAG, "VPN disconnected successfully from notification")
                        }
                        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                            Log.e(TAG, "Failed to disconnect VPN from notification: $errorCode - $errorMessage")
                        }
                        override fun notImplemented() {
                            Log.w(TAG, "Disconnect not implemented")
                        }
                    })
                }
            }
        }
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(stopVpnReceiver, IntentFilter("com.sanamtamang.flutter_wireguard.STOP_VPN_REQUEST"))

        scope.launch(Dispatchers.IO) {
            try {
                backend = createBackend()
                futureBackend.complete(backend!!)
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
            }
        }

        channel.setMethodCallHandler(this)
        events.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                isVpnChecked = false
                vpnStageSink = events
            }

            override fun onCancel(arguments: Any?) {
                isVpnChecked = false
                vpnStageSink = null
            }
        })
    }

    private fun createBackend(): Backend {
        if (backend == null) {
            backend = GoBackend(context)
        }
        return backend as Backend
    }

    private fun flutterSuccess(result: Result, o: Any) {
        scope.launch(Dispatchers.Main) {
            result.success(o)
        }
    }

    private fun flutterError(result: Result, error: String) {
        scope.launch(Dispatchers.Main) {
            result.error(error, null, null)
        }
    }

    private fun flutterNotImplemented(result: Result) {
        scope.launch(Dispatchers.Main) {
            result.notImplemented()
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "initialize" -> setupTunnel(call.argument<String>("localizedDescription").toString(), result)
            "start" -> {
                // Extract custom notification values
                notificationTitle = call.argument<String>("notificationTitle")
                notificationBody = call.argument<String>("notificationBody")

                connect(call.argument<String>("wgQuickConfig").toString(), result)
                if (!isVpnChecked) {
                    if (isVpnActive()) {
                        state = "connected"
                        isVpnChecked = true
                        println("VPN is active")
                    } else {
                        state = "disconnected"
                        isVpnChecked = true
                        println("VPN is not active")
                    }
                }
            }
            "stop" -> {
                disconnect(result)
            }
            "stage" -> {
                result.success(getStatus())
            }
            "checkPermission" -> {
                checkPermission()
                result.success(null)
            }
            "getDownloadData" -> {
                getDownloadData(result)
            }
            "getUploadData" -> {
                getUploadData(result)
            }
            else -> flutterNotImplemented(result)
        }
    }

    private fun isVpnActive(): Boolean {
        try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork = connectivityManager.activeNetwork
                val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                return networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            } else {
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "isVpnActive - ERROR - ${e.message}")
            return false
        }
    }

    private fun updateStage(stage: String?) {
        scope.launch(Dispatchers.Main) {
            val updatedStage = stage ?: "no_connection"
            state = updatedStage
            vpnStageSink?.success(updatedStage.lowercase(Locale.ROOT))
        }
    }

    private fun updateStageFromState(state: Tunnel.State) {
        scope.launch(Dispatchers.Main) {
            when (state) {
                Tunnel.State.UP -> updateStage("connected")
                Tunnel.State.DOWN -> updateStage("disconnected")
                else -> updateStage("wait_connection")
            }
        }
    }

    private fun disconnect(result: Result) {
        scope.launch(Dispatchers.IO) {
            try {
                if (futureBackend.await().runningTunnelNames.isEmpty()) {
                    updateStage("disconnected")
                    throw Exception("Tunnel is not running")
                }
                updateStage("disconnecting")
                futureBackend.await().setState(
                    tunnel(tunnelName) { state ->
                        scope.launch(Dispatchers.Main) {
                            Log.i(TAG, "onStateChange - $state")
                            updateStageFromState(state)
                        }
                    }, Tunnel.State.DOWN, config
                )
                // ✅ Stop the foreground service after tunnel is down
                val serviceIntent = Intent(context, VpnKeepAliveService::class.java)
                context.stopService(serviceIntent)

                Log.i(TAG, "Disconnect - success!")
                flutterSuccess(result, "")
            } catch (e: BackendException) {
                Log.e(TAG, "Disconnect - BackendException - ERROR - ${e.reason}", e)
                flutterError(result, e.reason.toString())
            } catch (e: Throwable) {
                Log.e(TAG, "Disconnect - Can't disconnect from tunnel: ${e.message}")
                flutterError(result, e.message.toString())
            }
        }
    }

    private fun connect(wgQuickConfig: String, result: Result) {
        scope.launch(Dispatchers.IO) {
            try {
                if (!havePermission) {
                    checkPermission()
                    // Wait for permission result with timeout
                    var permissionWaitTime = 0
                    while (!havePermission && permissionWaitTime < 30000) { // 30 second timeout
                        delay(100)
                        permissionWaitTime += 100
                    }
                    if (!havePermission) {
                        throw Exception("Permissions are not given")
                    }
                }
                updateStage("prepare")
                val inputStream = ByteArrayInputStream(wgQuickConfig.toByteArray())
                config = Config.parse(inputStream)
                updateStage("connecting")
                futureBackend.await().setState(
                    tunnel(tunnelName) { state ->
                        scope.launch(Dispatchers.Main) {
                            Log.i(TAG, "onStateChange - $state")
                            updateStageFromState(state)
                        }
                    }, Tunnel.State.UP, config
                )

                // ✅ Start foreground service to keep alive
                val serviceIntent = Intent(context, VpnKeepAliveService::class.java)
                // Pass custom notification values to the service
                if (notificationTitle != null) {
                    serviceIntent.putExtra(VpnKeepAliveService.EXTRA_NOTIFICATION_TITLE, notificationTitle)
                }
                if (notificationBody != null) {
                    serviceIntent.putExtra(VpnKeepAliveService.EXTRA_NOTIFICATION_BODY, notificationBody)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                Log.i(TAG, "Connect - success!")
                flutterSuccess(result, "")
            } catch (e: BackendException) {
                Log.e(TAG, "Connect - BackendException - ERROR - ${e.reason}", e)
                flutterError(result, e.reason.toString())
            } catch (e: Throwable) {
                Log.e(TAG, "Connect - Can't connect to tunnel: $e", e)
                flutterError(result, e.message.toString())
            }
        }
    }

    private fun setupTunnel(localizedDescription: String, result: Result) {
        scope.launch(Dispatchers.IO) {
            if (Tunnel.isNameInvalid(localizedDescription)) {
                flutterError(result, "Invalid Name")
                return@launch
            }
            tunnelName = localizedDescription
            checkPermission()
            result.success(null)
        }
    }

    private fun checkPermission() {
        val intent = GoBackend.VpnService.prepare(this.activity)
        if (intent != null) {
            havePermission = false
            this.activity?.startActivityForResult(intent, PERMISSIONS_REQUEST_CODE)
        } else {
            havePermission = true
        }
    }

    private fun getDownloadData(result: Result) {
        scope.launch(Dispatchers.IO) {
            try {
                val downloadData = futureBackend.await().getStatistics(tunnel(tunnelName)).totalRx()
                flutterSuccess(result, downloadData)
            } catch (e: Throwable) {
                Log.e(TAG, "getDownloadData - ERROR - ${e.message}")
                flutterError(result, e.message.toString())
            }
        }
    }

    private fun getUploadData(result: Result) {
        scope.launch(Dispatchers.IO) {
            try {
                val uploadData = futureBackend.await().getStatistics(tunnel(tunnelName)).totalTx()
                flutterSuccess(result, uploadData)
            } catch (e: Throwable) {
                Log.e(TAG, "getUploadData - ERROR - ${e.message}")
                flutterError(result, e.message.toString())
            }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        events.setStreamHandler(null)
        isVpnChecked = false

        // ✅ Unregister broadcast receiver
        try {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(stopVpnReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered
        }
    }

    private fun tunnel(name: String, callback: StateChangeCallback? = null): WireGuardTunnel {
        if (tunnel == null) {
            tunnel = WireGuardTunnel(name, callback)
        }
        return tunnel as WireGuardTunnel
    }

    // ✅ Public method to disconnect VPN from service (when app is closed)
    fun disconnectVpnFromService() {
        Log.i(TAG, "Disconnecting VPN from service (app closed)")
        scope.launch(Dispatchers.IO) {
            try {
                if (futureBackend.await().runningTunnelNames.isEmpty()) {
                    Log.i(TAG, "No running tunnels to disconnect")
                    return@launch
                }
                futureBackend.await().setState(
                    tunnel(tunnelName) { state ->
                        scope.launch(Dispatchers.Main) {
                            Log.i(TAG, "onStateChange - $state")
                            updateStageFromState(state)
                        }
                    }, Tunnel.State.DOWN, config
                )
                Log.i(TAG, "VPN disconnected from service successfully")
            } catch (e: Throwable) {
                Log.e(TAG, "Error disconnecting VPN from service: ${e.message}", e)
            }
        }
    }
}

typealias StateChangeCallback = (Tunnel.State) -> Unit

class WireGuardTunnel(
    private val name: String, private val onStateChanged: StateChangeCallback? = null
) : Tunnel {

    override fun getName() = name

    override fun onStateChange(newState: Tunnel.State) {
        onStateChanged?.invoke(newState)
    }
} 