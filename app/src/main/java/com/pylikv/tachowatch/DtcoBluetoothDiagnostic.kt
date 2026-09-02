package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

class DtcoBluetoothDiagnostic(private val context: Context, private val listener: Listener? = null) {
    interface Listener {
        fun onLogChanged(fullLog: String)
        fun onConnectionStateChanged(connected: Boolean, deviceName: String?)
    }

    companion object {
        private const val VERSION = "BLE-DOWNLOAD-CARD-PROBE-TEST-11E"
        const val RESULT_MARKER = "===== TEST-11 DOWNLOAD RESULT ====="
        private const val RESPONSE_TIMEOUT_MS = 5000L
        private const val PENDING_TIMEOUT_MS = 120000L
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val SERVICE = UUID.fromString("eef90782-55dd-4388-b80b-695aba7a69b5")
        private val FIFO = UUID.fromString("29d3a479-1592-47df-80a4-afa742d369bb")
        private val CREDITS = UUID.fromString("db9c4128-bff3-41fe-a306-fb6f9a8aeb2d")
    }

    private enum class Stage { IDLE, WAIT_C1, WAIT_50, WAIT_75, WAIT_CARD, DONE }
    private val handler = Handler(Looper.getMainLooper())
    private val lines = CopyOnWriteArrayList<String>()
    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var connected = false
    private var fifoSub = false
    private var creditSub = false
    private var txCredits = 0
    private var stage = Stage.IDLE
    private var started = false
    private var finished = false
    private var timeoutToken = 0L
    private var pendingCount = 0
    private var rxApplication = byteArrayOf()
    private var expectedPackets = 0
    private var lastPacketNo = 0
    private var firstCardPayload = byteArrayOf()

    fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun connect(d: BluetoothDevice) {
        if (!hasConnectPermission()) { log("ОШИБКА: нет BLUETOOTH_CONNECT"); return }
        closeGatt(); reset(); device = d
        log("========================================")
        log("TachoWatch — TEST-11E RESPONSE PENDING")
        log("Версия: $VERSION")
        log("Card request = точный Appendix 7: 36 06 01")
        log("FIX: NRC 0x78 теперь НЕ ошибка; ждём окончательный ответ до 120 секунд")
        log("Повторный CardDownload при 0x78 НЕ отправляется")
        log("Цель: получить окончательный SID 76 после responsePending")
        log("Download UUID=$SERVICE")
        log("FIFO=$FIFO")
        log("CREDITS=$CREDITS")
        log("ВАЖНО: успешный card download может обновить LastCardDownload на карте")
        log("Деятельность/страны/ручные записи приложение НЕ изменяет")
        log("========================================")
        try {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                d.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
            else d.connectGatt(context, false, cb)
            log("connectGatt=${if (gatt != null) "CREATED" else "NULL"}")
        } catch (e: Throwable) { err("connectGatt", e) }
    }

    private fun reset() {
        lines.clear(); connected=false; fifoSub=false; creditSub=false; txCredits=0
        stage=Stage.IDLE; started=false; finished=false; timeoutToken++; pendingCount=0
        rxApplication=byteArrayOf(); expectedPackets=0; lastPacketNo=0; firstCardPayload=byteArrayOf()
    }

    fun manualGattCheck() {
        log("===== MANUAL STATUS =====")
        log("connected=$connected FIFO=$fifoSub Credits=$creditSub txCredits=$txCredits stage=$stage started=$started finished=$finished pendingCount=$pendingCount")
        log("rxApplication=${rxApplication.size} expectedPackets=$expectedPackets lastPacketNo=$lastPacketNo firstCardPayload=${firstCardPayload.size}")
    }

    private val cb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("CONNECTION status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected=true; gatt=g; notifyConnection(true, safeName(g.device))
                try { if (!g.requestMtu(512)) discover(g) } catch (_: Throwable) { discover(g) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected=false; timeoutToken++; notifyConnection(false, safeName(g.device)); log("BLE DISCONNECTED status=$status")
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { log("MTU=$mtu status=$status"); discover(g) }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("services status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val s=g.getService(SERVICE) ?: run { finishFail("Download Service NOT FOUND"); return }
            logChar("DOWNLOAD FIFO",s.getCharacteristic(FIFO)); logChar("DOWNLOAD CREDITS",s.getCharacteristic(CREDITS)); subscribe(g,FIFO)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            val u=d.characteristic.uuid; log("CCCD $u status=$status")
            if (u==FIFO) { fifoSub=status==BluetoothGatt.GATT_SUCCESS; handler.postDelayed({if(connected)subscribe(g,CREDITS)},150) }
            else if (u==CREDITS) {
                creditSub=status==BluetoothGatt.GATT_SUCCESS
                if(!creditSub) finishFail("CREDITS subscription failed") else handler.postDelayed({if(connected)grantRx(g,20,"INITIAL")},250)
            }
        }
        @Deprecated("legacy")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) { @Suppress("DEPRECATION") incoming(g,c,c.value ?: byteArrayOf()) }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { incoming(g,c,value) }
    }

