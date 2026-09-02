package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

class DtcoBluetoothDiagnostic(private val context: Context, private val listener: Listener? = null) {
    interface Listener {
        fun onLogChanged(fullLog: String)
        fun onConnectionStateChanged(connected: Boolean, deviceName: String?)
    }

    companion object {
        private const val VERSION = "BLE-DOWNLOAD-CARD-FULL-TEST-11I"
        const val RESULT_MARKER = "===== TEST-11 DOWNLOAD RESULT ====="
        private const val SHORT_TIMEOUT_MS = 5000L
        private const val CARD_TIMEOUT_MS = 120000L
        private const val FRAGMENT_TIMEOUT_MS = 5000L
        private const val INITIAL_RX_CREDITS = 24
        private const val REFILL_EVERY_PACKETS = 12
        private const val REFILL_RX_CREDITS = 12
        private const val REFILL_DELAY_MS = 180L
        private const val MAX_FRAGMENT_RETRIES = 3
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val SERVICE = UUID.fromString("eef90782-55dd-4388-b80b-695aba7a69b5")
        private val FIFO = UUID.fromString("29d3a479-1592-47df-80a4-afa742d369bb")
        private val CREDITS = UUID.fromString("db9c4128-bff3-41fe-a306-fb6f9a8aeb2d")
    }

    private enum class Stage { IDLE, WAIT_C1, WAIT_50, WAIT_75, WAIT_CARD, WAIT_77, WAIT_C2, DONE }

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
    private var fragmentToken = 0L
    private var pendingCount = 0
    private var cardPendingCount = 0
    private var ackPendingCount = 0
    private var subMessages = 0
    private var lastCounter = 0
    private var requestedCounter = 0
    private var fifoPacketsSinceRefill = 0
    private var totalFifoPackets = 0
    private var rxCreditsGranted = 0
    private var fragmentRetries = 0
    private var rxApplication = byteArrayOf()
    private var expectedPackets = 0
    private var lastPacketNo = 0
    private val cardFile = ByteArrayOutputStream()
    private var savedPath = ""

    fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun connect(d: BluetoothDevice) {
        if (!hasConnectPermission()) { log("ОШИБКА: нет BLUETOOTH_CONNECT"); return }
        closeGatt(); reset(); device = d
        log("========================================")
        log("TachoWatch — TEST-11I SERIALIZED BLE WRITES")
        log("Версия: $VERSION")
        log("Card request: 36 06 01")
        log("NRC 78 для SID36/SID83 = responsePending, НЕ ошибка")
        log("RX credits: initial=$INITIAL_RX_CREDITS, refill +$REFILL_RX_CREDITS каждые $REFILL_EVERY_PACKETS FIFO packets")
        log("FIX: refill откладывается на ${REFILL_DELAY_MS}ms, чтобы не конфликтовать с ACK FIFO write")
        log("Fragment watchdog: ${FRAGMENT_TIMEOUT_MS/1000}s, retry expected MsgC до $MAX_FRAGMENT_RETRIES раз")
        log("RAW больших card blocks в журнал НЕ выводим; пишем сразу в TLV/DDD")
        log("ВАЖНО: штатный card download может обновить LastCardDownload/Card_Download на карте")
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
        stage=Stage.IDLE; started=false; finished=false; timeoutToken++; fragmentToken++
        pendingCount=0; cardPendingCount=0; ackPendingCount=0; subMessages=0; lastCounter=0; requestedCounter=0
        fifoPacketsSinceRefill=0; totalFifoPackets=0; rxCreditsGranted=0; fragmentRetries=0
        rxApplication=byteArrayOf(); expectedPackets=0; lastPacketNo=0
        cardFile.reset(); savedPath=""
    }

    fun manualGattCheck() {
        log("===== MANUAL STATUS =====")
        log("connected=$connected FIFO=$fifoSub Credits=$creditSub txCredits=$txCredits stage=$stage finished=$finished")
        log("pending=$pendingCount cardPending=$cardPendingCount ackPending=$ackPendingCount subMessages=$subMessages lastCounter=$lastCounter requestedCounter=$requestedCounter")
        log("cardBytes=${cardFile.size()} fifoPackets=$totalFifoPackets rxCreditsGranted=$rxCreditsGranted expectedPackets=$expectedPackets lastPacketNo=$lastPacketNo fragmentRetries=$fragmentRetries")
        log("savedPath=$savedPath")
    }

