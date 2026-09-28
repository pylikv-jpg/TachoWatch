package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import androidx.core.content.ContextCompat
import java.util.UUID

/** Isolated Remote-HMI writer. It never reads or writes TachoWatch counter preferences. */
class RhmiControlClient(private val context: Context, private val listener: Listener) {
    interface Listener { fun onState(text:String); fun onPairingDataReady(); fun onCommandResult(text:String) }
    companion object {
        private val CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val SERVICE=TachographDiscovery.DIAGNOSTICS_SERVICE
        private val FIFO=UUID.fromString("e413960c-75ba-4ca9-8a67-99bc052a1b13")
        private val CREDITS=UUID.fromString("e168d1a6-304f-42b4-ab96-4cd1d4efebd9")
        private const val PREFS="tachowatch_rhmi_control"
        private const val KEY_SESSION="rhmi_session_id"
    }
    private val handler=Handler(Looper.getMainLooper()); private var gatt:BluetoothGatt?=null; private var credits=0
    private var connected=false; private var rhmiOpen=false; private var opening=false; private var pendingPairId:ByteArray?=null; private var pendingTransportType=-1
    private var queued:ByteArray?=null; private var queuedLabel=""
    fun hasPermission()=Build.VERSION.SDK_INT<31 || ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
    @SuppressLint("MissingPermission") fun connect(d:BluetoothDevice){ if(!hasPermission()){state("Нет разрешения Bluetooth");return}; disconnect(); state("Подключение к "+safeName(d)+"…"); gatt=if(Build.VERSION.SDK_INT>=23)d.connectGatt(context,false,cb,BluetoothDevice.TRANSPORT_LE) else d.connectGatt(context,false,cb) }
    @SuppressLint("MissingPermission") fun disconnect(){runCatching{gatt?.disconnect()};runCatching{gatt?.close()};gatt=null;connected=false;rhmiOpen=false;opening=false;credits=0}
    fun requestPairing(){ sendDirect(byteArrayOf(0x31,0x01,0xF2.toByte(),0x13,0x01),"PairRHMIclient F213") }
    fun acceptToken(token:String):Boolean{
        val digits=token.filter{it.isDigit()}; if(digits.length!=8){state("Код должен содержать 8 цифр");return false}
        val tx=pendingPairId ?: run{state("Сначала нажмите «Запросить сопряжение»");return false}
        val tok=ByteArray(4){i->(((digits[i*2]-'0') shl 4) or (digits[i*2+1]-'0')).toByte()}
        val id=if(pendingTransportType==0) tx.copyOf() else ByteArray(8){i->(tx[i].toInt() xor tok[i%4].toInt()).toByte()}
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY_SESSION,id.joinToString(""){String.format("%02X",it.toInt() and 255)}).apply()
        state("RHMI ID сохранён. Проверяем…"); verifySessionId(); return true
    }
    fun setActivity(type:Int){ val body=byteArrayOf(0x31,0x01,0xF2.toByte(),0x05,0x00,type.toByte())+timestamp(); sendSigned(body,"F205 SetActivity") }
    fun loadUnload(type:Int){ val body=byteArrayOf(0x31,0x01,0xF2.toByte(),0x06,type.toByte())+timestamp(); sendSigned(body,"F206 LoadUnload") }
    fun ferryTrain(begin:Boolean){ val entry=if(begin)0x03 else 0x04; val body=byteArrayOf(0x31,0x01,0xF2.toByte(),0x04,entry.toByte())+timestamp(); sendSigned(body,"F204 Ferry/Train "+if(begin)"BEGIN" else "END") }
    private fun verifySessionId(){val body=byteArrayOf(0x31,0x01,0xF2.toByte(),0x14,0x01)+timestamp();sendSigned(body,"F214 VerifyRHMISessionId")}
    private fun sendSigned(prefix:ByteArray,label:String){val id=loadSessionId()?:run{state("Сначала выполните сопряжение RHMI");return}; val cs=crc32NoXor(prefix+id); queueSigned(prefix+be32(cs),label)}
    private fun timestamp():ByteArray=(System.currentTimeMillis()/1000L).let(::be32)
    private fun be32(v:Long)=byteArrayOf((v ushr 24).toByte(),(v ushr 16).toByte(),(v ushr 8).toByte(),v.toByte())
    private fun crc32NoXor(b:ByteArray):Long{var c=0L;for(x in b){c=c xor (x.toLong() and 255);repeat(8){c=if((c and 1L)!=0L)(c ushr 1) xor 0xEDB88320L else c ushr 1}};return c and 0xFFFFFFFFL}
    private fun loadSessionId():ByteArray?{val s=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY_SESSION,null)?:return null;if(s.length!=16)return null;return runCatching{ByteArray(8){i->s.substring(i*2,i*2+2).toInt(16).toByte()}}.getOrNull()}
    private val cb=object:BluetoothGattCallback(){
        @SuppressLint("MissingPermission") override fun onConnectionStateChange(g:BluetoothGatt,status:Int,newState:Int){if(newState==BluetoothProfile.STATE_CONNECTED){gatt=g;connected=true;state("Bluetooth подключён");if(Build.VERSION.SDK_INT>=23)g.requestMtu(512) else g.discoverServices()}else{connected=false;rhmiOpen=false;opening=false;state("Bluetooth отключён (status=$status)")}}
        @SuppressLint("MissingPermission") override fun onMtuChanged(g:BluetoothGatt,mtu:Int,status:Int){g.discoverServices()}
        override fun onServicesDiscovered(g:BluetoothGatt,status:Int){val s=g.getService(SERVICE)?:run{state("Diagnostics service не найден");return};subscribe(g,s.getCharacteristic(FIFO))}
        override fun onDescriptorWrite(g:BluetoothGatt,d:BluetoothGattDescriptor,status:Int){if(d.characteristic.uuid==FIFO)handler.postDelayed({g.getService(SERVICE)?.getCharacteristic(CREDITS)?.let{subscribe(g,it)}},120) else if(d.characteristic.uuid==CREDITS)handler.postDelayed({grant(g)},150)}
        @Deprecated("legacy") override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic){if(Build.VERSION.SDK_INT<33)incoming(g,c,c.value?:byteArrayOf())}
        override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic,value:ByteArray){incoming(g,c,value)}
    }
    private fun incoming(g:BluetoothGatt,c:BluetoothGattCharacteristic,v:ByteArray){
        if(c.uuid==CREDITS){if(v.isNotEmpty()){credits+=v[0].toInt() and 255;flush()};return}
        if(c.uuid!=FIFO||v.size<3)return
        val a=v.copyOfRange(2,v.size)
        if(a.size>=4&&u(a[0])==0x71&&u(a[1])==1&&u(a[2])==0xF2&&u(a[3])==0x11){state("RHMI: запрос открытия принят");sendRawApp(byteArrayOf(0x31,0x03,0xF2.toByte(),0x11),"RHMI status");return}
        if(a.size>=5&&u(a[0])==0x71&&u(a[1])==3&&u(a[2])==0xF2&&u(a[3])==0x11){opening=false;rhmiOpen=u(a[4])==0x10;state("RHMI status=0x"+String.format("%02X",u(a[4]))+if(rhmiOpen)" OPEN" else "");if(rhmiOpen)flush();return}
        if(a.size>=18&&u(a[0])==0x71&&u(a[1])==1&&u(a[2])==0xF2&&u(a[3])==0x13){pendingTransportType=u(a[5]);pendingPairId=a.copyOfRange(6,14);state("DTCO показал 8-значный RHMI token. Введите его ниже.");listener.onPairingDataReady();return}
        if(a.size>=4&&u(a[0])==0x71&&u(a[1])==1&&u(a[2])==0xF2){listener.onCommandResult("DTCO принял команду F2"+String.format("%02X",u(a[3])));return}
        if(a.size>=3&&u(a[0])==0x7F){listener.onCommandResult("DTCO отклонил: SID="+String.format("%02X",u(a[1]))+", NRC="+String.format("%02X",u(a[2])));return}
    }
    private fun queueSigned(app:ByteArray,label:String){queued=app;queuedLabel=label;if(!connected){state("Нет соединения с DTCO");return};if(!rhmiOpen){openRhmi();return};flush()}
    private fun sendDirect(app:ByteArray,label:String){if(!connected){state("Нет соединения с DTCO");return};sendRawApp(app,label)}
    private fun openRhmi(){if(opening)return;if(credits<=0){state("Ожидание BLE credits…");return};opening=true;sendRawApp(byteArrayOf(0x31,0x01,0xF2.toByte(),0x11),"OpenRHMISession")}
    private fun flush(){val app=queued?:return;if(!connected||credits<=0)return;if(!rhmiOpen){openRhmi();return};val label=queuedLabel;queued=null;sendRawApp(app,label)}
    private fun sendRawApp(app:ByteArray,label:String){if(credits<=0){state("Ожидание BLE credits…");handler.postDelayed({sendRawApp(app,label)},200);return};write(byteArrayOf(1,1)+app,label)}
    @SuppressLint("MissingPermission") private fun subscribe(g:BluetoothGatt,c:BluetoothGattCharacteristic){g.setCharacteristicNotification(c,true);val d=c.getDescriptor(CCCD)?:return;if(Build.VERSION.SDK_INT>=33)g.writeDescriptor(d,BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) else {@Suppress("DEPRECATION") d.value=BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;@Suppress("DEPRECATION") g.writeDescriptor(d)}}
    private fun grant(g:BluetoothGatt){g.getService(SERVICE)?.getCharacteristic(CREDITS)?.let{writeRaw(g,it,byteArrayOf(8))}}
    private fun write(v:ByteArray,label:String){val g=gatt?:return;val c=g.getService(SERVICE)?.getCharacteristic(FIFO)?:return;if(writeRaw(g,c,v)){credits=(credits-1).coerceAtLeast(0);state("Отправлено: $label")}}
    @SuppressLint("MissingPermission") private fun writeRaw(g:BluetoothGatt,c:BluetoothGattCharacteristic,v:ByteArray):Boolean=try{if(Build.VERSION.SDK_INT>=33)g.writeCharacteristic(c,v,BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)==BluetoothGatt.GATT_SUCCESS else {@Suppress("DEPRECATION") c.writeType=BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;@Suppress("DEPRECATION") c.value=v;@Suppress("DEPRECATION") g.writeCharacteristic(c)}}catch(_:Throwable){false}
    private fun state(s:String){handler.post{listener.onState(s)}};private fun u(b:Byte)=b.toInt() and 255;@SuppressLint("MissingPermission") private fun safeName(d:BluetoothDevice)=runCatching{d.name?:d.address}.getOrDefault("DTCO")
}
