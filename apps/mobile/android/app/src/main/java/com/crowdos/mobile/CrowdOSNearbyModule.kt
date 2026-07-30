package com.crowdos.mobile

import android.util.Base64
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.nearby.connection.AdvertisingOptions
import java.util.concurrent.ConcurrentHashMap

class CrowdOSNearbyModule(
  private val context: ReactApplicationContext,
) : ReactContextBaseJavaModule(context) {
  private val client = Nearby.getConnectionsClient(context)
  private val connectedEndpoints = ConcurrentHashMap.newKeySet<String>()

  override fun getName(): String = "CrowdOSNearby"

  @ReactMethod
  fun start(serviceId: String, promise: Promise) {
    val options = AdvertisingOptions.Builder()
      .setStrategy(Strategy.P2P_CLUSTER)
      .build()
    val discoveryOptions = DiscoveryOptions.Builder()
      .setStrategy(Strategy.P2P_CLUSTER)
      .build()

    client.startAdvertising("CrowdOS", serviceId, lifecycleCallback, options)
      .addOnFailureListener { promise.reject("ADVERTISE_FAILED", it) }
    client.startDiscovery(serviceId, endpointCallback, discoveryOptions)
      .addOnSuccessListener { promise.resolve(null) }
      .addOnFailureListener { promise.reject("DISCOVERY_FAILED", it) }
  }

  @ReactMethod
  fun stop(promise: Promise) {
    client.stopAdvertising()
    client.stopDiscovery()
    client.stopAllEndpoints()
    connectedEndpoints.clear()
    emit("nearbyStopped", null)
    promise.resolve(null)
  }

  @ReactMethod
  fun sendMessage(base64Payload: String, promise: Promise) {
    val payload = Payload.fromBytes(Base64.decode(base64Payload, Base64.NO_WRAP))
    val endpoints = connectedEndpoints.toList()
    if (endpoints.isEmpty()) {
      promise.reject("NO_PEERS", "No nearby CrowdOS peers are connected")
      return
    }
    client.sendPayload(endpoints, payload)
      .addOnSuccessListener { promise.resolve(null) }
      .addOnFailureListener { promise.reject("SEND_FAILED", it) }
  }

  private val lifecycleCallback = object : ConnectionLifecycleCallback() {
    override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
      // Transport connectivity is established first; signed event handshakes gate payloads later.
      client.acceptConnection(endpointId, payloadCallback)
        .addOnFailureListener { emitError("ACCEPT_FAILED", it) }
      emitPeer("peerConnecting", endpointId, info.endpointName)
    }

    override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
      if (result.status.isSuccess) {
        connectedEndpoints.add(endpointId)
        emitPeer("peerConnected", endpointId, endpointId)
      } else {
        emitPeer("peerRejected", endpointId, result.status.statusMessage ?: "connection rejected")
      }
    }

    override fun onDisconnected(endpointId: String) {
      connectedEndpoints.remove(endpointId)
      emitPeer("peerDisconnected", endpointId, endpointId)
    }
  }

  private val endpointCallback = object : EndpointDiscoveryCallback() {
    override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
      emitPeer("peerFound", endpointId, info.endpointName)
      client.requestConnection("CrowdOS", endpointId, lifecycleCallback)
        .addOnFailureListener { emitError("REQUEST_FAILED", it) }
    }

    override fun onEndpointLost(endpointId: String) {
      emitPeer("peerLost", endpointId, endpointId)
    }
  }

  private val payloadCallback = object : PayloadCallback() {
    override fun onPayloadReceived(endpointId: String, payload: Payload) {
      payload.asBytes()?.let {
        val map = Arguments.createMap()
        map.putString("endpointId", endpointId)
        map.putString("data", Base64.encodeToString(it, Base64.NO_WRAP))
        emit("nearbyMessage", map)
      }
    }

    override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
  }

  private fun emitPeer(event: String, endpointId: String, name: String) {
    val map = Arguments.createMap()
    map.putString("endpointId", endpointId)
    map.putString("name", name)
    emit(event, map)
  }

  private fun emitError(code: String, error: Exception) {
    val map = Arguments.createMap()
    map.putString("code", code)
    map.putString("message", error.message ?: code)
    emit("nearbyError", map)
  }

  private fun emit(event: String, payload: com.facebook.react.bridge.WritableMap?) {
    context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
      .emit(event, payload)
  }
}