    private val cb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("CONNECTION status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected=true; gatt=g; notifyConnection(true, safeName(g.device))
                try { if (!g.requestMtu(512)) discover(g) } catch (_: Throwable) { discover(g) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected=false; timeoutToken++; fragmentToken++; notifyConnection(false, safeName(g.device)); log("BLE DISCONNECTED status=$status")
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
            val uu=d.characteristic.uuid; log("CCCD $uu status=$status")
            if (uu==FIFO) {
                fifoSub=status==BluetoothGatt.GATT_SUCCESS
                handler.postDelayed({if(connected)subscribe(g,CREDITS)},150)
            } else if (uu==CREDITS) {
                creditSub=status==BluetoothGatt.GATT_SUCCESS
                if(!creditSub) finishFail("CREDITS subscription failed")
                else handler.postDelayed({if(connected)grantRx(g,INITIAL_RX_CREDITS,"INITIAL")},250)
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

    @SuppressLint("MissingPermission") private fun subscribe(g:BluetoothGatt,uu:UUID){
        val c=g.getService(SERVICE)?.getCharacteristic(uu) ?: return
        log("${if(uu==FIFO)"FIFO" else "CREDITS"} notify=${g.setCharacteristicNotification(c,true)}")
        c.getDescriptor(CCCD)?.let{log("${if(uu==FIFO)"FIFO" else "CREDITS"} CCCD=${writeDesc(g,it,BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)}")}
    }

    private fun incoming(g:BluetoothGatt,c:BluetoothGattCharacteristic,v:ByteArray){
        if(c.uuid==CREDITS){
            if(v.isEmpty())return
            val n=u(v[0]); if(n==0xFF){finishFail("FLOW CONTROL REJECTED");return}
            txCredits+=n
            if(!started || txCredits<=2) log("TX-CREDIT RX +$n => $txCredits")
            if(!started && txCredits>0){started=true;stage=Stage.WAIT_C1;send(g,startCommunication(),"StartCommunication",SHORT_TIMEOUT_MS)}
            return
        }
        if(c.uuid!=FIFO)return

        totalFifoPackets++
        fifoPacketsSinceRefill++
        val refillDue = fifoPacketsSinceRefill>=REFILL_EVERY_PACKETS
        if(refillDue) fifoPacketsSinceRefill=0

        val pNo=if(v.size>1)u(v[1]) else -1
        val total=if(v.isNotEmpty())u(v[0]) else -1
        if(stage!=Stage.WAIT_CARD || total<=1) log("RX FIFO len=${v.size} packet=$pNo/$total HEAD=${hex(v.take(20).toByteArray())}${if(v.size>20)" ..." else ""}")
        consumeTransportPacket(g,v)

        if(refillDue && !finished){
            handler.postDelayed({
                if(!finished && connected) grantRx(g,REFILL_RX_CREDITS,"REFILL@$totalFifoPackets")
            }, REFILL_DELAY_MS)
        }
    }

    private fun consumeTransportPacket(g:BluetoothGatt,v:ByteArray){
        if(v.size<2){log("RX transport packet too short");return}
        val first=u(v[0]); val packetNo=u(v[1]); val payload=v.copyOfRange(2,v.size)
        if(packetNo==1){
            expectedPackets=first; lastPacketNo=1; rxApplication=payload
            if(expectedPackets>1){ scheduleFragmentWatchdog(g) }
            else { fragmentToken++; finishApplication(g) }
            return
        }
        if(expectedPackets<=1){log("RX TRANSPORT unexpected continuation packet=$packetNo");return}
        if(packetNo!=lastPacketNo+1){
            log("RX TRANSPORT packet sequence error expected=${lastPacketNo+1} got=$packetNo")
            clearPartialTransport(); return
        }
        rxApplication+=payload; lastPacketNo=packetNo
        if(packetNo>=expectedPackets){fragmentToken++;fragmentRetries=0;finishApplication(g)}
    }

    private fun scheduleFragmentWatchdog(g:BluetoothGatt){
        val token=++fragmentToken
        handler.postDelayed({
            if(finished || token!=fragmentToken || stage!=Stage.WAIT_CARD) return@postDelayed
            if(expectedPackets>1 && lastPacketNo<expectedPackets){
                val missing=lastPacketNo+1
                log("FRAGMENT TIMEOUT packet=$missing/$expectedPackets after MsgC=$lastCounter requested=$requestedCounter; partial=${rxApplication.size} bytes")
                clearPartialTransport()
                if(requestedCounter>0 && fragmentRetries<MAX_FRAGMENT_RETRIES){
                    fragmentRetries++
                    log("RECOVERY #$fragmentRetries: повторно запрашиваем MsgC=$requestedCounter")
                    send(g,ackSubMessage(requestedCounter),"RECOVER SID83 MsgC=$requestedCounter",CARD_TIMEOUT_MS)
                } else finishFail("Fragment recovery exhausted for requested MsgC=$requestedCounter")
            }
        },FRAGMENT_TIMEOUT_MS)
    }

    private fun clearPartialTransport(){ fragmentToken++; rxApplication=byteArrayOf(); expectedPackets=0; lastPacketNo=0 }

    private fun finishApplication(g:BluetoothGatt){
        val a=rxApplication;rxApplication=byteArrayOf();expectedPackets=0;lastPacketNo=0;parseKwpFrame(g,a)
    }

    private fun parseKwpFrame(g:BluetoothGatt,frame:ByteArray){
        if(frame.isEmpty())return
        val start=frame.indexOfFirst{u(it)==0x80}; if(start<0){log("RX APP: no KWP 0x80 header");return}
        val kwp=frame.copyOfRange(start,frame.size); if(kwp.size<5){log("RX APP: short KWP frame");return}
        val len=u(kwp[3]); val total=4+len+1
        if(kwp.size<total){log("RX APP: incomplete KWP expected=$total got=${kwp.size}");return}
        val one=kwp.copyOfRange(0,total); val expected=checksum(one.copyOfRange(0,one.size-1)); val actual=u(one.last())
        if(actual!=expected){finishFail("KWP checksum BAD actual=${hb(actual)} expected=${hb(expected)}");return}
        handleFrame(g,one.copyOfRange(4,4+len),len)
    }

    private fun handleFrame(g:BluetoothGatt,data:ByteArray,kwpLen:Int){
        if(data.isEmpty())return
        timeoutToken++; val sid=u(data[0])
        if(stage!=Stage.WAIT_CARD || sid!=0x76) log("RX SID=0x${hb(sid)} DATA=${hex(data.take(24).toByteArray())}${if(data.size>24)" ..." else ""}")
        if(sid==0x7F){
            val req=if(data.size>1)u(data[1]) else 0; val n=if(data.size>2)u(data[2]) else 0
            if(n==0x78 && stage==Stage.WAIT_CARD && (req==0x36 || req==0x83)){
                pendingCount++
                if(req==0x36){cardPendingCount++;log("RESPONSE PENDING CardDownload count=$cardPendingCount")}
                else {ackPendingCount++;log("RESPONSE PENDING ACK count=$ackPendingCount requestedMsgC=$requestedCounter")}
                scheduleTimeout("card stream after NRC78 SID${hb(req)}",Stage.WAIT_CARD,CARD_TIMEOUT_MS)
                return
            }
            finishFail("NEGATIVE requestSID=0x${hb(req)} NRC=0x${hb(n)} ${nrcName(n)} stage=$stage"); return
        }
        when(stage){
            Stage.WAIT_C1 -> if(sid==0xC1){log("OK StartCommunication");stage=Stage.WAIT_50;send(g,startDiagnostic(),"StartDiagnostic",SHORT_TIMEOUT_MS)}
            Stage.WAIT_50 -> if(sid==0x50){log("OK StartDiagnostic");stage=Stage.WAIT_75;send(g,requestUpload(),"RequestUpload",SHORT_TIMEOUT_MS)}
            Stage.WAIT_75 -> if(sid==0x75){log("OK RequestUpload");stage=Stage.WAIT_CARD;requestedCounter=1;send(g,cardDownloadSlot1(),"CardDownload SID36 TRTP06 slot01",CARD_TIMEOUT_MS)}
            Stage.WAIT_CARD -> if(sid==0x76){handleCardSubMessage(g,data,kwpLen)}
            Stage.WAIT_77 -> if(sid==0x77){log("OK RequestTransferExit");stage=Stage.WAIT_C2;send(g,stopCommunication(),"StopCommunication",SHORT_TIMEOUT_MS)}
            Stage.WAIT_C2 -> if(sid==0xC2){log("OK StopCommunication");finishSuccess()}
            else -> Unit
        }
    }

    private fun handleCardSubMessage(g:BluetoothGatt,data:ByteArray,kwpLen:Int){
        if(data.size<4){finishFail("SID76/06 too short");return}
        val trep=u(data[1]); if(trep!=0x06){finishFail("Unexpected TREP=0x${hb(trep)}");return}
        val counter=(u(data[2]) shl 8) or u(data[3])
        if(subMessages>0 && counter==lastCounter){
            log("DUPLICATE MsgC=$counter ignored; ACK next=${(counter+1) and 0xFFFF}")
            val next=(counter+1) and 0xFFFF; requestedCounter=next
            send(g,ackSubMessage(next),"ACK duplicate nextMsgC=$next",CARD_TIMEOUT_MS)
            return
        }
        if(requestedCounter>0 && counter!=requestedCounter) log("WARNING MsgC expected=$requestedCounter got=$counter")
        fragmentRetries=0
        val payload=if(data.size>4)data.copyOfRange(4,data.size) else byteArrayOf()
        subMessages++; lastCounter=counter; cardFile.write(payload)
        log("CARD #$subMessages MsgC=$counter len=$kwpLen payload=${payload.size} totalTLV=${cardFile.size()} creditsGranted=$rxCreditsGranted fifoPackets=$totalFifoPackets")
        if(subMessages==1 && payload.size>=5){
            val fid=(u(payload[0]) shl 8) or u(payload[1]); val suffix=u(payload[2]); val len=(u(payload[3]) shl 8) or u(payload[4])
            log("FIRST TLV FID=${String.format(Locale.US,"%04X",fid)} suffix=${hb(suffix)} len=$len")
        }
        if(kwpLen==0xFF){
            val next=(counter+1) and 0xFFFF; requestedCounter=next
            send(g,ackSubMessage(next),"ACK SID83 nextMsgC=$next",CARD_TIMEOUT_MS)
        } else {
            log("FINAL CARD SUBMSG MsgC=$counter KWP_LEN=$kwpLen")
            requestedCounter=0; saveCardFile(); stage=Stage.WAIT_77
            send(g,requestTransferExit(),"RequestTransferExit",SHORT_TIMEOUT_MS)
        }
    }

    private fun saveCardFile(){
        try{
            val dir=context.getExternalFilesDir(null) ?: context.filesDir
            if(!dir.exists())dir.mkdirs()
            val stamp=SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())
            val f=File(dir,"TachoWatch_card_$stamp.ddd")
            f.writeBytes(cardFile.toByteArray()); savedPath=f.absolutePath
            log("CARD FILE SAVED bytes=${f.length()} path=$savedPath")
        }catch(e:Throwable){log("CARD FILE SAVE ERROR ${e.javaClass.simpleName}: ${e.message}")}
    }

    private fun countTlvs(bytes:ByteArray):Pair<Int,Int>{
        var p=0; var n=0
        while(p+5<=bytes.size){
            val len=(u(bytes[p+3]) shl 8) or u(bytes[p+4]); val end=p+5+len
            if(end>bytes.size)break
            n++; p=end
        }
        return Pair(n,p)
    }

    private fun scheduleTimeout(label:String,expectedStage:Stage,ms:Long){
        val token=++timeoutToken
        handler.postDelayed({if(!finished&&token==timeoutToken&&stage==expectedStage)finishFail("TIMEOUT waiting $label stage=$expectedStage after ${ms/1000}s")},ms)
    }

    private fun finishSuccess(){
        timeoutToken++;fragmentToken++;stage=Stage.DONE;finished=true
        val bytes=cardFile.toByteArray(); val parsed=countTlvs(bytes)
        log("");log("========================================");log(RESULT_MARKER);log("STATUS=SUCCESS")
        log("DownloadService=FOUND StartCommunication=OK StartDiagnostic=OK RequestUpload=OK CardDownloadResponse=OK")
        log("ResponsePendingCount=$pendingCount CardPending=$cardPendingCount AckPending=$ackPendingCount")
        log("SubMessages=$subMessages LastMsgC=$lastCounter CardTLVBytes=${bytes.size}")
        log("CompleteTLVRecords=${parsed.first} TLVParsedBytes=${parsed.second}")
        log("FifoPackets=$totalFifoPackets RxCreditsGranted=$rxCreditsGranted")
        log("CardFilePath=$savedPath")
        log("First64TLV=${hex(bytes.take(64).toByteArray())}")
        log("TransferExit=OK StopCommunication=OK")
        log("Следующий этап: декодирование EF карты")
        log("========================================")
    }

    private fun finishFail(reason:String){
        if(finished)return
        timeoutToken++;fragmentToken++;finished=true;stage=Stage.DONE
        log("");log("========================================");log(RESULT_MARKER);log("STATUS=FAILED");log(reason)
        log("ResponsePendingCount=$pendingCount CardPending=$cardPendingCount AckPending=$ackPendingCount")
        log("SubMessages=$subMessages CardTLVBytes=${cardFile.size()} LastMsgC=$lastCounter RequestedMsgC=$requestedCounter")
        log("FifoPackets=$totalFifoPackets RxCreditsGranted=$rxCreditsGranted expectedPackets=$expectedPackets lastPacketNo=$lastPacketNo")
        log("========================================")
    }

    private fun send(g:BluetoothGatt,kwpFrame:ByteArray,label:String,timeoutMs:Long){
        if(finished)return
        if(txCredits<=0){handler.postDelayed({if(!finished)send(g,kwpFrame,label,timeoutMs)},250);return}
        val c=g.getService(SERVICE)?.getCharacteristic(FIFO) ?: run{finishFail("FIFO unavailable");return}
        val packet=byteArrayOf(0x01,0x01)+kwpFrame; val ok=writeBest(g,c,packet)
        if(stage!=Stage.WAIT_CARD || !label.startsWith("ACK SID83")) log("TX $label initiated=$ok HEAD=${hex(packet.take(20).toByteArray())}")
        if(ok){txCredits--;scheduleTimeout(label,stage,timeoutMs)}else {
            log("BLE FIFO busy for $label; retry через 80ms")
            handler.postDelayed({if(!finished)send(g,kwpFrame,label,timeoutMs)},80)
        }
    }

    private fun grantRx(g:BluetoothGatt,n:Int,label:String){
        val c=g.getService(SERVICE)?.getCharacteristic(CREDITS)?:return
        val ok=writeBest(g,c,byteArrayOf((n and 0xFF).toByte()))
        if(ok){
            rxCreditsGranted+=n
            log("RX-CREDIT [$label] +$n initiated=true totalGranted=$rxCreditsGranted")
        } else {
            log("RX-CREDIT [$label] GATT busy; retry через 120ms")
            handler.postDelayed({if(!finished && connected) grantRx(g,n,label+"-retry")},120)
        }
    }

    private fun startCommunication()=byteArrayOf(0x81.toByte(),0xEE.toByte(),0xF0.toByte(),0x81.toByte(),0xE0.toByte())
    private fun startDiagnostic()=kwp(byteArrayOf(0x10,0x81.toByte()))
    private fun requestUpload()=kwp(byteArrayOf(0x35,0x00,0x00,0x00,0x00,0x00,0xFF.toByte(),0xFF.toByte(),0xFF.toByte(),0xFF.toByte()))
    private fun cardDownloadSlot1()=kwp(byteArrayOf(0x36,0x06,0x01))
    private fun ackSubMessage(next:Int)=kwp(byteArrayOf(0x83.toByte(),0x76,((next shr 8) and 0xFF).toByte(),(next and 0xFF).toByte()))
    private fun requestTransferExit()=kwp(byteArrayOf(0x37))
    private fun stopCommunication()=kwp(byteArrayOf(0x82.toByte()))
    private fun kwp(data:ByteArray):ByteArray{val body=byteArrayOf(0x80.toByte(),0xEE.toByte(),0xF0.toByte(),data.size.toByte())+data;return body+byteArrayOf(checksum(body).toByte())}
    private fun checksum(b:ByteArray)=b.fold(0){a,x->(a+u(x)) and 0xFF}

    @SuppressLint("MissingPermission") private fun writeBest(g:BluetoothGatt,c:BluetoothGattCharacteristic,v:ByteArray):Boolean=try{
        val nr=(c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0
        val type=if(nr)BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU)g.writeCharacteristic(c,v,type)==BluetoothGatt.GATT_SUCCESS
        else{@Suppress("DEPRECATION")c.writeType=type;@Suppress("DEPRECATION")c.value=v;@Suppress("DEPRECATION")g.writeCharacteristic(c)}
    }catch(e:Throwable){err("writeBest",e);false}

    @SuppressLint("MissingPermission") private fun writeDesc(g:BluetoothGatt,d:BluetoothGattDescriptor,v:ByteArray):Boolean=try{
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU)g.writeDescriptor(d,v)==BluetoothGatt.GATT_SUCCESS
        else{@Suppress("DEPRECATION")d.value=v;@Suppress("DEPRECATION")g.writeDescriptor(d)}
    }catch(_:Throwable){false}

    @SuppressLint("MissingPermission") fun disconnect(){timeoutToken++;fragmentToken++;try{gatt?.disconnect()}catch(_:Throwable){};closeGatt();connected=false;notifyConnection(false,device?.let(::safeName));log("Отключено пользователем")}
    @SuppressLint("MissingPermission") private fun closeGatt(){try{gatt?.close()}catch(_:Throwable){};gatt=null}
    fun clearLog(){lines.clear();notifyLog()}
    private fun nrcName(n:Int)=when(n){0x10->"generalReject";0x11->"serviceNotSupported";0x12->"subFunctionNotSupported";0x13->"incorrectMessageLength";0x21->"busyRepeatRequest";0x22->"conditionsNotCorrect/requestSequenceError";0x31->"requestOutOfRange";0x33->"securityAccessDenied";0x50->"uploadNotAccepted";0x73->"wrongBlockSequenceCounter";0x78->"responsePending";0xFA->"dataNotAvailable";else->"NRC"}
    private fun log(s:String){val t=SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(Date());lines.add("[$t] $s");while(lines.size>10000)lines.removeAt(0);notifyLog()}
    private fun notifyLog(){val text=lines.joinToString("\n");handler.post{listener?.onLogChanged(text)}}
    private fun notifyConnection(v:Boolean,n:String?){handler.post{listener?.onConnectionStateChanged(v,n)}}
    @SuppressLint("MissingPermission") private fun safeName(d:BluetoothDevice)=try{d.name?:d.address}catch(_:Throwable){"DTCO"}
    private fun err(w:String,e:Throwable){log("ERROR [$w] ${e.javaClass.simpleName}: ${e.message}")}
    private fun writeTypeName(t:Int)=when(t){BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT->"DEFAULT";BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE->"NO_RESPONSE";BluetoothGattCharacteristic.WRITE_TYPE_SIGNED->"SIGNED";else->t.toString()}
    private fun u(b:Byte)=b.toInt() and 0xFF
    private fun hb(v:Int)=String.format(Locale.US,"%02X",v and 0xFF)
    private fun hex(b:ByteArray)=b.joinToString(" "){String.format(Locale.US,"%02X",u(it))}
}