    @SuppressLint("MissingPermission") private fun discover(g:BluetoothGatt){ try{log("discoverServices=${g.discoverServices()}")}catch(e:Throwable){err("discover",e)} }
    private fun logChar(label:String,c:BluetoothGattCharacteristic?){
        if(c==null){log("$label NOT FOUND");return}
        log("$label props=0x${Integer.toHexString(c.properties)} perms=0x${Integer.toHexString(c.permissions)} writeType=${writeTypeName(c.writeType)}")
        log("$label flags WRITE=${(c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE)!=0} WRITE_NR=${(c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0} INDICATE=${(c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE)!=0}")
    }
    @SuppressLint("MissingPermission") private fun subscribe(g:BluetoothGatt,u:UUID){
        val c=g.getService(SERVICE)?.getCharacteristic(u) ?: return
        log("${if(u==FIFO)"FIFO" else "CREDITS"} notify=${g.setCharacteristicNotification(c,true)}")
        c.getDescriptor(CCCD)?.let{log("${if(u==FIFO)"FIFO" else "CREDITS"} CCCD=${writeDesc(g,it,BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)}")}
    }

    private fun incoming(g:BluetoothGatt,c:BluetoothGattCharacteristic,v:ByteArray){
        if(c.uuid==CREDITS){
            if(v.isEmpty())return
            val n=u(v[0]); if(n==0xFF){finishFail("FLOW CONTROL REJECTED");return}
            txCredits+=n; log("TX-CREDIT RX +$n => $txCredits")
            if(!started && txCredits>0){started=true;stage=Stage.WAIT_C1;send(g,startCommunication(),"StartCommunication")}
            return
        }
        if(c.uuid!=FIFO)return
        log("RX FIFO BLE chunk len=${v.size} RAW=${hex(v)}"); consumeTransportPacket(g,v)
    }

    private fun consumeTransportPacket(g:BluetoothGatt,v:ByteArray){
        if(v.size<2){log("RX transport packet too short");return}
        val first=u(v[0]); val packetNo=u(v[1]); val payload=v.copyOfRange(2,v.size)
        if(packetNo==1){
            expectedPackets=first; lastPacketNo=1; rxApplication=payload
            log("RX TRANSPORT first packet total=$expectedPackets packet=1 appBytes=${payload.size}")
            if(expectedPackets<=1)finishApplication(g); return
        }
        if(expectedPackets<=1){log("RX TRANSPORT unexpected continuation packet=$packetNo");return}
        if(packetNo!=lastPacketNo+1){log("RX TRANSPORT packet sequence error expected=${lastPacketNo+1} got=$packetNo");rxApplication=byteArrayOf();expectedPackets=0;lastPacketNo=0;return}
        rxApplication+=payload; lastPacketNo=packetNo; log("RX TRANSPORT continuation packet=$packetNo/$expectedPackets appTotal=${rxApplication.size}")
        if(packetNo>=expectedPackets)finishApplication(g)
    }
    private fun finishApplication(g:BluetoothGatt){val a=rxApplication;rxApplication=byteArrayOf();expectedPackets=0;lastPacketNo=0;parseKwpFrame(g,a)}

    private fun parseKwpFrame(g:BluetoothGatt,frame:ByteArray){
        if(frame.isEmpty())return
        log("RX APP reassembled len=${frame.size} RAW=${hex(frame)}")
        val start=frame.indexOfFirst{u(it)==0x80}; if(start<0){log("RX APP: no KWP 0x80 header");return}
        val kwp=frame.copyOfRange(start,frame.size); if(kwp.size<5){log("RX APP: short KWP frame");return}
        val len=u(kwp[3]); val total=4+len+1; if(kwp.size<total){log("RX APP: incomplete KWP expected=$total got=${kwp.size}");return}
        val one=kwp.copyOfRange(0,total); val expected=checksum(one.copyOfRange(0,one.size-1)); val actual=u(one.last())
        log("RX KWP len=$len checksum=${hb(actual)} expected=${hb(expected)} ${if(actual==expected)"OK" else "BAD"}")
        handleFrame(g,one.copyOfRange(4,4+len),one)
    }

