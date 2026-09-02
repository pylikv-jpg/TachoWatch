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
        private const val VERSION = "BLE-RHMI-READONLY-TARGET34-TEST-10A"
        const val RESULT_MARKER = "===== TEST-10 TARGET DATA RESULT ====="
        private const val RESPONSE_TIMEOUT_MS = 3000L
        private const val NEXT_DELAY_MS = 220L
        private const val CREDIT_TO_REQUEST_DELAY_MS = 250L
        private const val RECONNECT_DELAY_MS = 2500L
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val SERVICE = UUID.fromString("fa213def-aef4-475c-bcea-0a8d69073efc")
        private val FIFO = UUID.fromString("e413960c-75ba-4ca9-8a67-99bc052a1b13")
        private val CREDITS = UUID.fromString("e168d1a6-304f-42b4-ab96-4cd1d4efebd9")
    }

    private enum class T { POSITIVE, NRC, TIMEOUT, ERROR }
    private data class R(val type: T, val data: ByteArray = byteArrayOf(), val nrc: Int? = null, val text: String = "")
    private data class D(val id: Int, val name: String, val kind: String = "raw")

    private val defs = listOf(
        D(0xF903, "Текущая деятельность водителя", "activity"),
        D(0xF906, "Предупреждение по времени водителя", "warning"),
        D(0xF907, "Наличие карты водителя", "byte"),
        D(0xF923, "Непрерывное вождение", "minutes"),
        D(0xF925, "Накопленный перерыв", "minutes"),
        D(0xF927, "Длительность текущей деятельности", "minutes"),
        D(0xF931, "Имя и фамилия водителя", "name72"),
        D(0xF938, "Вождение текущая + предыдущая неделя", "minutes"),
        D(0xF997, "Конец последнего суточного отдыха", "time"),
        D(0xF998, "Конец последнего недельного отдыха", "time"),
        D(0xF999, "Конец предпоследнего недельного отдыха", "time"),
        D(0xF99A, "Текущее дневное вождение", "minutes"),
        D(0xF99B, "Текущее недельное вождение", "minutes"),
        D(0xF99C, "До обязательного нового суточного отдыха", "minutes"),
        D(0xF9A0, "Использовано продлений дневного вождения >9ч", "count"),
        D(0xF9A1, "До нового недельного отдыха", "minutes"),
        D(0xF9A2, "Непрерывно накопленный отдых", "minutes"),
        D(0xF9A3, "Минимально требуемый суточный отдых", "minutes"),
        D(0xF9A4, "Минимально требуемый недельный отдых", "minutes"),
        D(0xF9A5, "Максимальная длительность текущего суточного периода", "minutes"),
        D(0xF9A6, "Допустимое дневное вождение", "minutes"),
        D(0xF9AB, "Использовано сокращённых суточных отдыхов", "count"),
        D(0xF9AD, "Оставшееся непрерывное вождение", "minutes"),
        D(0xF9AF, "Оставшееся вождение текущей смены", "minutes"),
        D(0xF9B1, "Остаток недельного вождения", "minutes"),
        D(0xF9B3, "Остаток двухнедельного вождения", "minutes"),
        D(0xF9B5, "До следующего допустимого периода вождения", "minutes"),
        D(0xF9B7, "Длительность следующего допустимого периода вождения", "minutes"),
        D(0xF9B9, "Следующий необходимый перерыв/отдых", "minutes"),
        D(0xF9C0, "Остаток текущего break/rest", "minutes"),
        D(0xF9C2, "До следующего обязательного break/rest", "minutes"),
        D(0xF9C7, "Открытая компенсация: последняя неделя", "minutes"),
        D(0xF9C9, "Открытая компенсация: неделя -1", "minutes"),
        D(0xF9CB, "Открытая компенсация: неделя -2", "minutes")
    )
    private val dids = defs.map { it.id }
    private val defMap = defs.associateBy { it.id }

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
    @Volatile private var nextGen = 0L
    @Volatile private var finished = false
    @Volatile private var reconnectUsed = false

    fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun connect(d: BluetoothDevice) {
        if (!hasConnectPermission()) { log("ОШИБКА: нет BLUETOOTH_CONNECT"); return }
        closeGatt(); reset(); device = d
        log("========================================")
        log("TachoWatch — TEST-10A TARGET 34 DID")
        log("Версия: $VERSION")
        log("READ-ONLY: только UDS 0x22")
        log("Цель: только данные конечного TachoWatch, ${dids.size} DID")
        log("Транспорт TEST-9H сохранён: FIFO/CREDITS NO_RESPONSE")
        log("Flow: RX-credit -> ${CREDIT_TO_REQUEST_DELAY_MS} ms -> UDS request")
        log("НЕТ 0x27; НЕТ 0x2E; НЕТ записи карты/деятельности")
        log("========================================")
        connectGattNow(d, false)
    }

    private fun reset() {
        lines.clear(); results.clear(); connected=false; credits=0; fifoSub=false; creditSub=false
        openSent=false; statusSent=false; rhmi=false; scanning=false; index=0; waiting=false
        currentDid=null; finished=false; reconnectUsed=false; reqGen++; nextGen++
    }

    private fun resetTransport() {
        connected=false; credits=0; fifoSub=false; creditSub=false; openSent=false; statusSent=false
        rhmi=false; scanning=false; waiting=false; currentDid=null; reqGen++; nextGen++
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
    }

    private val cb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("CONNECTION status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected=true; gatt=g; notifyConnection(true, safeName(g.device))
                try { if (!g.requestMtu(512)) discover(g) } catch (_: Throwable) { discover(g) }
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected=false; waiting=false; reqGen++; nextGen++; notifyConnection(false, safeName(g.device))
                log("BLE DISCONNECTED status=$status")
                if (status == 19 && !reconnectUsed && !finished) {
                    reconnectUsed=true
                    log("STATUS 19: peer terminated connection; one fresh-GATT retry")
                    try { g.close() } catch (_: Throwable) {}
                    if (gatt === g) gatt=null
                    resetTransport()
                    device?.let { dev -> handler.postDelayed({ if (!finished) connectGattNow(dev, true) }, RECONNECT_DELAY_MS) }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { log("MTU=$mtu status=$status"); discover(g) }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("services status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val s = g.getService(SERVICE) ?: run { log("Diagnostics Service NOT FOUND"); return }
            logChar("FIFO", s.getCharacteristic(FIFO)); logChar("CREDITS", s.getCharacteristic(CREDITS)); subscribe(g, FIFO)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            val u=d.characteristic.uuid; log("CCCD $u status=$status")
            if (u == FIFO) {
                fifoSub = status == BluetoothGatt.GATT_SUCCESS
                handler.postDelayed({ if (connected) subscribe(g, CREDITS) }, 150)
            } else if (u == CREDITS) {
                creditSub = status == BluetoothGatt.GATT_SUCCESS
                if (creditSub) handler.postDelayed({ if (connected && credits == 0 && !openSent) grant(g, "INITIAL") }, 300)
                else stop("CREDITS CCCD subscription failed")
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            log("CHAR-WRITE uuid=${c.uuid} status=$status type=${writeTypeName(c.writeType)}")
        }

        @Deprecated("legacy")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION") incoming(g, c, c.value ?: byteArrayOf())
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { incoming(g, c, value) }
    }

    @SuppressLint("MissingPermission")
    private fun discover(g: BluetoothGatt) { try { log("discoverServices=${g.discoverServices()}") } catch (e: Throwable) { err("discover", e) } }

    private fun logChar(label: String, c: BluetoothGattCharacteristic?) {
        if (c == null) { log("$label characteristic NOT FOUND"); return }
        log("$label props=0x${Integer.toHexString(c.properties)} perms=0x${Integer.toHexString(c.permissions)} writeType=${writeTypeName(c.writeType)}")
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(g: BluetoothGatt, uuid: UUID) {
        val c=g.getService(SERVICE)?.getCharacteristic(uuid) ?: return
        log("${if(uuid==FIFO)"FIFO" else "CREDITS"} setCharacteristicNotification=${g.setCharacteristicNotification(c,true)}")
        c.getDescriptor(CCCD)?.let { log("${if(uuid==FIFO)"FIFO" else "CREDITS"} CCCD=${writeDesc(g,it,BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)}") }
    }

    private fun grant(g: BluetoothGatt, label: String) {
        val c=g.getService(SERVICE)?.getCharacteristic(CREDITS) ?: return
        log("RX-CREDIT [$label] initiated=${writeNoResponse(g,c,byteArrayOf(1))}")
    }

    private fun incoming(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) {
        if (c.uuid == CREDITS) {
            if (v.isEmpty()) return
            val n=u(v[0]); if (n==0xFF) { log("FLOW CONTROL REJECTED"); return }
            credits += n; log("TX-CREDIT RX +$n => $credits")
            if (!openSent) pumpBootstrap(g)
            return
        }
        if (c.uuid != FIFO || v.size < 3) return
        val a=v.copyOfRange(2,v.size); log("RX APP=${hex(a)}")
        if (a.size>=4 && u(a[0])==0x71 && u(a[1])==1 && u(a[2])==0xF2 && u(a[3])==0x11) {
            log("OPEN RHMI POSITIVE"); grant(g,"RHMI-STATUS")
            handler.postDelayed({ if(connected && credits>0) sendStatus(g) },300); return
        }
        if (a.size>=5 && u(a[0])==0x71 && u(a[1])==3 && u(a[2])==0xF2 && u(a[3])==0x11) {
            val s=u(a[4]); log("RHMI STATUS=0x${hb(s)}")
            if(s==0x10 && !rhmi) { rhmi=true; log(">>> RHMI OPENED <<<"); handler.postDelayed({startScan(g)},300) }
            return
        }
        if(a.size>=3 && u(a[0])==0x62) {
            val did=(u(a[1]) shl 8) or u(a[2]); val data=if(a.size>3)a.copyOfRange(3,a.size) else byteArrayOf()
            positive(g,did,data); return
        }
        if(a.size>=3 && u(a[0])==0x7F && u(a[1])==0x22 && waiting && currentDid!=null) {
            val did=currentDid!!; val n=u(a[2]); results[did]=R(T.NRC,nrc=n,text=nrc(n))
            log("${hd(did)} ${defMap[did]?.name ?: ""} => NRC 0x${hb(n)} ${nrc(n)}"); complete(g)
        }
    }

    private fun pumpBootstrap(g: BluetoothGatt) { if(connected && !finished && credits>0 && !openSent) sendOpen(g) }

    private fun sendOpen(g: BluetoothGatt) {
        if(openSent || credits<=0) return
        val f=fifo(g) ?: return
        val ok=writeNoResponse(g,f,byteArrayOf(1,1,0x31,1,0xF2.toByte(),0x11))
        log("OPEN initiated=$ok")
        if(ok){openSent=true;credits--}
    }

    private fun sendStatus(g: BluetoothGatt) {
        if(statusSent || credits<=0) return
        val f=fifo(g) ?: return
        val ok=writeNoResponse(g,f,byteArrayOf(1,1,0x31,3,0xF2.toByte(),0x11))
        log("RHMI status initiated=$ok")
        if(ok){statusSent=true;credits--}
    }

    private fun startScan(g: BluetoothGatt) {
        scanning=true; index=0; waiting=false; currentDid=null; results.clear(); nextGen++
        log("========================================")
        log("===== TEST-10 TARGET DATA START =====")
        log("Target ${dids.size} documented DID, service 0x22 only")
        log("========================================")
        schedulePrepare(g,0,"START")
    }

    private fun schedulePrepare(g: BluetoothGatt, delay: Long, reason: String) {
        if(finished || !scanning) return
        val gen=++nextGen
        handler.postDelayed({ if(gen==nextGen && !finished && scanning) prepareNext(g,gen) },delay)
    }

    private fun prepareNext(g: BluetoothGatt, gen: Long) {
        if(gen!=nextGen || finished || !scanning || waiting) return
        if(index>=dids.size){summary();return}
        val did=dids[index]
        grant(g,"RX-${hd(did)}")
        val sendGen=++nextGen
        handler.postDelayed({
            if(sendGen!=nextGen || finished || !scanning || waiting || index>=dids.size) return@postDelayed
            if(dids[index]!=did) return@postDelayed
            if(credits<=0) { schedulePrepare(g,180,"WAIT-TX-CREDIT"); return@postDelayed }
            sendRead(g,did)
        },CREDIT_TO_REQUEST_DELAY_MS)
    }

    private fun sendRead(g: BluetoothGatt, did: Int) {
        if(finished || waiting || credits<=0 || index>=dids.size || dids[index]!=did) return
        nextGen++
        val f=fifo(g) ?: return
        val name=defMap[did]?.name ?: ""
        log("TX ${index+1}/${dids.size} 22 ${hd(did)} — $name")
        val ok=writeNoResponse(g,f,byteArrayOf(1,1,0x22,(did shr 8).toByte(),did.toByte()))
        if(!ok){results[did]=R(T.ERROR,text="WRITE FAILED");index++;schedulePrepare(g,NEXT_DELAY_MS,"WRITE-FAILED");return}
        credits--;waiting=true;currentDid=did;timeout(g,did)
    }

    private fun positive(g: BluetoothGatt, did: Int, data: ByteArray) {
        val text=decode(did,data); results[did]=R(T.POSITIVE,data.copyOf(),text=text)
        log("${hd(did)} ${defMap[did]?.name ?: ""} => OK len=${data.size} RAW=${hex(data)} | $text")
        if(waiting && currentDid==did) complete(g)
    }

    private fun complete(g: BluetoothGatt) {
        reqGen++; nextGen++; waiting=false; currentDid=null; index++
        schedulePrepare(g,NEXT_DELAY_MS,"DID-COMPLETE")
    }

    private fun timeout(g: BluetoothGatt, did: Int) {
        val gen=++reqGen
        handler.postDelayed({
            if(finished || gen!=reqGen || !waiting || currentDid!=did) return@postDelayed
            results[did]=R(T.TIMEOUT,text="TIMEOUT"); log("${hd(did)} ${defMap[did]?.name ?: ""} => TIMEOUT")
            waiting=false; currentDid=null; index++; nextGen++; schedulePrepare(g,500,"TIMEOUT")
        },RESPONSE_TIMEOUT_MS)
    }

    private fun summary() {
        scanning=false; waiting=false; currentDid=null; reqGen++; nextGen++; finished=true
        val pos=results.values.count{it.type==T.POSITIVE}
        val nrcs=results.values.count{it.type==T.NRC}
        val timeouts=results.values.count{it.type==T.TIMEOUT}
        val errors=results.values.count{it.type==T.ERROR}
        log(""); log("========================================"); log(RESULT_MARKER)
        log("Всего=${dids.size}  OK=$pos  NRC=$nrcs  TIMEOUT=$timeouts  ERROR=$errors")
        log("Формат: DID | статус | название | значение | RAW")
        defs.forEach { d ->
            val r=results[d.id]
            when(r?.type){
                T.POSITIVE -> log("${hd(d.id)} | OK | ${d.name} | ${r.text} | RAW=${hex(r.data)}")
                T.NRC -> log("${hd(d.id)} | NRC 0x${hb(r.nrc?:0)} ${r.text} | ${d.name}")
                T.TIMEOUT -> log("${hd(d.id)} | TIMEOUT | ${d.name}")
                T.ERROR -> log("${hd(d.id)} | ERROR ${r.text} | ${d.name}")
                null -> log("${hd(d.id)} | NO RESULT | ${d.name}")
            }
        }
        log("READ-ONLY TARGET TEST COMPLETE")
        log("========================================")
    }

    private fun decode(did:Int,b:ByteArray):String {
        val d=defMap[did] ?: return generic(b)
        return when(d.kind){
            "activity" -> if(b.isEmpty()) "нет данных" else when(u(b[0]) and 0x07){
                0 -> "ОТДЫХ / ПЕРЕРЫВ"
                1 -> "ГОТОВНОСТЬ"
                2 -> "ДРУГАЯ РАБОТА"
                3 -> "ВОЖДЕНИЕ"
                6 -> "ОШИБКА"
                7 -> "НЕДОСТУПНО"
                else -> "код=${u(b[0]) and 0x07}"
            }
            "warning" -> if(b.isEmpty()) "нет данных" else "warningCode=${u(b[0])} (0x${hb(u(b[0]))}, bits=${Integer.toBinaryString(u(b[0]) and 0x0F).padStart(4,'0')})"
            "byte" -> if(b.isEmpty()) "нет данных" else "значение=${u(b[0])}"
            "count" -> if(b.isEmpty()) "нет данных" else "количество=${u(b[0])}"
            "minutes" -> mins(b)
            "time" -> timeRaw(b)
            "name72" -> driverName(b)
            else -> generic(b)
        }
    }

    private fun mins(b:ByteArray):String {
        if(b.size<2) return "короткий ответ len=${b.size}"
        val m=(u(b[0]) shl 8) or u(b[1])
        return "$m мин = ${m/60}:${String.format(Locale.US,"%02d",m%60)}"
    }

    private fun timeRaw(b:ByteArray):String {
        if(b.isEmpty()) return "нет данных"
        val n=when(b.size){
            1 -> u(b[0]).toLong()
            2 -> ((u(b[0]) shl 8) or u(b[1])).toLong()
            4 -> ((u(b[0]).toLong() shl 24) or (u(b[1]).toLong() shl 16) or (u(b[2]).toLong() shl 8) or u(b[3]).toLong())
            else -> -1L
        }
        return if(n>=0) "raw-time-value=$n; декодирование после проверки формата" else "time RAW=${hex(b)}"
    }

    private fun driverName(b:ByteArray):String {
        if(b.isEmpty()) return "нет данных"
        if(b.size>=72){
            val surname=cleanText(b.copyOfRange(0,36))
            val first=cleanText(b.copyOfRange(36,72))
            return "фамилия=\"$surname\"; имя=\"$first\""
        }
        return "TEXT=\"${cleanText(b)}\" len=${b.size}"
    }

    private fun cleanText(b:ByteArray)=buildString {
        b.forEach { val x=u(it); append(if(x in 0x20..0x7E) x.toChar() else if(x==0) ' ' else '.') }
    }.trim().trimEnd('.')

    private fun generic(b:ByteArray):String {
        val s=cleanText(b)
        return if(s.any{it.isLetterOrDigit()}) "TEXT=\"$s\"" else "binary ${b.size} byte(s)"
    }

    private fun nrc(n:Int)=when(n){
        0x10->"generalReject";0x11->"serviceNotSupported";0x12->"subFunctionNotSupported";
        0x13->"incorrectMessageLengthOrInvalidFormat";0x21->"busyRepeatRequest";
        0x22->"conditionsNotCorrect";0x31->"requestOutOfRange";0x33->"securityAccessDenied";
        0x78->"responsePending";else->"NRC"
    }

    private fun stop(reason:String){finished=true;scanning=false;waiting=false;reqGen++;nextGen++;log("===== TEST STOPPED =====");log(reason)}
    @SuppressLint("MissingPermission") fun disconnect(){try{gatt?.disconnect()}catch(_:Throwable){};closeGatt();connected=false;nextGen++;notifyConnection(false,device?.let(::safeName));log("Отключено пользователем")}
    @SuppressLint("MissingPermission") private fun closeGatt(){try{gatt?.close()}catch(_:Throwable){};gatt=null}
    fun clearLog(){lines.clear();notifyLog()}
    private fun fifo(g:BluetoothGatt)=g.getService(SERVICE)?.getCharacteristic(FIFO)

    @SuppressLint("MissingPermission")
    private fun writeNoResponse(g:BluetoothGatt,c:BluetoothGattCharacteristic,v:ByteArray):Boolean=try{
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU)g.writeCharacteristic(c,v,BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)==BluetoothGatt.GATT_SUCCESS
        else{@Suppress("DEPRECATION")c.writeType=BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;@Suppress("DEPRECATION")c.value=v;@Suppress("DEPRECATION")g.writeCharacteristic(c)}
    }catch(e:Throwable){err("writeNoResponse ${c.uuid}",e);false}

    private fun writeTypeName(t:Int)=when(t){BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT->"DEFAULT";BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE->"NO_RESPONSE";BluetoothGattCharacteristic.WRITE_TYPE_SIGNED->"SIGNED";else->"$t"}
    @SuppressLint("MissingPermission") private fun writeDesc(g:BluetoothGatt,d:BluetoothGattDescriptor,v:ByteArray):Boolean=try{if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU)g.writeDescriptor(d,v)==BluetoothGatt.GATT_SUCCESS else{@Suppress("DEPRECATION")d.value=v;@Suppress("DEPRECATION")g.writeDescriptor(d)}}catch(_:Throwable){false}

    private fun log(s:String){val t=SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(Date());lines.add("[$t] $s");while(lines.size>5000)lines.removeAt(0);notifyLog()}
    private fun notifyLog(){val s=lines.joinToString("\n");handler.post{listener?.onLogChanged(s)}}
    private fun notifyConnection(b:Boolean,n:String?){handler.post{listener?.onConnectionStateChanged(b,n)}}
    @SuppressLint("MissingPermission") private fun safeName(d:BluetoothDevice)=try{d.name?:d.address}catch(_:Throwable){"DTCO"}
    private fun err(w:String,e:Throwable){log("ERROR [$w] ${e.javaClass.simpleName}: ${e.message}")}
    private fun u(b:Byte)=b.toInt() and 0xFF
    private fun hex(b:ByteArray)=b.joinToString(" "){String.format(Locale.US,"%02X",u(it))}
    private fun hb(v:Int)=String.format(Locale.US,"%02X",v and 0xFF)
    private fun hd(v:Int)=String.format(Locale.US,"%04X",v and 0xFFFF)
}
