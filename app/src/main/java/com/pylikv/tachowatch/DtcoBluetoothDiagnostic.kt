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
        private const val VERSION = "BLE-RHMI-READONLY-CARD-DATA-TEST-9D"
        private const val RESPONSE_TIMEOUT_MS = 3000L
        private const val NEXT_DELAY_MS = 220L
        private const val RECONNECT_DELAY_MS = 2500L
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val SERVICE = UUID.fromString("fa213def-aef4-475c-bcea-0a8d69073efc")
        private val FIFO = UUID.fromString("e413960c-75ba-4ca9-8a67-99bc052a1b13")
        private val CREDITS = UUID.fromString("e168d1a6-304f-42b4-ab96-4cd1d4efebd9")
    }

    private enum class T { POSITIVE, NRC, TIMEOUT, ERROR }
    private data class R(val type: T, val data: ByteArray = byteArrayOf(), val nrc: Int? = null, val text: String = "")

    private val dids = (0xF900..0xF9FF).toList()
    private val known = setOf(0xF903, 0xF916, 0xF921, 0xF923, 0xF925, 0xF927, 0xF938)
    private val handler = Handler(Looper.getMainLooper())
    private val lines = CopyOnWriteArrayList<String>()
    private val results = linkedMapOf<Int, R>()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var device: BluetoothDevice? = null
    @Volatile private var connected = false
    @Volatile private var credits = 0
    @Volatile private var fifoSub = false
    @Volatile private var creditSub = false
    @Volatile private var openSent = false
    @Volatile private var statusSent = false
    @Volatile private var rhmi = false
    @Volatile private var scanning = false
    @Volatile private var index = 0
    @Volatile private var waiting = false
    @Volatile private var currentDid: Int? = null
    @Volatile private var reqGen = 0L
    @Volatile private var finished = false
    @Volatile private var reconnectUsed = false

    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun connect(d: BluetoothDevice) {
        if (!hasConnectPermission()) { log("ОШИБКА: нет BLUETOOTH_CONNECT"); return }
        closeGatt(); resetAll(); device = d
        log("========================================")
        log("TachoWatch — TEST-9D CARD DATA SEARCH")
        log("Версия: $VERSION")
        log("READ-ONLY: только UDS 0x22, диапазон F900-F9FF")
        log("STATUS-19: полный close GATT + один контролируемый reconnect")
        log("Стартовый transport без принудительного bootstrap кредита")
        log("НЕТ 0x27; НЕТ 0x2E; НЕТ записи карты/деятельности")
        log("========================================")
        connectGattNow(d, false)
    }

    private fun resetAll() {
        lines.clear(); results.clear(); connected=false; credits=0; fifoSub=false; creditSub=false
        openSent=false; statusSent=false; rhmi=false; scanning=false; index=0; waiting=false
        currentDid=null; finished=false; reconnectUsed=false; reqGen++
    }

    private fun resetTransport() {
        connected=false; credits=0; fifoSub=false; creditSub=false; openSent=false; statusSent=false
        rhmi=false; scanning=false; waiting=false; currentDid=null; reqGen++
    }

    @SuppressLint("MissingPermission")
    private fun connectGattNow(d: BluetoothDevice, retry: Boolean) {
        log(if (retry) "RECONNECT: creating fresh BluetoothGatt" else "Устройство: ${safeName(d)}")
        try {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                d.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
            else d.connectGatt(context, false, cb)
            log("connectGatt object=${if (gatt != null) "CREATED" else "NULL"}")
        } catch (e: Throwable) { err("connectGatt", e) }
    }

    fun manualGattCheck() {
        log("===== MANUAL STATUS =====")
        log("connected=$connected RHMI=$rhmi FIFO=$fifoSub Credits=$creditSub TX=$credits reconnectUsed=$reconnectUsed")
        log("scan=$index/${dids.size} scanning=$scanning waiting=$waiting DID=${currentDid?.let(::hd) ?: "NONE"}")
        log("positive=${results.values.count{it.type==T.POSITIVE}} nrc=${results.values.count{it.type==T.NRC}} timeout=${results.values.count{it.type==T.TIMEOUT}}")
    }

    private val cb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("CONNECTION status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected=true; gatt=g; notifyConnection(true, safeName(g.device))
                try { if (!g.requestMtu(512)) discover(g) } catch (_:Throwable) { discover(g) }
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected=false; waiting=false; reqGen++; notifyConnection(false, safeName(g.device))
                log("BLE DISCONNECTED status=$status")
                if (status == 19 && !reconnectUsed && !finished) {
                    reconnectUsed = true
                    log("STATUS 19 confirmed: peer terminated connection")
                    try { g.close() } catch (_:Throwable) {}
                    if (gatt === g) gatt = null
                    resetTransport()
                    val d = device
                    if (d != null) {
                        log("RECONNECT #1 scheduled after ${RECONNECT_DELAY_MS} ms")
                        handler.postDelayed({ if (!finished) connectGattNow(d, true) }, RECONNECT_DELAY_MS)
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU=$mtu status=$status"); discover(g)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("services status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) return
            if (g.getService(SERVICE) == null) { log("Diagnostics Service NOT FOUND"); return }
            subscribe(g, FIFO)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            val u = d.characteristic.uuid
            log("CCCD $u status=$status")
            if (u == FIFO) {
                fifoSub = status == BluetoothGatt.GATT_SUCCESS
                handler.postDelayed({ if (connected) subscribe(g, CREDITS) }, 150)
            } else if (u == CREDITS) {
                creditSub = status == BluetoothGatt.GATT_SUCCESS
                if (creditSub) {
                    log("CREDITS subscribed; waiting for genuine TX-credit")
                    handler.postDelayed({ if (connected && credits == 0 && !openSent) grant(g, "INITIAL") }, 300)
                } else stop("CREDITS CCCD subscription failed")
            }
        }

        @Deprecated("legacy")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION") incoming(g, c, c.value ?: byteArrayOf())
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            incoming(g, c, value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun discover(g: BluetoothGatt) {
        try { log("discoverServices=${g.discoverServices()}") } catch (e:Throwable) { err("discover", e) }
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(g: BluetoothGatt, uuid: UUID) {
        val c = g.getService(SERVICE)?.getCharacteristic(uuid) ?: return
        g.setCharacteristicNotification(c, true)
        c.getDescriptor(CCCD)?.let {
            log("${if(uuid==FIFO)"FIFO" else "CREDITS"} CCCD=${writeDesc(g,it,BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)}")
        }
    }

    private fun grant(g: BluetoothGatt, label: String) {
        val c = g.getService(SERVICE)?.getCharacteristic(CREDITS) ?: return
        log("RX-CREDIT [$label] write=${writeChar(g,c,byteArrayOf(1))}")
    }

    private fun incoming(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) {
        if (c.uuid == CREDITS) {
            if (v.isEmpty()) return
            val n = u(v[0]); if (n == 0xFF) { log("FLOW CONTROL REJECTED"); return }
            credits += n; log("TX-CREDIT RX +$n => $credits"); pump(g); return
        }
        if (c.uuid != FIFO || v.size < 3) return
        val a = v.copyOfRange(2, v.size); log("RX APP=${hex(a)}")
        if (a.size>=4 && u(a[0])==0x71 && u(a[1])==1 && u(a[2])==0xF2 && u(a[3])==0x11) {
            log("OPEN RHMI POSITIVE"); grant(g,"RHMI-STATUS")
            handler.postDelayed({ if (connected && credits>0) sendStatus(g) },400); return
        }
        if (a.size>=5 && u(a[0])==0x71 && u(a[1])==3 && u(a[2])==0xF2 && u(a[3])==0x11) {
            val s=u(a[4]); log("RHMI STATUS=0x${hb(s)}")
            if (s==0x10 && !rhmi) { rhmi=true; log(">>> RHMI OPENED <<<"); handler.postDelayed({startScan(g)},300) }
            return
        }
        if (a.size>=3 && u(a[0])==0x62) {
            val did=(u(a[1]) shl 8) or u(a[2]); val data=if(a.size>3)a.copyOfRange(3,a.size) else byteArrayOf()
            positive(g,did,data); return
        }
        if (a.size>=3 && u(a[0])==0x7F && u(a[1])==0x22 && waiting && currentDid!=null) {
            val did=currentDid!!; val n=u(a[2]); results[did]=R(T.NRC,nrc=n,text=nrc(n))
            log("${hd(did)} NRC 0x${hb(n)} ${nrc(n)}"); complete(g)
        }
    }

    private fun pump(g: BluetoothGatt) {
        if (!connected || finished || waiting || credits<=0) return
        when { !openSent -> sendOpen(g); scanning && index<dids.size -> sendRead(g) }
    }

    private fun sendOpen(g: BluetoothGatt) {
        if (openSent || credits<=0) return
        val f=fifo(g) ?: return
        val ok=writeChar(g,f,byteArrayOf(1,1,0x31,1,0xF2.toByte(),0x11))
        log("OPEN write=$ok"); if(ok){openSent=true;credits--}
    }

    private fun sendStatus(g: BluetoothGatt) {
        if (statusSent || credits<=0) return
        val f=fifo(g) ?: return
        val ok=writeChar(g,f,byteArrayOf(1,1,0x31,3,0xF2.toByte(),0x11))
        log("RHMI status write=$ok"); if(ok){statusSent=true;credits--}
    }

    private fun startScan(g: BluetoothGatt) {
        scanning=true;index=0;waiting=false;currentDid=null;results.clear()
        log("========================================");log("===== TEST-9D CARD DATA SEARCH START =====")
        log("Range F900-F9FF (${dids.size} DID), service 0x22 only");log("========================================");next(g)
    }

    private fun next(g: BluetoothGatt) {
        if(finished||!scanning||waiting)return
        if(index>=dids.size){summary();return}
        if(credits<=0){grant(g,"READ-${hd(dids[index])}");return}
        sendRead(g)
    }

    private fun sendRead(g: BluetoothGatt) {
        if(finished||waiting||credits<=0||index>=dids.size)return
        val did=dids[index]; val f=fifo(g)?:return
        log("TX UDS 22 ${hd(did)} [${index+1}/${dids.size}] creditBefore=$credits")
        val ok=writeChar(g,f,byteArrayOf(1,1,0x22,(did shr 8).toByte(),did.toByte()))
        if(!ok){results[did]=R(T.ERROR,text="WRITE FAILED");index++;handler.postDelayed({next(g)},NEXT_DELAY_MS);return}
        credits--;waiting=true;currentDid=did;timeout(g,did)
    }

    private fun positive(g:BluetoothGatt,did:Int,data:ByteArray){
        val text=decode(did,data);results[did]=R(T.POSITIVE,data.copyOf(),text=text)
        log("${hd(did)} POSITIVE len=${data.size} RAW=${hex(data)} | $text")
        if(waiting&&currentDid==did)complete(g)
    }

    private fun complete(g:BluetoothGatt){
        reqGen++;waiting=false;currentDid=null;index++;grant(g,"AFTER-DID")
        if(index%32==0)log("----- PROGRESS $index/${dids.size}; positive=${results.values.count{it.type==T.POSITIVE}} -----")
        handler.postDelayed({next(g)},NEXT_DELAY_MS)
    }

    private fun timeout(g:BluetoothGatt,did:Int){
        val gen=++reqGen
        handler.postDelayed({
            if(finished||gen!=reqGen||!waiting||currentDid!=did)return@postDelayed
            results[did]=R(T.TIMEOUT,text="TIMEOUT");log("${hd(did)} TIMEOUT")
            waiting=false;currentDid=null;index++;grant(g,"TIMEOUT-RECOVERY");handler.postDelayed({next(g)},500)
        },RESPONSE_TIMEOUT_MS)
    }

    private fun summary(){
        scanning=false;waiting=false;currentDid=null;reqGen++;finished=true
        val pos=results.filterValues{it.type==T.POSITIVE}
        log("");log("========================================");log("===== TEST-9D CARD DATA SEARCH RESULT =====")
        log("Scanned=${dids.size} POSITIVE=${pos.size} NRC=${results.values.count{it.type==T.NRC}} TIMEOUT=${results.values.count{it.type==T.TIMEOUT}} ERROR=${results.values.count{it.type==T.ERROR}}")
        log("----- KNOWN / CONTROL DID -----");known.sorted().forEach{d->results[d]?.let{log("${hd(d)}=${rt(it)}")}}
        log("----- ALL POSITIVE DID -----");pos.forEach{(d,r)->log("${hd(d)} len=${r.data.size} RAW=${hex(r.data)} | ${r.text}")}
        log("----- CARD DATA CANDIDATES -----")
        val cand=pos.filter{(d,r)->d !in known&&(r.data.size>=8||ratio(r.data)>=0.45||ascii(r.data).count{it.isLetter()}>=4)}
        if(cand.isEmpty())log("No automatic candidates. Все положительные DID выше сохранены RAW.")
        else cand.forEach{(d,r)->log("${hd(d)} len=${r.data.size} ASCII=\"${ascii(r.data)}\" RAW=${hex(r.data)}")}
        log("READ-ONLY TEST COMPLETE");log("========================================")
    }

    private fun decode(d:Int,b:ByteArray):String=when(d){
        0xF903->if(b.isEmpty())"activity no data" else when(u(b[0])){0->"0 - ОТДЫХ / ПЕРЕРЫВ";1->"1 - ГОТОВНОСТЬ";2->"2 - ДРУГАЯ РАБОТА";3->"3 - ВОЖДЕНИЕ";else->"activity=${u(b[0])}"}
        0xF916->"driver/card candidate ASCII=\"${ascii(b)}\""
        0xF923->mins(b,"continuous driving")
        0xF925->mins(b,"accumulated break")
        0xF927->mins(b,"current activity duration")
        0xF938->mins(b,"two-week driving")
        else->{val s=ascii(b);if(s.isNotBlank())"ASCII=\"$s\"" else if(b.size==2)"${(u(b[0])shl 8)or u(b[1])}" else "binary ${b.size} byte(s)"}
    }

    private fun mins(b:ByteArray,label:String):String{if(b.size<2)return "$label short";val m=(u(b[0])shl 8)or u(b[1]);return "$label: $m min = ${m/60}:${String.format(Locale.US,"%02d",m%60)}"}
    private fun ascii(b:ByteArray)=buildString{b.forEach{val x=u(it);append(if(x in 0x20..0x7E)x.toChar() else '.')}}.trim('.')
    private fun ratio(b:ByteArray)=if(b.isEmpty())0.0 else b.count{u(it) in 0x20..0x7E}.toDouble()/b.size
    private fun rt(r:R)=when(r.type){T.POSITIVE->"POSITIVE ${hex(r.data)} (${r.text})";T.NRC->"NRC 0x${hb(r.nrc?:0)} ${r.text}";T.TIMEOUT->"TIMEOUT";T.ERROR->"ERROR ${r.text}"}
    private fun nrc(n:Int)=when(n){0x10->"generalReject";0x11->"serviceNotSupported";0x12->"subFunctionNotSupported";0x13->"incorrectMessageLengthOrInvalidFormat";0x21->"busyRepeatRequest";0x22->"conditionsNotCorrect";0x31->"requestOutOfRange";0x33->"securityAccessDenied";0x78->"responsePending";else->"NRC"}

    private fun stop(reason:String){finished=true;scanning=false;waiting=false;reqGen++;log("===== TEST STOPPED =====");log(reason)}

    @SuppressLint("MissingPermission")
    fun disconnect(){try{gatt?.disconnect()}catch(_:Throwable){};closeGatt();connected=false;notifyConnection(false,device?.let(::safeName));log("Отключено пользователем")}

    @SuppressLint("MissingPermission")
    private fun closeGatt(){try{gatt?.close()}catch(_:Throwable){};gatt=null}

    fun clearLog(){lines.clear();notifyLog()}
    private fun fifo(g:BluetoothGatt)=g.getService(SERVICE)?.getCharacteristic(FIFO)

    @SuppressLint("MissingPermission")
    private fun writeChar(g:BluetoothGatt,c:BluetoothGattCharacteristic,v:ByteArray):Boolean=try{
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU)g.writeCharacteristic(c,v,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)==BluetoothGatt.GATT_SUCCESS
        else{@Suppress("DEPRECATION")c.value=v;@Suppress("DEPRECATION")g.writeCharacteristic(c)}
    }catch(_:Throwable){false}

    @SuppressLint("MissingPermission")
    private fun writeDesc(g:BluetoothGatt,d:BluetoothGattDescriptor,v:ByteArray):Boolean=try{
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU)g.writeDescriptor(d,v)==BluetoothGatt.GATT_SUCCESS
        else{@Suppress("DEPRECATION")d.value=v;@Suppress("DEPRECATION")g.writeDescriptor(d)}
    }catch(_:Throwable){false}

    private fun log(s:String){val t=SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(Date());lines.add("[$t] $s");while(lines.size>20000)lines.removeAt(0);notifyLog()}
    private fun notifyLog(){val s=lines.joinToString("\n");handler.post{listener?.onLogChanged(s)}}
    private fun notifyConnection(b:Boolean,n:String?){handler.post{listener?.onConnectionStateChanged(b,n)}}
    @SuppressLint("MissingPermission") private fun safeName(d:BluetoothDevice)=try{d.name?:d.address}catch(_:Throwable){"DTCO"}
    private fun err(w:String,e:Throwable){log("ERROR [$w] ${e.javaClass.simpleName}: ${e.message}")}
    private fun u(b:Byte)=b.toInt() and 0xFF
    private fun hex(b:ByteArray)=b.joinToString(" "){String.format(Locale.US,"%02X",u(it))}
    private fun hb(v:Int)=String.format(Locale.US,"%02X",v and 0xFF)
    private fun hd(v:Int)=String.format(Locale.US,"%04X",v and 0xFFFF)
}