    private fun handleFrame(g:BluetoothGatt,data:ByteArray,frame:ByteArray){
        if(data.isEmpty())return
        timeoutToken++; val sid=u(data[0]); log("RX SID=0x${hb(sid)} DATA=${hex(data)}")
        if(sid==0x7F){
            val req=if(data.size>1)u(data[1]) else 0; val n=if(data.size>2)u(data[2]) else 0
            if(n==0x78 && req==0x36 && stage==Stage.WAIT_CARD){
                pendingCount++
                log("*** RESPONSE PENDING *** request SID=0x36 count=$pendingCount")
                log("DTCO принял CardDownload и ещё готовит данные; продолжаем ждать, повторный запрос НЕ отправляем")
                schedulePendingTimeout()
                return
            }
            finishFail("NEGATIVE: request SID=0x${hb(req)} NRC=0x${hb(n)} ${nrcName(n)} stage=$stage"); return
        }
        when(stage){
            Stage.WAIT_C1 -> if(sid==0xC1){log("OK: StartCommunication accepted");stage=Stage.WAIT_50;send(g,startDiagnostic(),"StartDiagnostic 0x10/0x81")}
            Stage.WAIT_50 -> if(sid==0x50){log("OK: Diagnostic session accepted");stage=Stage.WAIT_75;send(g,requestUpload(),"RequestUpload 0x35")}
            Stage.WAIT_75 -> if(sid==0x75){
                log("OK: RequestUpload accepted; DATA=${hex(data)}")
                stage=Stage.WAIT_CARD; send(g,cardDownloadSlot1(),"CardDownload exact SID36 TRTP=06 slot=01")
            }
            Stage.WAIT_CARD -> if(sid==0x76){
                val trep=if(data.size>1)u(data[1]) else -1
                firstCardPayload=if(data.size>2)data.copyOfRange(2,data.size) else byteArrayOf()
                log("*** CARD DATA RESPONSE RECEIVED *** TREP=0x${hb(trep)} payload=${firstCardPayload.size} byte(s)")
                log("CARD PAYLOAD RAW=${hex(firstCardPayload)}"); finishOk(trep,frame)
            }
            else -> Unit
        }
    }

    private fun scheduleTimeout(label:String,expectedStage:Stage){
        val token=++timeoutToken
        handler.postDelayed({if(!finished&&token==timeoutToken&&stage==expectedStage)finishFail("TIMEOUT waiting response for $label at stage=$expectedStage")},RESPONSE_TIMEOUT_MS)
    }

    private fun schedulePendingTimeout(){
        val token=++timeoutToken
        handler.postDelayed({
            if(!finished && token==timeoutToken && stage==Stage.WAIT_CARD){
                finishFail("TIMEOUT after responsePending: no final CardDownload response within ${PENDING_TIMEOUT_MS/1000} s; pendingCount=$pendingCount")
            }
        },PENDING_TIMEOUT_MS)
    }

    private fun finishOk(trep:Int,frame:ByteArray){
        timeoutToken++;stage=Stage.DONE;finished=true
        log("");log("========================================");log(RESULT_MARKER);log("STATUS=SUCCESS")
        log("DownloadService=FOUND");log("StartCommunication=OK");log("StartDiagnostic=OK");log("RequestUpload=OK");log("CardDownloadResponse=OK")
        log("ResponsePendingCount=$pendingCount")
        log("CardTREP=0x${hb(trep)}");log("FirstCardPayloadBytes=${firstCardPayload.size}");log("FirstCardPayloadRAW=${hex(firstCardPayload)}");log("FullResponseFrameRAW=${hex(frame)}")
        log("Следующий этап: continuation/sub-message ACK + TLV decoder");log("========================================")
    }
    private fun finishFail(reason:String){if(finished)return;timeoutToken++;finished=true;stage=Stage.DONE;log("");log("========================================");log(RESULT_MARKER);log("STATUS=FAILED");log(reason);log("ResponsePendingCount=$pendingCount");log("========================================")}

    private fun send(g:BluetoothGatt,kwpFrame:ByteArray,label:String){
        if(finished)return
        if(txCredits<=0){log("WAIT TX-CREDIT for $label");handler.postDelayed({if(!finished)send(g,kwpFrame,label)},250);return}
        val c=g.getService(SERVICE)?.getCharacteristic(FIFO) ?: run{finishFail("FIFO unavailable");return}
        val packet=byteArrayOf(0x01,0x01)+kwpFrame; val ok=writeBest(g,c,packet)
        log("TX $label appLen=${kwpFrame.size} bleLen=${packet.size} initiated=$ok");log("TX BLE RAW=${hex(packet)}");log("TX APP RAW=${hex(kwpFrame)} creditBefore=$txCredits")
        if(ok){txCredits--;scheduleTimeout(label,stage)}else finishFail("write failed: $label")
    }
    private fun grantRx(g:BluetoothGatt,n:Int,label:String){val c=g.getService(SERVICE)?.getCharacteristic(CREDITS)?:return;val ok=writeBest(g,c,byteArrayOf((n and 0xFF).toByte()));log("RX-CREDIT [$label] +$n initiated=$ok")}

