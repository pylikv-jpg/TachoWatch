from pathlib import Path
import base64

activity_path = Path("app/src/main/java/com/pylikv/tachowatch/DriverDashboardActivity.kt")
manifest_path = Path("app/src/main/AndroidManifest.xml")
icon_b64_path = Path("tools/tachowatch_icon.jpg.b64")
icon_path = Path("app/src/main/res/drawable/tachowatch_icon.jpg")

text = activity_path.read_text(encoding="utf-8")
marker = "AUTO_RECONNECT_FIX_V1"
if marker not in text:
    old = '    private lateinit var live:LiveDidDiagnostic; private lateinit var cardReader:DtcoBluetoothDiagnostic\n    private var dtco:BluetoothDevice?=null; private var history:HistoryData.Model?=null; private var cardReading=false; private var resumeLive=false\n'
    new = old + '''    private var activityDestroyed=false // AUTO_RECONNECT_FIX_V1
    private val reconnectRunnable=Runnable{
        if(activityDestroyed||cardReading)return@Runnable
        val d=dtco?:return@Runnable
        status.text="Переподключение к DTCO…"
        live.connect(d)
    }
'''
    if old not in text:
        raise SystemExit("reconnect state anchor not found")
    text = text.replace(old, new, 1)

    old = '    override fun onDestroy(){live.disconnect();cardReader.disconnect();super.onDestroy()}\n'
    new = '    override fun onDestroy(){activityDestroyed=true;mainHandler.removeCallbacks(reconnectRunnable);live.disconnect();cardReader.disconnect();super.onDestroy()}\n'
    if old not in text:
        raise SystemExit("onDestroy anchor not found")
    text = text.replace(old, new, 1)

    old = '    override fun onLiveConnection(connected:Boolean,deviceName:String?){runOnUiThread{if(!cardReading)status.text=if(connected)"Онлайн • ${deviceName?:"DTCO"}" else "Нет связи с выбранным DTCO"}}\n'
    new = '''    override fun onLiveConnection(connected:Boolean,deviceName:String?){runOnUiThread{
        if(cardReading)return@runOnUiThread
        if(connected){
            mainHandler.removeCallbacks(reconnectRunnable)
            status.text="Онлайн • ${deviceName?:"DTCO"}"
        }else if(!activityDestroyed){
            status.text="Связь потеряна • переподключение…"
            mainHandler.removeCallbacks(reconnectRunnable)
            mainHandler.postDelayed(reconnectRunnable,1500L)
        }
    }}
'''
    if old not in text:
        raise SystemExit("onLiveConnection anchor not found")
    text = text.replace(old, new, 1)
    activity_path.write_text(text, encoding="utf-8")
    print("Applied automatic Bluetooth reconnect")
else:
    print("Automatic Bluetooth reconnect already applied")

manifest = manifest_path.read_text(encoding="utf-8")
if 'android:icon="@drawable/tachowatch_icon"' not in manifest:
    old = '<application android:allowBackup="true" android:label="TachoWatch" android:supportsRtl="true" android:theme="@style/Theme.TachoWatch">'
    new = '<application android:allowBackup="true" android:label="TachoWatch" android:icon="@drawable/tachowatch_icon" android:roundIcon="@drawable/tachowatch_icon" android:supportsRtl="true" android:theme="@style/Theme.TachoWatch">'
    if old not in manifest:
        raise SystemExit("manifest application anchor not found")
    manifest = manifest.replace(old, new, 1)
    manifest_path.write_text(manifest, encoding="utf-8")
    print("Applied TachoWatch launcher icon in manifest")
else:
    print("Launcher icon already configured")

icon_path.parent.mkdir(parents=True, exist_ok=True)
b64 = ''.join(icon_b64_path.read_text(encoding="utf-8").split())
b64 += '=' * ((4 - len(b64) % 4) % 4)
raw = base64.b64decode(b64)
if not raw.startswith(b'\xff\xd8\xff'):
    raise SystemExit("decoded launcher icon is not a JPEG")
icon_path.write_bytes(raw)
print(f"Decoded launcher icon: {icon_path} ({len(raw)} bytes)")
