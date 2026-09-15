package com.jiangdg.bluetooth

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.util.Log
import java.util.UUID

class BleManager(private val context: Context){

    companion object{
        private const val TAG="HERLENS_BLE"

        const val DEVICE_NAME="HerLens-ESP32S3"

        val SERVICE_UUID=UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val RX_UUID=UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val TX_UUID=UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD_UUID=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt:BluetoothGatt?=null
    private var rx:BluetoothGattCharacteristic?=null

    var onConnectionChanged:((Boolean)->Unit)?=null
    var onStateReceived:((String)->Unit)?=null
    var onEventReceived:((String)->Unit)?=null

    fun startScan(){
        adapter?.bluetoothLeScanner?.startScan(scanCallback)
        Log.d(TAG,"Start BLE Scan")
    }

    fun stopScan(){
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback=object:ScanCallback(){

        override fun onScanResult(
            callbackType:Int,
            result:ScanResult
        ){
            val device=result.device

            Log.d(TAG,"FOUND ${device.name}")

            if(device.name==DEVICE_NAME){

                stopScan()
                connect(device)

            }
        }
    }

    private fun connect(device:BluetoothDevice){

        Log.d(TAG,"Connecting ${device.address}")

        gatt=device.connectGatt(
            context,
            false,
            callback
        )
    }

    private val callback=object:BluetoothGattCallback(){

        override fun onConnectionStateChange(
            g:BluetoothGatt,
            status:Int,
            newState:Int
        ){

            if(newState==BluetoothProfile.STATE_CONNECTED){

                Log.d(TAG,"BLE Connected")

                gatt=g
                onConnectionChanged?.invoke(true)

                g.discoverServices()
            }


            if(newState==BluetoothProfile.STATE_DISCONNECTED){

                Log.d(TAG,"BLE Disconnected")

                onConnectionChanged?.invoke(false)
            }
        }


        override fun onServicesDiscovered(
            g:BluetoothGatt,
            status:Int
        ){
            val service= g.getService(SERVICE_UUID)
                    ?: return

            rx= service.getCharacteristic(RX_UUID)

            val tx= service.getCharacteristic(TX_UUID)
                    ?: return

            g.setCharacteristicNotification(
                tx,
                true
            )

            val descriptor= tx.getDescriptor(CCCD_UUID)

            descriptor?.let{
                it.value= BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

                g.writeDescriptor(it)
            }


            Log.d(TAG,"BLE Ready")
        }

        override fun onCharacteristicChanged(
            g:BluetoothGatt,
            c:BluetoothGattCharacteristic
        ){

            if(c.uuid==TX_UUID){
                val data = c.value
                    .toString(Charsets.UTF_8)
                    .trim()

                Log.d(TAG,"ESP32 DATA : $data")

                parseData(data)
            }
        }
    }

    private fun parseData(data:String){

        val msg = data.trim()

        when{

            msg.startsWith("STATE:") -> {
                onStateReceived?.invoke(msg)
            }

            msg.startsWith("EVENT:") -> {
                val event = msg.removePrefix("EVENT:")
                onEventReceived?.invoke(event)
            }

            else -> {
                onEventReceived?.invoke(msg)
            }
        }
    }

    fun sendCommand(cmd:String){

        val ch=rx ?: return

        ch.value=cmd.toByteArray()

        gatt?.writeCharacteristic(ch)

        Log.d(TAG,"SEND : $cmd")
    }

    fun disconnect(){

        gatt?.disconnect()
        gatt?.close()
        gatt=null
    }
}