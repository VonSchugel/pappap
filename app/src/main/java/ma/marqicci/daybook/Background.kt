package ma.marqicci.daybook

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.work.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

data class ActivityTime(val id:String,val title:String,val clock:LocalTime,val date:LocalDate?,val notify:Boolean,val weekdays:Set<Int> = everyDay){
    fun next(now:ZonedDateTime):ZonedDateTime?{
        if(date!=null) return date.atTime(clock).atZone(now.zone).takeIf { it.isAfter(now) }
        for(offset in 0L..7L){
            val day=now.toLocalDate().plusDays(offset)
            if(day.dayOfWeek.value !in weekdays) continue
            val run=day.atTime(clock).atZone(now.zone)
            if(run.isAfter(now)) return run
        }
        return null
    }
}
fun activities(b:Book):List<ActivityTime>{
    val schedule=b.prefs().getJSONObject("schedule")
    return schedule.namesList().mapNotNull { id->
        val r=schedule.getJSONObject(id)
        if(!r.getBoolean("enabled")) null else ActivityTime(id,r.getString("title"),LocalTime.parse(r.getString("time")),null,r.getBoolean("notify"),r.weekdays())
    }+b.rows("reminder").mapNotNull { row->
        val r=JSONObject(row.body)
        try {
            ActivityTime(row.item,r.getString("title"),LocalTime.parse(r.getString("time")),LocalDate.parse(row.day),r.optBoolean("notify",true))
        } catch(e:java.time.format.DateTimeParseException){null}
    }
}
object Bells {
    val background=Executors.newSingleThreadExecutor()
    fun intent(ctx:Context,id:String,at:Long)=PendingIntent.getBroadcast(ctx,0,Intent(ctx,Bell::class.java).setData(Uri.parse("marqicci://alarm/$id")).putExtra("at",at),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    @Synchronized fun reset(ctx:Context){
        if(!ctx.book.ready.value) return
        val b=ctx.book;val am=ctx.getSystemService(AlarmManager::class.java)
        val previous=b.connection.getStringSet("alarms",emptySet()).orEmpty().toSet()
        val now=ZonedDateTime.now(ZoneId.of(b.prefs().getString("zone")))
        val next=activities(b).filter { it.notify }.mapNotNull { a->a.next(now)?.let { a.id to it.toInstant().toEpochMilli() } }.toMap()
        (previous-next.keys).forEach { am.cancel(intent(ctx,it,0)) }
        next.forEach { (id,at)->am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,intent(ctx,id,at)) }
        b.connection.edit().putStringSet("alarms",next.keys.toSet()).apply()
    }
}
class Reboot:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        val pending=goAsync()
        Bells.background.execute { try { context.book.flush(); Bells.reset(context) } finally { pending.finish() } }
    }
}
class Bell:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        val pending=goAsync()
        Bells.background.execute {
            try {
                val b=context.book;b.flush()
                val id=intent.data?.lastPathSegment ?: return@execute
                val at=intent.getLongExtra("at",0)
                val zone=ZoneId.of(b.prefs().getString("zone"))
                val due=Instant.ofEpochMilli(at).atZone(zone)
                val activity=activities(b).firstOrNull { it.id==id && it.notify } ?: return@execute
                val d=due.toLocalDate()
                if(activity.date==null && d.dayOfWeek.value !in activity.weekdays) return@execute
                if((activity.date!=null && activity.date!=d) || d!=LocalDate.now(zone) || d.atTime(activity.clock).atZone(zone).toInstant()!=due.toInstant()) return@execute
                val nm=context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel("agenda","Daily agenda",NotificationManager.IMPORTANCE_DEFAULT))
                if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return@execute
                if(b.db.pages().claim(Page("delivered",d.toString(),id,"1"))==-1L) return@execute
                val launch=Intent(context,MainActivity::class.java).putExtra("open",id).putExtra("day",d.toString())
                val open=PendingIntent.getActivity(context,(id+d).hashCode(),launch,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification=Notification.Builder(context,"agenda").setSmallIcon(R.drawable.mark).setContentTitle(activity.title).setContentText("Open your daybook").setAutoCancel(true).setOnlyAlertOnce(true).setContentIntent(open).build()
                nm.notify(id+d,1,notification)
            } finally { Bells.reset(context); pending.finish() }
        }
    }
}
object Api {
    private fun http(b:Book,path:String,method:String="GET",body:JSONObject?=null):JSONObject{
        val base=b.connection.getString("url","").orEmpty().trimEnd('/')
        require(base.isNotBlank()){"Connect a server in Schedule."}
        val c=URL(base+path).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects=false;c.requestMethod=method;c.connectTimeout=10000;c.readTimeout=150000
            c.setRequestProperty("Authorization","Bearer "+b.connection.getString("key",""))
            if(body!=null){ c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
            if(c.responseCode !in 200..299) error("Server returned HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }
    @Synchronized fun sync(b:Book,day:String?,refresh:String?=null){
        b.flush()
        val rev=b.get("meta","","pending")
        if(rev.isNotEmpty()){ http(b,"/v1/preferences","PUT",b.prefs()); b.acknowledge(rev) }
        else b.receivedPrefs(http(b,"/v1/preferences"))
        val d=day?:today(b.prefs()).toString()
        if(refresh!=null) http(b,"/v1/days/$d/refresh/$refresh","POST")
        val reports=http(b,"/v1/days/$d").arr("reports").objects()
        b.edit(reports.map { Page("report",d,it.getString("kind"),it.toString()) })
        b.put("meta","","sync","Synced "+Instant.now());b.flush()
    }
}
class SyncWorker(ctx:Context,params:WorkerParameters):Worker(ctx,params){
    override fun doWork():Result{
        val b=applicationContext.book;b.flush()
        if(b.connection.getString("url","").isNullOrBlank()) return Result.success()
        return try { Api.sync(b,null); Result.success() } catch(e:Exception){ b.put("meta","","sync","Sync pending · "+e.message);Result.retry() }
    }
    companion object {
        private val network=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        fun schedule(ctx:Context)=WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("report-cache",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<SyncWorker>(30,TimeUnit.MINUTES).setConstraints(network).build())
        fun request(ctx:Context)=WorkManager.getInstance(ctx).enqueueUniqueWork("preferences",ExistingWorkPolicy.APPEND_OR_REPLACE,OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network).build())
    }
}

