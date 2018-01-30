/*
 * Copyright 2018 JUUL Labs, Inc.
 */

@file:Suppress("RedundantUnitReturnType")

package com.juul.able.experimental

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.os.RemoteException
import com.github.ajalt.timberkt.Timber
import com.juul.able.experimental.messenger.Message.CharacteristicNotification
import com.juul.able.experimental.messenger.Message.DiscoverServices
import com.juul.able.experimental.messenger.Message.ReadCharacteristic
import com.juul.able.experimental.messenger.Message.RequestMtu
import com.juul.able.experimental.messenger.Message.WriteCharacteristic
import com.juul.able.experimental.messenger.Message.WriteDescriptor
import com.juul.able.experimental.messenger.Messenger
import com.juul.able.experimental.messenger.OnCharacteristicChanged
import com.juul.able.experimental.messenger.OnCharacteristicRead
import com.juul.able.experimental.messenger.OnCharacteristicWrite
import com.juul.able.experimental.messenger.OnConnectionStateChange
import com.juul.able.experimental.messenger.OnDescriptorWrite
import com.juul.able.experimental.messenger.OnMtuChanged
import com.juul.able.experimental.messenger.OnServicesDiscovered
import kotlinx.coroutines.experimental.CompletableDeferred
import kotlinx.coroutines.experimental.channels.BroadcastChannel
import java.util.UUID

