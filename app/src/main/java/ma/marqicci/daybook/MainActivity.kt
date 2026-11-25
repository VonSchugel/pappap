package ma.marqicci.daybook

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.net.Uri
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.json.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

val ink=Color(0xff233c32)
val muted=Color(0xff546559)
val paper=Color(0xfffaf8ef)
val line=Color(0xffd9ddcf)
val leaf=Color(0xff27573f)
val clay=Color(0xff9c502e)
val LocalPages=compositionLocalOf<List<Page>> { emptyList() }
class MainActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme=lightColorScheme(primary=leaf,onPrimary=Color.White,background=paper,surface=paper,onSurface=ink,secondaryContainer=Color(0xffdfe9d5),outline=muted)){
            val ready by book.ready.collectAsState()
            if(ready) Desk(book,intent.getStringExtra("open"),intent.getStringExtra("day"))
            else Box(Modifier.fillMaxSize().background(paper),contentAlignment=Alignment.Center){ CircularProgressIndicator() }
        }}
    }
    override fun onStop(){super.onStop();book.flush()}
}
@Composable fun Caption(s:String){Text(s,color=muted,fontSize=12.sp,lineHeight=17.sp,letterSpacing=.3.sp)}
@Composable fun Heading(s:String){Text(s,color=ink,fontFamily=FontFamily.Serif,fontSize=27.sp,lineHeight=32.sp)}
@Composable fun Body(s:String){Text(s,color=ink,fontSize=16.sp,lineHeight=23.sp)}
@Composable fun Rule(){HorizontalDivider(color=line)}
@Composable fun Action(label:String,onClick:()->Unit){TextButton(onClick=onClick){ Text(label) }}
@Composable fun Field(label:String,value:String,set:(String)->Unit,one:Boolean=true){
    OutlinedTextField(value=value,onValueChange=set,label={Text(label)},singleLine=one,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(10.dp))
}
@Composable fun CardBox(content:@Composable ColumnScope.()->Unit){
    Surface(shape=RoundedCornerShape(16.dp),color=Color(0xfff1f1e6),modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)
    }
}
@Composable fun Sheet(title:String,close:()->Unit,closeLabel:String="Done",content:@Composable ColumnScope.()->Unit){
    AlertDialog(onDismissRequest=close,title={Heading(title)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)},confirmButton={Action(closeLabel,close)})
}
@Composable fun Web(title:String,url:String){
    val c=LocalContext.current
    if(url.startsWith("https://") || url.startsWith("http://")) Action(title+" ↗"){runCatching { c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }}
}
fun nice(d:LocalDate,format:String)=d.format(DateTimeFormatter.ofPattern(format,Locale.ENGLISH))
@Composable fun DateButton(d:LocalDate,onDate:(LocalDate)->Unit,label:String=nice(d,"MMM d, yyyy")){
    val c=LocalContext.current
    OutlinedButton(onClick={DatePickerDialog(c,{_,y,m,dd->onDate(LocalDate.of(y,m+1,dd))},d.year,d.monthValue-1,d.dayOfMonth).show()}){ Text(label) }
}
@Composable fun ClockButton(label:String,v:String,set:(String)->Unit){
    val c=LocalContext.current
    Action("$label  $v"){val t=LocalTime.parse(v);TimePickerDialog(c,{_,h,m->set("%02d:%02d".format(Locale.ROOT,h,m))},t.hour,t.minute,true).show()}
}
@Composable fun Toggle(label:String,value:Boolean,onChange:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(value,onCheckedChange=onChange)}
}
@Composable fun Screen(body:@Composable ColumnScope.()->Unit){
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=body)
}
private val glyphs=listOf("◉","◷","▤","✎")
@Composable fun Desk(b:Book,open:String?,initialDay:String?){
    val pages by b.state.collectAsState()
    val p=b.prefs()
    var current by remember { mutableStateOf(today(p)) }
    var dayOverride by rememberSaveable { mutableStateOf(initialDay) }
    var tab by rememberSaveable { mutableIntStateOf(if(open=="journal")3 else if(open=="tech")2 else 0) }
    var detail by rememberSaveable { mutableStateOf(open?.takeUnless { it == "journal" || it == "tech" }) }
    val day=dayOverride?.let(LocalDate::parse)?:current
    val scope=rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) };var message by remember { mutableStateOf("") }
    val connected=!b.connection.getString("url","").isNullOrBlank()
    fun sync(kind:String?=null){
        if(busy)return
        if(!connected){message="Demo mode. Connect FastAPI in Schedule to refresh live reports.";return}
        busy=true;message=""
        scope.launch {
            message=withContext(Dispatchers.IO){runCatching { Api.sync(b,day.toString(),kind); "Updated from server." }.getOrElse { it.message?:"Refresh failed." }}
            busy=false
        }
    }
    LaunchedEffect(p.optString("zone")){while(true){current=today(b.prefs());delay(10000)}}
    LaunchedEffect(day,connected){ if(connected)sync() }
    LaunchedEffect(Unit){Bells.reset(b.ctx)}
    fun select(t:Int){tab=t;detail=null;message="";if(t==3)dayOverride=null}
    BackHandler(enabled=detail!=null || tab!=0){if(detail!=null)detail=null else select(0)}
    val tabs=listOf("Today","Schedule","Tech Brief","Journal")
    val fatal by b.error.collectAsState()
    CompositionLocalProvider(LocalPages provides pages){
        Scaffold(containerColor=paper,bottomBar={
            Column(Modifier.background(paper)){
                if(tab==0 && detail==null)Button(onClick={tab=3},modifier=Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=6.dp),shape=RoundedCornerShape(12.dp)){Text("Continue journal  →",modifier=Modifier.padding(vertical=3.dp))}
                NavigationBar(containerColor=Color(0xfff2f2e8)){tabs.forEachIndexed { i,t->
                    NavigationBarItem(selected=tab==i,onClick={select(i)},icon={Text(glyphs[i],fontSize=21.sp)},label={Text(t,maxLines=1)})
                }}
            }
        }){padding->
            Column(Modifier.padding(padding).fillMaxSize()){
                Row(Modifier.fillMaxWidth().padding(start=20.dp,end=14.dp,top=7.dp,bottom=5.dp),verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){Text("Marqicci",color=leaf,fontFamily=FontFamily.Serif,fontWeight=FontWeight.Bold,fontSize=22.sp);Caption(if(connected)"Personal daybook" else "Offline daybook · demo mode")}
                    if(tab!=1)DateButton(day,{ dayOverride=if(it==current)null else it.toString();message="" })
                }
                Rule()
                if(busy) LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
                if(message.isNotEmpty() || fatal.isNotEmpty())Text(fatal.ifEmpty { message },Modifier.padding(horizontal=20.dp,vertical=5.dp),fontSize=13.sp,color=clay)
                key(tab,detail,day.toString()){
                    when {
                        detail?.startsWith("activity-")==true->SessionScreen(b,day,detail!!,if(tab==3)"← Journal" else "← Agenda"){detail=null}
                        detail!=null->ReportScreen(b,day,detail!!,{detail=null},{sync(detail)})
                        tab==0->Today(b,day,{k->if(k=="tech")tab=2 else if(k=="journal")tab=3 else detail=k},{dayOverride=null},
                            {old->dayOverride=old;tab=3},{old,id->dayOverride=old;detail=id})
                        tab==1->Schedule(b){message=it}
                        tab==2->Brief(b,day){sync("tech")}
                        else->Journal(b,day,{dayOverride=it.toString()},{detail=it}){message=it}
                    }
                }
            }
        }
    }
}
@Composable fun Today(b:Book,d:LocalDate,open:(String)->Unit,reset:()->Unit,resume:(String)->Unit,resumeSession:(String,String)->Unit){
    LocalPages.current
    val reports=b.reports(d.toString()).associateBy { it.getString("kind") };val p=b.prefs();val current=today(p)
    Screen {
        Caption(nice(d,"EEEE · MMMM yyyy").uppercase(Locale.ENGLISH))
        Text(nice(d,"MMMM d"),fontFamily=FontFamily.Serif,fontSize=48.sp,lineHeight=54.sp,color=ink)
        if(d!=current)Action("Return to today",reset)
        Surface(color=leaf,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth().clickable { open("journal") }){
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                Text("ALL DAY",color=Color(0xffd3deb9),fontSize=12.sp,letterSpacing=1.5.sp)
                Text("Intellectual journal",color=Color.White,fontFamily=FontFamily.Serif,fontSize=26.sp)
                Text(b.rows("draft",d.toString()).firstOrNull { it.body.isNotBlank() }?.body?.take(100)?:"Open a page for today’s reading and questions.",color=Color(0xffe0e8d7),fontSize=14.sp)
            }
        }
        Heading("Agenda")
        val sc=p.getJSONObject("schedule")
        data class RowItem(val time:String,val id:String,val title:String,val summary:String)
        val items=mutableListOf<RowItem>()
        val sessions=b.rows("session",d.toString()).associate { it.item to ActivitySession.read(it.body) }
        val kinds=(if(d<current)reports.keys+sessions.keys else sc.namesList()+reports.keys+sessions.keys).distinct()
        kinds.forEach { k->
            val r=reports[k];val rule=sc.optJSONObject(k)
            val session=sessions[k]
            if(session!=null){
                val status=if(session.complete)"Completed" else "In progress"
                items+=RowItem(session.time,k,session.title,listOf(status,session.excerpt()).filter { it.isNotBlank() }.joinToString(" · "))
                return@forEach
            }
            if(d>=current && rule!=null && (!rule.optBoolean("enabled") || d.dayOfWeek.value !in rule.weekdays()))return@forEach
            if(k.startsWith("activity-") && rule!=null){
                items+=RowItem(rule.getString("time"),k,rule.getString("title"),"Planned")
                return@forEach
            }
            val time=if(d<current)r?.optString("time") else rule?.optString("time")?:r?.optString("time")
            items+=RowItem(time?:"",k,r?.optString("title")?:rule?.optString("title")?:k,(if(r?.optBoolean("demo")==true)"Demo · " else "")+(r?.optString("summary")?:if(k=="journal")"Return to today’s entry." else "Awaiting publication."))
        }
        b.rows("reminder",d.toString()).forEach { val r=JSONObject(it.body);items+=RowItem(r.getString("time"),it.item,r.getString("title"),"Personal reminder") }
        if(items.isEmpty())Body("No reports or reminders saved for this date.")
        items.sortedWith(compareBy<RowItem>{it.time}.thenBy { sc.namesList().indexOf(it.id) }).forEach { a->
            Column(Modifier.fillMaxWidth().clickable { open(a.id) }.padding(vertical=5.dp)){
                Caption(a.time);Text(a.title,fontFamily=FontFamily.Serif,color=ink,fontSize=22.sp);Text(a.summary,color=muted,fontSize=14.sp,lineHeight=20.sp)
            };Rule()
        }
        val previous=b.rows("draft").filter { it.day<d.toString() && it.body.isNotBlank() }.maxByOrNull { it.day }
        val unfinished=b.rows("session").filter { it.day<d.toString() }.sortedByDescending { it.day }
            .map { it to ActivitySession.read(it.body) }.filterNot { it.second.complete }
        if(previous!=null || unfinished.isNotEmpty()) Heading("Pick up unfinished work")
        unfinished.forEach { (row,session)->
            Action("Resume "+session.title+" · "+row.day){resumeSession(row.day,row.item)}
            session.excerpt().takeIf { it.isNotEmpty() }?.let { Caption(it) }
        }
        if(previous!=null){
            val question=b.get("draft",previous.day,"unresolved")
            Body(question.ifBlank { previous.body }.take(160))
            Action("Reopen journal · "+previous.day){resume(previous.day)}
        }
    }
}
@Composable fun ReportScreen(b:Book,d:LocalDate,kind:String,back:()->Unit,refresh:()->Unit){
    LocalPages.current
    val r=b.reports(d.toString()).firstOrNull { it.optString("kind")==kind }
    val reminder=b.rows("reminder",d.toString()).firstOrNull { it.item==kind }
    Screen {
        Action("← Agenda",back)
        Heading(r?.optString("title")?:reminder?.let { JSONObject(it.body).optString("title") }?:b.prefs().getJSONObject("schedule").optJSONObject(kind)?.optString("title")?:kind)
        if(reminder!=null){Body(JSONObject(reminder.body).optString("time")+" · "+d);return@Screen}
        if(r==null)Body("No published report for this date.")
        if(d==today(b.prefs()))Action("Refresh report",refresh)
        if(r!=null){
            if(r.optBoolean("demo"))Caption("ILLUSTRATIVE DEMO · sample conditions and events")
            CardBox { Body(r.optString("summary")) }
            if(!r.optBoolean("demo"))Caption("Last successful publication · "+r.optString("updated"))
            r.arr("gaps").texts().forEach { Caption(it) }
            r.arr("blocks").objects().forEach { section->Heading(section.getString("title"));section.arr("lines").texts().forEach { Body(it);Rule() } }
            r.arr("links").objects().forEach { Web(it.optString("title"),it.optString("url")) }
            r.arr("events").objects().forEach { EventCard(b,d,it,r.optBoolean("demo")) }
        }
        if(kind=="swim"){
            Heading("Beach observations")
            var note by rememberSaveable { mutableStateOf("") }
            Field("Waves, gap flow, flags and exit",note,{note=it},false)
            Button(enabled=note.isNotBlank(),onClick={b.put("observation",d.toString(),uid(),Instant.now().toString()+" · "+note);note=""}){Text("Save observation")}
            b.rows("observation",d.toString()).forEach { Body(it.body) }
        }
    }
}
@Composable fun EventCard(b:Book,d:LocalDate,e:JSONObject,demo:Boolean){
    LocalPages.current
    val id=identity(e.optString("url").ifEmpty { e.getString("title")+d });val saved=b.get("event","",id).isNotEmpty()
    val deadline=e.optString("deadlineAt").takeIf { it.isNotBlank() }?.let { Instant.parse(it).atZone(ZoneId.of(b.prefs().getString("zone"))) }
    val day=deadline?.toLocalDate()?.toString()?:e.optString("deadline")
    val time=deadline?.toLocalTime()?.toString()?.take(5)?:e.optString("time")
    CardBox {
        Heading(e.getString("title"));Caption(e.getString("mode"));Body(e.getString("eligibility"))
        if(day.isNotBlank())Caption("Apply by $day · $time") else Caption("Application deadline unverified")
        Web("Organizer",e.optString("url"));Web("Eligibility rules",e.optString("rules"))
        Action(if(saved)"Remove bookmark" else "Bookmark event"){
            if(saved)b.rows("event").firstOrNull { it.item==id }?.let(b::remove) else b.put("event","",id,e.toString())
        }
        if(day.isNotBlank() && time.isNotBlank()){
            val exists=b.get("reminder",day,id).isNotBlank()
            Action(if(exists)"Deadline added" else "Add deadline to Schedule"){if(!exists){b.put("reminder",day,id,JSONObject().put("title",(if(demo)"Demo · " else "")+e.getString("title")+" · apply").put("time",time).put("notify",true).toString());Bells.reset(b.ctx)}}
        }
    }
}

