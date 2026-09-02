package com.pylikv.tachowatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class DtcoBluetoothDiagnostic(private val context: Context, private val listener: Listener? = null) {
    interface Listener {
        fun onLogChanged(fullLog: String)
        fun onConnectionStateChanged(connected: Boolean, deviceName: String?)
    }

    companion object {
        private const val VERSION = "BLE-RHMI-READONLY-SECURITY-SEED-TEST-8B"
        private const val MAX_LOG_LINES = 14000
        private const val RESPONSE_TIMEOUT_MS = 3000L
        private const val CREDIT_WAIT_MS = 12000L
        private const val NEXT_DELAY_MS = 350L
        private val UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val UUID_SERVICE = UUID.fromString("fa213def-aef4-475c-bcea-0a8d69073efc")
        private val UUID_FIFO = UUID.fromString("e413960c-75ba-4ca9-8a67-99bc052a1b13")
        private val UUID_CREDITS = UUID.fromString("e168d1a6-304f-42b4-ab96-4cd1d4efebd9")
    }

    private enum class ResultType { POSITIVE, NRC, TIMEOUT, ERROR }
    private enum class ReadStage { NONE, BASELINE, AFTER }
    private data class ReadResult(val type: ResultType, val data: ByteArray = byteArrayOf(), val nrc: Int? = null, val decoded: String = "")
    private data class SecurityResult(val positive: Boolean, val seed: ByteArray = byteArrayOf(), val nrc: Int? = null, val text: String = "")

    private val readDids = listOf(0xF903, 0xF923, 0xF925, 0xF927, 0xF938, 0xF99A, 0xF99B, 0xF99C)
    private val seedSubs = listOf(0x01, 0x03, 0x05, 0x07)
    private val handler = Handler(Looper.getMainLooper())
    private val logLines = CopyOnWriteArrayList<String>()
    private val before = linkedMapOf<Int, ReadResult>()
    private val after = linkedMapOf<Int, ReadResult>()
    private val security = linkedMapOf<Int, SecurityResult>()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var device: BluetoothDevice? = null
    @Volatile private var connected = false
    @Volatile private var txCredits = 0
    @Volatile private var fifoSubscribed = false
    @Volatile private var creditsSubscribed = false
    @Volatile private var openSent = false
    @Volatile private var statusSent = false
    @Volatile private var rhmiOpened = false
    @Volatile private var reading = false
    @Volatile private var readStage = ReadStage.NONE
    @Volatile private var readIndex = 0
    @Volatile private var seedIndex = 0
    @Volatile private var waiting = false
    @Volatile private var currentDid: Int? = null
    @Volatile private var pendingSession: Int? = null
    @Volatile private var pendingSecurity: Int? = null
    @Volatile private var requestGen = 0L
    @Volatile private var creditGen = 0L
    @Volatile private var extendedAccepted = false
    @Volatile private var finished = false

    fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!hasConnectPermission()) { log("ОШИБКА: нет BLUETOOTH_CONNECT"); return }
        closeGatt(); reset(); this.device = device
        log("========================================")
        log("TachoWatch — SECURITY ACCESS TEST-8B")
        log("Версия: $VERSION")
        log("Строгий TX-credit flow-control + recovery после TIMEOUT")
        log("UDS 0x27: только requestSeed 01/03/05/07; sendKey запрещён")
        log("НЕТ 0x2E; НЕТ записи деятельности/manual entry/карты")
        log("========================================")
        log("Устройство: ${safeName(device)}")
        try {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) else device.connectGatt(context, false, callback)
            log("connectGatt object=${if (gatt != null) "CREATED" else "NULL"}")
        } catch (e: Throwable) { error("connectGatt", e) }
    }

    private fun reset() {
        logLines.clear(); before.clear(); after.clear(); security.clear()
        connected=false; txCredits=0; fifoSubscribed=false; creditsSubscribed=false; openSent=false; statusSent=false; rhmiOpened=false
        reading=false; readStage=ReadStage.NONE; readIndex=0; seedIndex=0; waiting=false; currentDid=null; pendingSession=null; pendingSecurity=null
        extendedAccepted=false; finished=false; requestGen++; creditGen++
    }

    fun manualGattCheck() {
        log("===== MANUAL STATUS =====")
        log("connected=$connected RHMI=$rhmiOpened FIFO=$fifoSubscribed Credits=$creditsSubscribed TX=$txCredits")
        log("reading=$reading stage=$readStage read=$readIndex/${readDids.size} seed=$seedIndex/${seedSubs.size} waiting=$waiting")
        log("DID=${currentDid?.let(::hexDid) ?: "NONE"} session=${pendingSession?.let(::hexByte) ?: "NONE"} security=${pendingSecurity?.let(::hexByte) ?: "NONE"}")
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("CONNECTION status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected=true; gatt=g; notifyConnection(true, safeName(g.device))
                try { if (!g.requestMtu(512)) discover(g) } catch (_:Throwable) { discover(g) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected=false; waiting=false; requestGen++; creditGen++; notifyConnection(false, safeName(g.device)); log("BLE DISCONNECTED")
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { log("MTU=$mtu status=$status"); discover(g) }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("services status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) return
            if (g.getService(UUID_SERVICE)==null) { log("Diagnostics Service NOT FOUND"); return }
            subscribeFifo(g)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            val u=d.characteristic.uuid; log("CCCD $u status=$status")
            if (u==UUID_FIFO) { fifoSubscribed=status==BluetoothGatt.GATT_SUCCESS; handler.postDelayed({ subscribeCredits(g) },150) }
            else if (u==UUID_CREDITS) { creditsSubscribed=status==BluetoothGatt.GATT_SUCCESS; handler.postDelayed({ grantRxCredit(g,"INITIAL") },300) }
        }
        @Deprecated("legacy") override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) { @Suppress("DEPRECATION") handleIncoming(g,c,c.value ?: byteArrayOf()) }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { handleIncoming(g,c,value) }
    }

    @SuppressLint("MissingPermission") private fun discover(g:BluetoothGatt) { try { log("discoverServices=${g.discoverServices()}") } catch(e:Throwable){ error("discover",e) } }
    @SuppressLint("MissingPermission") private fun subscribeFifo(g:BluetoothGatt){ val c=g.getService(UUID_SERVICE)?.getCharacteristic(UUID_FIFO) ?: return; g.setCharacteristicNotification(c,true); c.getDescriptor(UUID_CCCD)?.let{ log("FIFO CCCD=${writeDesc(g,it,BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)}") } }
    @SuppressLint("MissingPermission") private fun subscribeCredits(g:BluetoothGatt){ val c=g.getService(UUID_SERVICE)?.getCharacteristic(UUID_CREDITS) ?: return; g.setCharacteristicNotification(c,true); c.getDescriptor(UUID_CCCD)?.let{ log("CREDITS CCCD=${writeDesc(g,it,BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)}") } }

    private fun grantRxCredit(g:BluetoothGatt,label:String){ val c=g.getService(UUID_SERVICE)?.getCharacteristic(UUID_CREDITS) ?: return; log("RX-CREDIT [$label] write=${writeChar(g,c,byteArrayOf(1))}") }

    private fun handleIncoming(g:BluetoothGatt,c:BluetoothGattCharacteristic,value:ByteArray){
        if(c.uuid==UUID_CREDITS){ if(value.isEmpty())return; val n=u(value[0]); if(n==0xFF){log("FLOW CONTROL REJECTED");return}; txCredits+=n; log("TX-CREDIT RX +$n => $txCredits"); creditGen++; pump(g); return }
        if(c.uuid!=UUID_FIFO || value.size<3)return
        val a=value.copyOfRange(2,value.size); log("RX APP=${hex(a)}")
        if(a.size>=4 && u(a[0])==0x71 && u(a[1])==0x01 && u(a[2])==0xF2 && u(a[3])==0x11){ log("OPEN RHMI POSITIVE"); grantRxCredit(g,"RHMI-STATUS"); handler.postDelayed({ sendRhmiStatus(g) },400); return }
        if(a.size>=5 && u(a[0])==0x71 && u(a[1])==0x03 && u(a[2])==0xF2 && u(a[3])==0x11){ val s=u(a[4]); log("RHMI STATUS=0x${hexByte(s)}"); if(s==0x10&&!rhmiOpened){rhmiOpened=true; log(">>> RHMI OPENED <<<"); handler.postDelayed({startRead(g,ReadStage.BASELINE)},300)}; return }
        if(a.isNotEmpty() && u(a[0])==0x50){ sessionPositive(g,a); return }
        if(a.isNotEmpty() && u(a[0])==0x67){ securityPositive(g,a); return }
        if(a.size>=3 && u(a[0])==0x62){ val did=(u(a[1]) shl 8) or u(a[2]); val data=if(a.size>3)a.copyOfRange(3,a.size) else byteArrayOf(); readPositive(g,did,data); return }
        if(a.size>=3 && u(a[0])==0x7F){ val svc=u(a[1]); val nrc=u(a[2]); when{ svc==0x22&&waiting&&currentDid!=null->{ val did=currentDid!!; store(did,ReadResult(ResultType.NRC,nrc=nrc,decoded=nrcName(nrc))); log("${hexDid(did)} NRC 0x${hexByte(nrc)} ${nrcName(nrc)}"); completeRead(g) }; svc==0x10&&waiting&&pendingSession!=null->sessionNegative(g,nrc); svc==0x27&&waiting&&pendingSecurity!=null->securityNegative(g,nrc) } }
    }

    private fun pump(g:BluetoothGatt){ if(!connected||finished||waiting||txCredits<=0)return; when{ !openSent->sendOpen(g); reading&&readIndex<readDids.size->sendRead(g); pendingSession!=null->sendSession(g,pendingSession!!); pendingSecurity!=null->sendSecurity(g,pendingSecurity!!) } }

    private fun waitCredit(g:BluetoothGatt,label:String,ready:()->Unit){
        if(finished)return; if(txCredits>0){ready();return}; val gen=++creditGen; log("WAIT TX-CREDIT [$label]"); grantRxCredit(g,"RECOVERY-$label")
        handler.postDelayed({ if(gen==creditGen&&txCredits==0)grantRxCredit(g,"RECOVERY2-$label") },1200)
        handler.postDelayed({ if(finished||gen!=creditGen)return@postDelayed; if(txCredits>0)ready() else {log("TX-CREDIT RECOVERY FAILED [$label]"); finish("Flow-control stalled before $label")} },CREDIT_WAIT_MS)
    }

    private fun sendOpen(g:BluetoothGatt){ if(openSent||txCredits<=0)return; val f=fifo(g)?:return; val ok=writeChar(g,f,byteArrayOf(1,1,0x31,1,0xF2.toByte(),0x11)); log("OPEN write=$ok"); if(ok){openSent=true;txCredits--} }
    private fun sendRhmiStatus(g:BluetoothGatt){ waitCredit(g,"RHMI STATUS"){ if(statusSent)return@waitCredit; val f=fifo(g)?:return@waitCredit; val ok=writeChar(g,f,byteArrayOf(1,1,0x31,3,0xF2.toByte(),0x11)); log("RHMI status write=$ok"); if(ok){statusSent=true;txCredits--} } }

    private fun startRead(g:BluetoothGatt,stage:ReadStage){ if(finished)return; readStage=stage;reading=true;readIndex=0;waiting=false;currentDid=null;log(if(stage==ReadStage.BASELINE)"===== PHASE A BASELINE =====" else "===== PHASE D AFTER SECURITY ====="); nextRead(g) }
    private fun nextRead(g:BluetoothGatt){ if(finished||!reading||waiting)return; if(readIndex>=readDids.size){finishRead(g);return}; val did=readDids[readIndex]; waitCredit(g,"READ ${hexDid(did)}"){sendRead(g)} }
    private fun sendRead(g:BluetoothGatt){ if(finished||waiting||txCredits<=0||readIndex>=readDids.size)return; val did=readDids[readIndex]; val f=fifo(g)?:return; log("TX UDS 22 ${hexDid(did)} creditBefore=$txCredits"); val ok=writeChar(g,f,byteArrayOf(1,1,0x22,(did shr 8).toByte(),did.toByte())); if(!ok){store(did,ReadResult(ResultType.ERROR,decoded="WRITE FAILED"));readIndex++;nextRead(g);return};txCredits--;waiting=true;currentDid=did;timeout(g,"DID ${hexDid(did)}") }
    private fun readPositive(g:BluetoothGatt,did:Int,data:ByteArray){ val dec=decodeDid(did,data); store(did,ReadResult(ResultType.POSITIVE,data.copyOf(),decoded=dec));log("${hexDid(did)} POSITIVE RAW=${hex(data)} | $dec");if(waiting&&currentDid==did)completeRead(g) }
    private fun completeRead(g:BluetoothGatt){requestGen++;waiting=false;currentDid=null;readIndex++;grantRxCredit(g,"AFTER-DID");handler.postDelayed({nextRead(g)},NEXT_DELAY_MS)}
    private fun store(did:Int,r:ReadResult){if(readStage==ReadStage.BASELINE)before[did]=r else if(readStage==ReadStage.AFTER)after[did]=r}
    private fun finishRead(g:BluetoothGatt){reading=false;waiting=false;currentDid=null;requestGen++;val m=if(readStage==ReadStage.BASELINE)before else after;log(if(readStage==ReadStage.BASELINE)"----- BASELINE SUMMARY -----" else "----- AFTER SUMMARY -----");readDids.forEach{log("${hexDid(it)}=${resultText(m[it])}")};if(readStage==ReadStage.BASELINE)requestSession(g,3) else requestSession(g,1)}

    private fun requestSession(g:BluetoothGatt,s:Int){pendingSession=s;waiting=false;waitCredit(g,"SESSION 10 ${hexByte(s)}"){sendSession(g,s)}}
    private fun sendSession(g:BluetoothGatt,s:Int){if(finished||waiting||txCredits<=0||pendingSession!=s)return;val f=fifo(g)?:return;log("TX UDS 10 ${hexByte(s)} creditBefore=$txCredits");val ok=writeChar(g,f,byteArrayOf(1,1,0x10,s.toByte()));log("session write=$ok");if(!ok){pendingSession=null;finish("Session write failed");return};txCredits--;waiting=true;timeout(g,"SESSION ${hexByte(s)}")}
    private fun sessionPositive(g:BluetoothGatt,a:ByteArray){val s=pendingSession?:return;if(!waiting||a.size<2||(u(a[1]) and 0x7F)!=(s and 0x7F))return;requestGen++;waiting=false;pendingSession=null;log("SESSION ${hexByte(s)} POSITIVE");grantRxCredit(g,"AFTER-SESSION");if(s==3){extendedAccepted=true;handler.postDelayed({startSecurity(g)},400)}else finish("Default session restored")}
    private fun sessionNegative(g:BluetoothGatt,nrc:Int){val s=pendingSession?:return;requestGen++;waiting=false;pendingSession=null;log("SESSION ${hexByte(s)} NRC 0x${hexByte(nrc)} ${nrcName(nrc)}");finish("Session rejected")}

    private fun startSecurity(g:BluetoothGatt){seedIndex=0;log("===== PHASE C SECURITY REQUEST-SEED =====");nextSecurity(g)}
    private fun nextSecurity(g:BluetoothGatt){if(finished)return;if(seedIndex>=seedSubs.size){finishSecurity(g);return};val s=seedSubs[seedIndex];pendingSecurity=s;waiting=false;waitCredit(g,"SECURITY 27 ${hexByte(s)}"){sendSecurity(g,s)}}
    private fun sendSecurity(g:BluetoothGatt,s:Int){if(finished||waiting||txCredits<=0||pendingSecurity!=s)return;if((s and 1)==0){security[s]=SecurityResult(false,text="BLOCKED");pendingSecurity=null;seedIndex++;nextSecurity(g);return};val f=fifo(g)?:return;log("TX UDS 27 ${hexByte(s)} requestSeed creditBefore=$txCredits");val ok=writeChar(g,f,byteArrayOf(1,1,0x27,s.toByte()));log("security write=$ok");if(!ok){security[s]=SecurityResult(false,text="WRITE FAILED");pendingSecurity=null;seedIndex++;nextSecurity(g);return};txCredits--;waiting=true;timeout(g,"SECURITY 27 ${hexByte(s)}")}
    private fun securityPositive(g:BluetoothGatt,a:ByteArray){val s=pendingSecurity?:return;if(!waiting||a.size<2||u(a[1])!=s)return;requestGen++;waiting=false;pendingSecurity=null;val seed=if(a.size>2)a.copyOfRange(2,a.size)else byteArrayOf();security[s]=SecurityResult(true,seed.copyOf(),text=if(seed.isNotEmpty()&&seed.all{u(it)==0})"ZERO SEED" else "seedLen=${seed.size}");log("27 ${hexByte(s)} POSITIVE SEED=${hex(seed)}");log("КЛЮЧ НЕ ОТПРАВЛЯЕТСЯ");grantRxCredit(g,"AFTER-SECURITY");seedIndex++;handler.postDelayed({nextSecurity(g)},NEXT_DELAY_MS)}
    private fun securityNegative(g:BluetoothGatt,nrc:Int){val s=pendingSecurity?:return;requestGen++;waiting=false;pendingSecurity=null;security[s]=SecurityResult(false,nrc=nrc,text=nrcName(nrc));log("27 ${hexByte(s)} NRC 0x${hexByte(nrc)} ${nrcName(nrc)}");grantRxCredit(g,"AFTER-SECURITY-NRC");seedIndex++;handler.postDelayed({nextSecurity(g)},NEXT_DELAY_MS)}
    private fun finishSecurity(g:BluetoothGatt){log("----- SECURITY SUMMARY -----");seedSubs.forEach{log("27 ${hexByte(it)}=${securityText(security[it])}")};handler.postDelayed({startRead(g,ReadStage.AFTER)},400)}

    private fun timeout(g:BluetoothGatt,label:String){requestGen++;val gen=requestGen;handler.postDelayed({if(finished||gen!=requestGen||!waiting)return@postDelayed;requestGen++;waiting=false;log("$label TIMEOUT -> flow recovery");grantRxCredit(g,"TIMEOUT-RECOVERY");when{currentDid!=null->{val d=currentDid!!;currentDid=null;store(d,ReadResult(ResultType.TIMEOUT,decoded="TIMEOUT"));readIndex++;handler.postDelayed({nextRead(g)},800)};pendingSession!=null->{val s=pendingSession!!;pendingSession=null;finish("No response to session ${hexByte(s)}")};pendingSecurity!=null->{val s=pendingSecurity!!;pendingSecurity=null;security[s]=SecurityResult(false,text="TIMEOUT");seedIndex++;handler.postDelayed({nextSecurity(g)},800)}}},RESPONSE_TIMEOUT_MS)}

    private fun finish(note:String){if(finished)return;finished=true;reading=false;waiting=false;requestGen++;creditGen++;log("========================================");log("===== SECURITY ACCESS TEST-8B RESULT =====");log("Extended 10 03 accepted=$extendedAccepted");log(note);log("SECURITY REQUEST-SEED RESULTS:");seedSubs.forEach{log("27 ${hexByte(it)}=${securityText(security[it])}")};log("TARGET COMPARISON:");listOf(0xF99A,0xF99B,0xF99C).forEach{log("${hexDid(it)} BEFORE=${resultText(before[it])} | AFTER=${resultText(after[it])}")};log("CONTROL COMPARISON:");listOf(0xF903,0xF923,0xF925,0xF927,0xF938).forEach{log("${hexDid(it)} BEFORE=${resultText(before[it])} | AFTER=${resultText(after[it])}")};log("PHOTO: TX UDS 10/27 + TX-CREDIT RX + RESULT");log("========================================")}

    private fun resultText(r:ReadResult?):String=when(r?.type){ResultType.POSITIVE->"POSITIVE ${hex(r.data)} (${r.decoded})";ResultType.NRC->"NRC 0x${hexByte(r.nrc?:0)} ${r.decoded}";ResultType.TIMEOUT->"TIMEOUT";ResultType.ERROR->"ERROR ${r.decoded}";null->"NO RESULT"}
    private fun securityText(r:SecurityResult?):String=when{r==null->"NO RESULT";r.positive->"POSITIVE seed=${hex(r.seed)} ${r.text}";r.nrc!=null->"NRC 0x${hexByte(r.nrc)} ${r.text}";else->r.text}
    private fun decodeDid(d:Int,b:ByteArray):String=when(d){0xF903->if(b.isEmpty())"EMPTY" else "${u(b[0])} — "+when(u(b[0])){0->"ОТДЫХ / ПЕРЕРЫВ";1->"ГОТОВНОСТЬ";2->"ДРУГАЯ РАБОТА";3->"ВОЖДЕНИЕ";else->"НЕИЗВЕСТНО"};0xF923,0xF925,0xF927,0xF938,0xF99A,0xF99B,0xF99C->if(b.size<2)"len=${b.size}" else {val m=(u(b[0]) shl 8)or u(b[1]);"$m min = ${m/60}:${"%02d".format(Locale.US,m%60)}"};else->"len=${b.size}"}
    private fun didTitle(d:Int)=when(d){0xF903->"Driver1WorkingState";0xF923->"Driver1ContinuousDrivingTime";0xF925->"Driver1CumulativeBreakTime";0xF927->"Driver1CurrentActivityDuration";0xF938->"Driver1PreviousAndCurrentWeekDriving";0xF99A->"Driver1CurrentDailyDrivingTime";0xF99B->"Driver1CurrentWeeklyDrivingTime";0xF99C->"Driver1TimeLeftUntilNewDailyRestPeriod";else->"UNKNOWN"}
    private fun nrcName(n:Int)=when(n){0x10->"generalReject";0x11->"serviceNotSupported";0x12->"subFunctionNotSupported";0x13->"incorrectMessageLengthOrInvalidFormat";0x21->"busyRepeatRequest";0x22->"conditionsNotCorrect";0x24->"requestSequenceError";0x31->"requestOutOfRange";0x33->"securityAccessDenied";0x35->"invalidKey";0x36->"exceedNumberOfAttempts";0x37->"requiredTimeDelayNotExpired";0x78->"responsePending";0x7E->"subFunctionNotSupportedInActiveSession";0x7F->"serviceNotSupportedInActiveSession";else->"UNKNOWN NRC"}
    private fun fifo(g:BluetoothGatt)=g.getService(UUID_SERVICE)?.getCharacteristic(UUID_FIFO)
    private fun u(b:Byte)=b.toInt() and 0xFF
    private fun hexByte(i:Int)="%02X".format(Locale.US,i and 0xFF)
    private fun hexDid(i:Int)="%04X".format(Locale.US,i and 0xFFFF)
    private fun hex(b:ByteArray)=if(b.isEmpty())"(empty)" else b.joinToString(" "){"%02X".format(Locale.US,u(it))}

    @SuppressLint("MissingPermission") private fun writeDesc(g:BluetoothGatt,d:BluetoothGattDescriptor,v:ByteArray):Boolean=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU)g.writeDescriptor(d,v)==BluetoothGatt.GATT_SUCCESS else {@Suppress("DEPRECATION") run{d.value=v;g.writeDescriptor(d)}}
    @SuppressLint("MissingPermission") private fun writeChar(g:BluetoothGatt,c:BluetoothGattCharacteristic,v:ByteArray):Boolean=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU)g.writeCharacteristic(c,v,BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)==BluetoothGatt.GATT_SUCCESS else {@Suppress("DEPRECATION") run{c.writeType=BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;c.value=v;g.writeCharacteristic(c)}}

    fun disconnect(){connected=false;waiting=false;requestGen++;creditGen++;log("Ручное отключение");closeGatt();notifyConnection(false,device?.let(::safeName))}
    @SuppressLint("MissingPermission") private fun closeGatt(){val x=gatt;gatt=null;if(x!=null){try{x.disconnect()}catch(_:Throwable){};try{x.close()}catch(_:Throwable){}}}
    fun clearLog(){logLines.clear();log("Журнал очищен")}
    fun getLog():String=logLines.joinToString("\n")
    private fun log(s:String){val t=SimpleDateFormat("HH:mm:ss.SSS",Locale.getDefault()).format(Date());logLines.add("[$t] $s");while(logLines.size>MAX_LOG_LINES)try{logLines.removeAt(0)}catch(_:Throwable){break};val all=getLog();handler.post{try{listener?.onLogChanged(all)}catch(_:Throwable){}}}
    private fun notifyConnection(x:Boolean,n:String?){handler.post{try{listener?.onConnectionStateChanged(x,n)}catch(_:Throwable){}}}
    private fun error(p:String,e:Throwable){log("ОШИБКА [$p]: ${e.javaClass.simpleName}: ${e.message ?: ""}")}
    @SuppressLint("MissingPermission") private fun safeName(d:BluetoothDevice)=try{d.name?:"Без имени"}catch(_:Throwable){"Нет доступа"}
}
