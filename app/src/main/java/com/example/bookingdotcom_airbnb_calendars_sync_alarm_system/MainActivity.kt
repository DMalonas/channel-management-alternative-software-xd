package com.example.bookingdotcom_airbnb_calendars_sync_alarm_system

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.util.Log
import android.content.BroadcastReceiver
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.content.ContextCompat.startActivity
import androidx.lifecycle.ViewModelProvider
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Url



class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CalendarApp()
        }
        // Call the method to check and request disabling of battery optimizations
        checkBatteryOptimizations()

        val serviceIntent = Intent(this, CalendarCheckService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun checkBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val packageName = packageName
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent()
                intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }
    }

}



@Composable
fun CalendarApp(viewModel: CalendarViewModel = viewModel(factory = CalendarViewModelFactory(LocalContext.current))) {
    val calendarStatus by viewModel.calendarStatus.collectAsState()
    val rawCalendarData by viewModel.rawCalendarData.collectAsState()
    Column {
        Text(text = calendarStatus)
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Calendar Data:")
        Text(text = rawCalendarData ?: "Fetching...")
    }
}

class CalendarViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CalendarViewModel::class.java)) {
            return CalendarViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}


interface CalendarService {
    @GET
    suspend fun fetchAirbnbCalendar(@Url url: String): String
}


class CalendarViewModel(private val context: Context) : ViewModel() {
    private val _rawCalendarData = MutableStateFlow<String?>(null)
    val rawCalendarData: StateFlow<String?> get() = _rawCalendarData
    private val AIRBNB_ICAL_URL = "https://www.airbnb.ie/calendar/ical/918763828716891572.ics?s=59c35c16b1707558eaf6612ffc37f547"
    private val _calendarStatus = MutableStateFlow("Checking for updates...")
    val calendarStatus: StateFlow<String> get() = _calendarStatus
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://www.airbnb.ie/")  // Base URL for initialization
        .addConverterFactory(ScalarsConverterFactory.create())
        .build()
    private val service = retrofit.create(CalendarService::class.java)

    init {
        viewModelScope.launch {
            periodicCheckOfAirbnbCalendar()
        }
    }

    private fun setAlarm() {
        val alarmIntent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(context, 0, alarmIntent,
            PendingIntent.FLAG_IMMUTABLE)

        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        manager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 5000, pendingIntent) // Alarm will go off after 5 seconds for demonstration purposes.
    }

    private suspend fun periodicCheckOfAirbnbCalendar() {
        var previousData: String? = null
        while (true) {
            try {
                val newCalendarData = service.fetchAirbnbCalendar(AIRBNB_ICAL_URL)
                _rawCalendarData.value = newCalendarData
                if (previousData != null && previousData != newCalendarData) {
                    _calendarStatus.value = "Calendar has been updated!"
                    setAlarm() // This will trigger the alarm
                    printDifferences(previousData, newCalendarData)
                }
                else {
                    _calendarStatus.value = "No update detected..."
                }
                previousData = newCalendarData
            } catch (e: Exception) {
                _calendarStatus.value = "Error checking updates." + e.message
                setAlarm() // This will trigger the alarm if there's an error
                Log.e("CalendarUpdateError", "Error fetching calendar data", e) // Log the exception
            }
            delay(60000) // 1-minute delay
        }
    }

    private fun printDifferences(oldData: String, newData: String) {
        Log.d("CalendarUpdate", newData)
    }
}



class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val r = RingtoneManager.getRingtone(context, notification)
        r.play()
    }
}




class CalendarCheckService : Service() {

    private val calendarViewModel = CalendarViewModel(this)

//    override fun onTaskRemoved(rootIntent: Intent?) {
//        stopSelf()
//    }


    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        // Move your periodicCheckOfAirbnbCalendar logic here or call it from the CalendarViewModel

        return START_STICKY // If service is killed by system, it will be recreated
    }

    private fun startForegroundNotification() {
        // Create a notification channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "calendarServiceChannel",
                "Calendar Check Service",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, "calendarServiceChannel")
            .setContentTitle("Calendar Check Running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        startForeground(1, notification)
    }



}




