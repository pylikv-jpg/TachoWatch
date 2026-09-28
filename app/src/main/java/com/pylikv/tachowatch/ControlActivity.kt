package com.pylikv.tachowatch

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class ControlActivity:AppCompatActivity(),RhmiControlClient.Listener{
    private lateinit var client:RhmiControlClient;private lateinit var status:TextView;private lateinit var token:EditText
    private val permission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){connectSaved()}
    override fun onCreate(b:Bundle?){super.onCreate(b);client=RhmiControlClient(this,this);buildUi();connectSaved()}
    override fun onDestroy(){client.disconnect();super.onDestroy()}
    private fun buildUi(){val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24)}
        root.addView(TextView(this).apply{text="Управление DTCO · эксперимент";textSize=24f;setTypeface(typeface,Typeface.BOLD)})
        status=TextView(this).apply{text="Подключение…";setPadding(0,12,0,20)};root.addView(status)
        root.addView(title("Сопряжение Remote HMI"));root.addView(Button(this).apply{text="Запросить сопряжение";setOnClickListener{client.requestPairing()}})
        token=EditText(this).apply{hint="8-значный код с дисплея DTCO";inputType=InputType.TYPE_CLASS_NUMBER};root.addView(token)
        root.addView(Button(this).apply{text="Сохранить код и проверить";setOnClickListener{client.acceptToken(token.text.toString())}})
        root.addView(title("Текущий режим водителя"));root.addView(row(button("Отдых"){client.setActivity(0)},button("Готовность"){client.setActivity(1)},button("Другая работа"){client.setActivity(2)}))
        root.addView(title("Загрузка / выгрузка"));root.addView(row(button("Загрузка"){client.loadUnload(1)},button("Выгрузка"){client.loadUnload(2)},button("Обе"){client.loadUnload(3)}))
        root.addView(title("Паром / поезд"));root.addView(row(button("Начало"){client.ferryTrain(true)},button("Окончание"){client.ferryTrain(false)}))
        root.addView(TextView(this).apply{text="Команды изолированы от счётчиков TachoWatch. Изменение подтверждается только после положительного ответа DTCO.";setPadding(0,24,0,0)})
        setContentView(ScrollView(this).apply{addView(root)})}
    private fun title(s:String)=TextView(this).apply{text=s;textSize=18f;setTypeface(typeface,Typeface.BOLD);setPadding(0,24,0,10)}
    private fun button(s:String,f:()->Unit)=Button(this).apply{text=s;setOnClickListener{f()}}
    private fun row(vararg b:Button)=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;b.forEach{addView(it,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))}}
    private fun connectSaved(){if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){permission.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN));return};val a=(getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter;val addr=getSharedPreferences("tachowatch_auto_card",Context.MODE_PRIVATE).getString("selected_dtco_address",null);val d=addr?.let{runCatching{a.getRemoteDevice(it)}.getOrNull()};d?.let{client.connect(it)}?:run{status.text="Сначала выберите тахограф на главном экране"}}
    override fun onState(text:String){status.text=text};override fun onPairingDataReady(){token.requestFocus()};override fun onCommandResult(text:String){status.text=text;Toast.makeText(this,text,Toast.LENGTH_LONG).show()}
}