class CoroutinesGatt(
    private val bluetoothGatt: BluetoothGatt,
    private val messenger: Messenger
) : Gatt {

    private val connectionStateMonitor by lazy { ConnectionStateMonitor(this) }

    override val onConnectionStateChange: BroadcastChannel<OnConnectionStateChange>
        get() = messenger.callback.onConnectionStateChange

    override val onCharacteristicChanged: BroadcastChannel<OnCharacteristicChanged>
        get() = messenger.callback.onCharacteristicChanged

    override fun requestConnect(): Boolean = bluetoothGatt.connect()
    override fun requestDisconnect(): Unit = bluetoothGatt.disconnect()

    override suspend fun connect(): Boolean {
        return if (requestConnect()) {
            connectionStateMonitor.suspendUntilConnectionState(STATE_CONNECTED)
        } else {
            Timber.e { "connect → BluetoothGatt.requestConnect() returned false " }
            false
        }
    }

    override suspend fun disconnect(): Unit {
        requestDisconnect()
        connectionStateMonitor.suspendUntilConnectionState(STATE_DISCONNECTED)
    }

    override fun close() {
        Timber.v { "close → Begin" }
        connectionStateMonitor.close()
        messenger.close()
        bluetoothGatt.close()
        Timber.v { "close → End" }
    }

    override val services: List<BluetoothGattService> get() = bluetoothGatt.services
    override fun getService(uuid: UUID): BluetoothGattService? = bluetoothGatt.getService(uuid)

    /**
     * @throws [RemoteException] if underlying [BluetoothGatt.discoverServices] returns `false`.
     */
    override suspend fun discoverServices(): OnServicesDiscovered {
        Timber.d { "discoverServices → send(DiscoverServices)" }

        val response = CompletableDeferred<Boolean>()
        messenger.send(DiscoverServices(response))

        val call = "BluetoothGatt.discoverServices()"
        Timber.v { "discoverServices → Waiting for $call" }
        if (!response.await()) {
            throw RemoteException("$call returned false.")
        }

        Timber.v { "discoverServices → Waiting for BluetoothGattCallback" }
        return messenger.callback.onServicesDiscovered.receive().also { (status) ->
            Timber.i { "discoverServices, status=${status.asGattStatusString()}" }
        }
    }

    /**
     * @throws [RemoteException] if underlying [BluetoothGatt.readCharacteristic] returns `false`.
     */
    override suspend fun readCharacteristic(
        characteristic: BluetoothGattCharacteristic
    ): OnCharacteristicRead {
        val uuid = characteristic.uuid
        Timber.d { "readCharacteristic → send(ReadCharacteristic[uuid=$uuid])" }

        val response = CompletableDeferred<Boolean>()
        messenger.send(ReadCharacteristic(characteristic, response))

        val call = "BluetoothGatt.readCharacteristic(BluetoothGattCharacteristic[uuid=$uuid])"
        Timber.v { "readCharacteristic → Waiting for $call" }
        if (!response.await()) {
            throw RemoteException("Failed to read characteristic with UUID $uuid.")
        }

        Timber.v { "readCharacteristic → Waiting for BluetoothGattCallback" }
        return messenger.callback.onCharacteristicRead.receive().also { (_, value, status) ->
            Timber.i {
                val bytesString = value.size.bytesString
                val statusString = status.asGattStatusString()
                "← readCharacteristic $uuid ($bytesString), status=$statusString"
            }
        }
    }

    /**
     * @param value applied to [characteristic] when characteristic is written.
     * @param writeType applied to [characteristic] when characteristic is written.
     * @throws [RemoteException] if underlying [BluetoothGatt.writeCharacteristic] returns `false`.
     */
    override suspend fun writeCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: WriteType
    ): OnCharacteristicWrite {
        val uuid = characteristic.uuid
        Timber.d { "writeCharacteristic → send(WriteCharacteristic[uuid=$uuid])" }

        val response = CompletableDeferred<Boolean>()
        messenger.send(WriteCharacteristic(characteristic, value, writeType, response))

        val call = "BluetoothGatt.writeCharacteristic(BluetoothGattCharacteristic[uuid=$uuid])"
        Timber.v { "writeCharacteristic → Waiting for $call" }
        if (!response.await()) {
            throw RemoteException("$call returned false.")
        }

        Timber.v { "writeCharacteristic → Waiting for BluetoothGattCallback" }
        return messenger.callback.onCharacteristicWrite.receive().also { (_, status) ->
            Timber.i {
                val bytesString = value.size.bytesString
                val typeString = writeType.asWriteTypeString()
                val statusString = status.asGattStatusString()
                "→ writeCharacteristic $uuid ($bytesString), type=$typeString, status=$statusString"
            }
        }
    }

    /**
     * @param value applied to [descriptor] when descriptor is written.
     * @throws [RemoteException] if underlying [BluetoothGatt.writeDescriptor] returns `false`.
     */
    override suspend fun writeDescriptor(
        descriptor: BluetoothGattDescriptor, value: ByteArray
    ): OnDescriptorWrite {
        val uuid = descriptor.uuid
        Timber.d { "writeDescriptor → send(WriteDescriptor[uuid=$uuid])" }

        val response = CompletableDeferred<Boolean>()
        messenger.send(WriteDescriptor(descriptor, value, response))

        val call = "BluetoothGatt.writeDescriptor(BluetoothGattDescriptor[uuid=$uuid])"
        Timber.v { "writeDescriptor → Waiting for $call" }
        if (!response.await()) {
            throw RemoteException("$call returned false.")
        }

        Timber.v { "writeDescriptor → Waiting for BluetoothGattCallback" }
        return messenger.callback.onDescriptorWrite.receive().also { (_, status) ->
            Timber.i {
                val bytesString = value.size.bytesString
                val statusString = status.asGattStatusString()
                "→ writeDescriptor $uuid ($bytesString), status=$statusString"
            }
        }
    }

    /**
     * @throws [RemoteException] if underlying [BluetoothGatt.requestMtu] returns `false`.
     */
    override suspend fun requestMtu(mtu: Int): OnMtuChanged {
        Timber.d { "requestMtu → send(RequestMtu[mtu=$mtu])" }

        val response = CompletableDeferred<Boolean>()
        messenger.send(RequestMtu(mtu, response))

        val call = "BluetoothGatt.requestMtu($mtu)"
        Timber.v { "requestMtu → Waiting for $call" }
        if (!response.await()) {
            throw RemoteException("$call returned false.")
        }

        Timber.v { "requestMtu → Waiting for BluetoothGattCallback" }
        return messenger.callback.onMtuChanged.receive().also { (mtu, status) ->
            Timber.i { "requestMtu $mtu, status=${status.asGattStatusString()}" }
        }
    }

    /**
     * @throws [RemoteException] if underlying [BluetoothGatt.setCharacteristicNotification] returns `false`.
     */
    override suspend fun setCharacteristicNotification(
        characteristic: BluetoothGattCharacteristic,
        enable: Boolean
    ): Boolean {
        val response = CompletableDeferred<Boolean>()
        messenger.send(CharacteristicNotification(characteristic, enable, response))

        val uuid = characteristic.uuid
        val call = "BluetoothGatt.setCharacteristicNotification(" +
            "BluetoothGattCharacteristic[uuid=$uuid], enabled=$enable)"
        Timber.v { "requestMtu → Waiting for $call" }
        if (!response.await()) {
            throw RemoteException("$call returned false.")
        }

        Timber.i { "setCharacteristicNotification $uuid enable=$enable" }
        return true
    }
}

private val Int.bytesString get() = if (this == 1) "$this byte" else "$this bytes"