    private fun startCommunication()=byteArrayOf(0x81.toByte(),0xEE.toByte(),0xF0.toByte(),0x81.toByte(),0xE0.toByte())
    private fun startDiagnostic()=kwp(byteArrayOf(0x10,0x81.toByte()))
    private fun requestUpload()=kwp(byteArrayOf(0x35,0x00,0x00,0x00,0x00,0x00,0xFF.toByte(),0xFF.toByte(),0xFF.toByte(),0xFF.toByte()))
    private fun cardDownloadSlot1()=kwp(byteArrayOf(0x36,0x06,0x01))
    private fun kwp(data:ByteArray):ByteArray{val body=byteArrayOf(0x80.toByte(),0xEE.toByte(),0xF0.toByte(),data.size.toByte())+data;return body+byteArrayOf(checksum(body).toByte())}
    private fun checksum(b:ByteArray)=b.fold(0){a,x->(a+u(x)) and 0xFF}

    @SuppressLint("MissingPermission") private fun writeBest(g:BluetoothGatt,c:BluetoothGattCharacteristic,v:ByteArray):Boolean=try{
        val nr=(c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0;val type=if(nr)BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU)g.writeCharacteristic(c,v,type)==BluetoothGatt.GATT_SUCCESS else{@Suppress("DEPRECATION")c.writeType=type;@Suppress("DEPRECATION")c.value=v;@Suppress("DEPRECATION")g.writeCharacteristic(c)}
    }catch(e:Throwable){err("writeBest",e);false}
    @SuppressLint("MissingPermission") private fun writeDesc(g:BluetoothGatt,d:BluetoothGattDescriptor,v:ByteArray):Boolean=try{if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU)g.writeDescriptor(d,v)==BluetoothGatt.GATT_SUCCESS else{@Suppress("DEPRECATION")d.value=v;@Suppress("DEPRECATION")g.writeDescriptor(d)}}catch(_:Throwable){false}
    @SuppressLint("MissingPermission") fun disconnect(){timeoutToken++;try{gatt?.disconnect()}catch(_:Throwable){};closeGatt();connected=false;notifyConnection(false,device?.let(::safeName));log("Отключено пользователем")}
    @SuppressLint("MissingPermission") private fun closeGatt(){try{gatt?.close()}catch(_:Throwable){};gatt=null}
    fun clearLog(){lines.clear();notifyLog()}
    private fun nrcName(n:Int)=when(n){0x10->"generalReject";0x11->"serviceNotSupported";0x12->"subFunctionNotSupported";0x13->"incorrectMessageLength";0x21->"busyRepeatRequest";0x22->"conditionsNotCorrect/requestSequenceError";0x31->"requestOutOfRange";0x33->"securityAccessDenied";0x50->"uploadNotAccepted";0x78->"responsePending";0xFA->"dataNotAvailable";else->"NRC"}
    private fun log(s:String){val t=SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(Date());lines.add("[$t] $s");while(lines.size>20000)lines.removeAt(0);notifyLog()}
    private fun notifyLog(){val text=lines.joinToString("\n");handler.post{listener?.onLogChanged(text)}}
    private fun notifyConnection(v:Boolean,n:String?){handler.post{listener?.onConnectionStateChanged(v,n)}}
    @SuppressLint("MissingPermission") private fun safeName(d:BluetoothDevice)=try{d.name?:d.address}catch(_:Throwable){"DTCO"}
    private fun err(w:String,e:Throwable){log("ERROR [$w] ${e.javaClass.simpleName}: ${e.message}")}
    private fun writeTypeName(t:Int)=when(t){BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT->"DEFAULT";BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE->"NO_RESPONSE";BluetoothGattCharacteristic.WRITE_TYPE_SIGNED->"SIGNED";else->t.toString()}
    private fun u(b:Byte)=b.toInt() and 0xFF
    private fun hb(v:Int)=String.format(Locale.US,"%02X",v and 0xFF)
    private fun hex(b:ByteArray)=b.joinToString(" "){String.format(Locale.US,"%02X",u(it))}
}
