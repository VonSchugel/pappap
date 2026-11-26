package ma.marqicci.daybook

import android.Manifest
import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.json.*
import org.threeten.bp.*

@Composable fun Schedule(b:Book,status:(String)->Unit){
    LocalPages.current
    val p=b.prefs();val schedule=p.getJSONObject("schedule")
    var dialog by rememberSaveable { mutableStateOf("") }
    val permit=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){if(!it)status("Notification permission is off. You can enable it in Android settings.")}
    fun permission(){if(Build.VERSION.SDK_INT>=33)permit.launch(Manifest.permission.POST_NOTIFICATIONS)}
    var expanded by rememberSaveable { mutableStateOf("") }
    val zone=ZoneId.of(p.getString("zone"))
    var now by remember(zone){mutableStateOf(ZonedDateTime.now(zone))}
    LaunchedEffect(zone){while(true){now=ZonedDateTime.now(zone);delay(30000)}}
    val active=activities(b).associateBy { it.id }
    Screen {
        Column { Eyebrow(p.getString("zone"));PageTitle("Schedule") }
        Button(onClick={dialog="recurring"}){
            Mark(R.drawable.ic_plus,Modifier.size(17.dp));Spacer(Modifier.width(6.dp));Text("Add recurring activity")
        }
        Heading("Recurring items")
        Column { schedule.namesList().sortedBy { schedule.getJSONObject(it).getString("time") }.forEach { id->
            val rule=schedule.getJSONObject(id);val enabled=rule.getBoolean("enabled")
            fun set(key:String,v:Any){b.settings { it.getJSONObject("schedule").getJSONObject(id).put(key,v) }}
            val next=active[id]?.next(now)
            val isOpen=expanded==id
            Column {
                Row(Modifier.fillMaxWidth().semantics { stateDescription=if(isOpen)"Expanded" else "Collapsed" }
                    .clickable(role=Role.Button){expanded=if(isOpen)"" else id}.padding(vertical=12.dp),
                    horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically){
                    KindMark(id)
                    Column(Modifier.weight(1f)){
                        Text(rule.getString("title"),color=ink,fontSize=16.sp,fontWeight=FontWeight.SemiBold)
                        Caption(if(enabled) describeDays(rule.weekdays()) else "Paused")
                    }
                    Text(rule.getString("time"),color=if(enabled)leaf else muted,fontSize=14.sp)
                    Mark(if(isOpen)R.drawable.ic_down else R.drawable.ic_chevron,Modifier.size(16.dp),muted)
                }
                if(isOpen) CardBox {
                    Caption(next?.let {"Next · "+nice(it.toLocalDate(),"EEE, MMM d")+" at "+it.toLocalTime()}?:"Paused")
                    Toggle("Enabled",enabled){set("enabled",it)}
                    ClockButton("Time",rule.getString("time")){set("time",it)}
                    if(id.startsWith("activity-")) Action("Edit activity · "+describeDays(rule.weekdays())){dialog="edit/"+id}
                    else Action("Repeat · "+describeDays(rule.weekdays())){dialog="days/"+id}
                    Toggle("Notify on device",rule.getBoolean("notify")){set("notify",it);if(it)permission()}
                }
                Rule()
            }
        } }
        Heading("Personal reminders")
        Action("Add reminder"){dialog="reminder"}
        b.rows("reminder").sortedBy { it.day+JSONObject(it.body).optString("time") }.forEach { row->
            val r=JSONObject(row.body)
            CardBox { Body(r.getString("title"));Caption(row.day+" · "+r.getString("time"))
                Toggle("Notify",r.optBoolean("notify",true)){b.put("reminder",row.day,row.item,r.put("notify",it).toString());Bells.reset(b.ctx);if(it)permission()}
                Action("Remove reminder"){b.remove(row);Bells.reset(b.ctx)}
            }
        }
        Heading("Preferences")
        Action("Beach pin and swim window"){dialog="coast"}
        Action("Local time zone"){dialog="zone"}
        Action("Newsletter sources and topics"){dialog="newsletter"}
        Action("Server connection"){dialog="connect"}
        Caption(b.get("meta","","sync","No server sync yet.") + if(b.get("meta","","pending").isNotEmpty())"\nPreference changes waiting to sync." else "")
        if(b.rows("event").isNotEmpty()){Heading("Bookmarked events");b.rows("event").forEach { EventCard(b,today(p),JSONObject(it.body),JSONObject(it.body).optString("url").isBlank()) }}
    }
    when(dialog){
        "newsletter"->NewsSettings(b){dialog=""}
        "recurring"->ActivityEditor(b,null){dialog=""}
        "reminder"->{
            var title by remember { mutableStateOf("") };var d by remember { mutableStateOf(today(p)) };var t by remember { mutableStateOf("12:00") };var notify by remember { mutableStateOf(true) }
            Sheet("Personal reminder",{dialog=""}){
                Field("Reminder",title,{title=it});DateButton(d,{d=it});ClockButton("Time",t){t=it};Toggle("Notify",notify){notify=it}
                Button(enabled=title.isNotBlank(),onClick={b.put("reminder",d.toString(),uid(),JSONObject().put("title",title.trim()).put("time",t).put("notify",notify).toString());Bells.reset(b.ctx);if(notify)permission();dialog=""}){Text("Add reminder")}
            }
        }
        "coast"->{
            var lat by remember { mutableStateOf(p.optString("lat")) };var lon by remember { mutableStateOf(p.optString("lon")) }
            var start by remember { mutableStateOf(p.getString("swimStart")) };var end by remember { mutableStateOf(p.getString("swimEnd")) }
            val x=lat.toDoubleOrNull();val y=lon.toDoubleOrNull()
            Sheet("Mannesmann swim window",{dialog=""}){
                Caption("The default pin marks the beach. Set the coordinates of your usual rock-sheltered section.")
                Field("Latitude",lat,{lat=it});Field("Longitude",lon,{lon=it})
                if(x!=null && y!=null)Web("Inspect pin on map","https://www.openstreetmap.org/?mlat=$x&mlon=$y#map=18/$x/$y")
                ClockButton("Start",start){start=it};ClockButton("End",end){end=it}
                if(end<=start)Caption("Choose a later end on the same date.")
                Button(enabled=x!=null && x in -90.0..90.0 && y!=null && y in -180.0..180.0 && end>start,onClick={b.settings { it.put("lat",x).put("lon",y).put("swimStart",start).put("swimEnd",end) };dialog=""}){Text("Save window")}
            }
        }
        "zone"->{
            var zone by remember { mutableStateOf(p.getString("zone")) }
            Sheet("Local time zone",{dialog=""}){Field("IANA time zone",zone,{zone=it});Button(enabled=runCatching { ZoneId.of(zone) }.isSuccess,onClick={b.settings { it.put("zone",zone) };dialog=""}){Text("Save time zone")}}
        }
        "connect"->{
            var url by remember { mutableStateOf(b.connection.getString("url","").orEmpty()) };var token by remember { mutableStateOf(b.connection.getString("key","").orEmpty()) }
            val uri=Uri.parse(url.trim());val valid=uri.host!=null && (uri.scheme=="https" || uri.scheme=="http" && uri.host in listOf("127.0.0.1","localhost","10.0.2.2")) && uri.userInfo==null && uri.query==null && uri.fragment==null
            Sheet("Server connection",{dialog=""}){
                Field("FastAPI base URL",url,{url=it})
                OutlinedTextField(value=token,onValueChange={token=it},label={Text("App token")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
                Caption("Use HTTPS or a loopback development address. Journal text stays on this device.")
                Button(enabled=valid && token.isNotBlank(),onClick={b.connection.edit().putString("url",url.trim().trimEnd('/')).putString("key",token.trim()).commit();b.put("meta","","sync","Connecting…");SyncWorker.request(b.ctx);dialog="";status("Connection saved. Sync queued.")}){Text("Connect")}
                Action("Use offline mode"){b.connection.edit().remove("url").remove("key").commit();b.put("meta","","sync","Disconnected");dialog=""}
            }
        }
    }
    if(dialog.startsWith("edit/")) ActivityEditor(b,dialog.substringAfter("/")){dialog=""}
    if(dialog.startsWith("days/")){
        val id=dialog.substringAfter("/")
        var days by remember(id){mutableStateOf(schedule.getJSONObject(id).weekdays())}
        Sheet("Repeat "+schedule.getJSONObject(id).getString("title"),{dialog=""},closeLabel="Cancel"){
            WeekdaySelector(days){days=it}
            Button(enabled=days.isNotEmpty(),onClick={
                b.settings { it.getJSONObject("schedule").getJSONObject(id).put("weekdays",JSONArray(days.sorted())) }
                dialog=""
            }){Text("Save days")}
        }
    }
}
@Composable fun NewsSettings(b:Book,close:()->Unit){
    val p=remember { b.prefs() }
    var repos by remember {mutableStateOf(p.arr("repos").texts().joinToString(", "))}
    var orgs by remember {mutableStateOf(p.arr("orgs").texts().joinToString(", "))}
    var accounts by remember {mutableStateOf(p.arr("accounts").texts().joinToString(", "))}
    var length by remember {mutableStateOf(p.optString("length"))}
    var topics by remember { mutableStateOf(p.arr("topics").texts()) }
    var switches by remember { mutableStateOf(p.getJSONObject("sources").toString()) }
    fun tokens(s:String)=s.split(",","\n").map { it.trim().removePrefix("@") }.filter { it.isNotBlank() }.distinct()
    val rs=tokens(repos);val os=tokens(orgs);val xs=tokens(accounts)
    val valid=rs.size<=20 && os.size<=8 && xs.size<=20 && rs.all { it.matches(Regex("""[\w.-]+/[\w.-]+""")) && ".." !in it } && os.all { it.matches(Regex("""[\w-]+""")) } && xs.all { it.matches(Regex("""[A-Za-z0-9_]{1,15}""")) } && (length.toIntOrNull() ?: 0) in 1..40
    Sheet("Edition preferences",close){
        for(t in listOf("AI models","Developer tools","Robotics")) Toggle(t,t in topics){topics=if(it)topics+t else topics-t}
        for((id,name) in listOf("github" to "GitHub","hn" to "Hacker News","x" to "X / Twitter"))Toggle(name,JSONObject(switches).optBoolean(id)){switches=JSONObject(switches).put(id,it).toString()}
        Field("Followed repositories · owner/repo",repos,{repos=it},false);Field("Organizations · comma separated",orgs,{orgs=it},false)
        Field("X accounts · comma separated",accounts,{accounts=it},false);Field("Maximum stories · 1–40",length,{length=it})
        if(!valid)Caption("Check repository names, handles and edition length.")
        Button(enabled=valid,onClick={b.settings { it.put("repos",JSONArray(rs)).put("orgs",JSONArray(os)).put("accounts",JSONArray(xs)).put("topics",JSONArray(topics)).put("sources",JSONObject(switches)).put("length",length.toInt()) };close()}){Text("Save preferences")}
    }
}

