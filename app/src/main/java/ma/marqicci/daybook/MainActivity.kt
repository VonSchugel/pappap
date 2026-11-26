package ma.marqicci.daybook

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.net.Uri
import android.content.Intent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
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

val LocalPages=compositionLocalOf<List<Page>> { emptyList() }
class MainActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DaybookTheme {
            val ready by book.ready.collectAsState()
            if(ready) Desk(book,intent.getStringExtra("open"),intent.getStringExtra("day"))
            else Box(Modifier.fillMaxSize().background(paper),contentAlignment=Alignment.Center){ CircularProgressIndicator() }
        }}
    }
    override fun onStop(){super.onStop();book.flush()}
}
@Composable fun Caption(s:String){Text(s,color=muted,fontSize=12.sp,lineHeight=17.sp,letterSpacing=.3.sp)}
@Composable fun Heading(s:String){Text(s,Modifier.semantics { heading() },color=ink,fontFamily=FontFamily.Serif,fontSize=25.sp,lineHeight=30.sp)}
@Composable fun Body(s:String){Text(s,color=ink,fontSize=16.sp,lineHeight=23.sp)}
@Composable fun Rule(){HorizontalDivider(color=line)}
@Composable fun Action(label:String,onClick:()->Unit){TextButton(onClick=onClick){ Text(label) }}
@Composable fun Field(label:String,value:String,set:(String)->Unit,one:Boolean=true){
    OutlinedTextField(value=value,onValueChange=set,label={Text(label)},singleLine=one,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(10.dp))
}
@Composable fun CardBox(content:@Composable ColumnScope.()->Unit){
    Surface(shape=RoundedCornerShape(16.dp),color=panel,border=BorderStroke(1.dp,line),modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)
    }
}
@Composable fun Sheet(title:String,close:()->Unit,closeLabel:String="Done",content:@Composable ColumnScope.()->Unit){
    AlertDialog(onDismissRequest=close,title={Heading(title)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)},confirmButton={Action(closeLabel,close)})
}
@Composable fun Web(title:String,url:String){
    val c=LocalContext.current
    if(url.startsWith("https://") || url.startsWith("http://")) TextButton(onClick={runCatching { c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }}){Text(title);Spacer(Modifier.width(6.dp));Mark(R.drawable.ic_external,Modifier.size(15.dp))}
}
fun nice(d:LocalDate,format:String)=d.format(DateTimeFormatter.ofPattern(format,Locale.ENGLISH))
@Composable fun DateButton(d:LocalDate,onDate:(LocalDate)->Unit,label:String=nice(d,"MMM d, yyyy")){
    val c=LocalContext.current
    OutlinedButton(onClick={DatePickerDialog(c,{_,y,m,dd->onDate(LocalDate.of(y,m+1,dd))},d.year,d.monthValue-1,d.dayOfMonth).show()}){ Mark(R.drawable.ic_calendar,Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text(label,fontSize=13.sp) }
}
@Composable fun ClockButton(label:String,v:String,set:(String)->Unit){
    val c=LocalContext.current
    Action("$label  $v"){val t=LocalTime.parse(v);TimePickerDialog(c,{_,h,m->set("%02d:%02d".format(Locale.ROOT,h,m))},t.hour,t.minute,true).show()}
}
@Composable fun Toggle(label:String,value:Boolean,onChange:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth().toggleable(value=value,role=Role.Switch,onValueChange=onChange).heightIn(min=48.dp),
        verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(value,onCheckedChange=null)}
}
@Composable fun Screen(scroll:ScrollState=rememberScrollState(),body:@Composable ColumnScope.()->Unit){
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal=20.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=body)
}
private val tabIcons=listOf(R.drawable.ic_today,R.drawable.ic_calendar,R.drawable.ic_brief,R.drawable.ic_journal)
@Composable fun Desk(b:Book,open:String?,initialDay:String?){
    val pages by b.state.collectAsState()
    val p=b.prefs()
    var current by remember { mutableStateOf(today(p)) }
    var dayOverride by rememberSaveable { mutableStateOf(initialDay) }
    var tab by rememberSaveable { mutableIntStateOf(if(open=="journal")3 else if(open=="tech")2 else 0) }
    var detail by rememberSaveable { mutableStateOf(open?.takeUnless { it == "journal" || it == "tech" }) }
    val day=dayOverride?.let(LocalDate::parse)?:current
    val screenState=rememberSaveableStateHolder()
    val screenKey="$day/$tab/${detail.orEmpty()}"
    val snacks=remember { SnackbarHostState() }
    var message by remember { mutableStateOf("") }
    val connected=!b.connection.getString("url","").isNullOrBlank()
    var request by remember(day,connected){ mutableStateOf(0 to (null as String?)) }
    var busy by remember(day,connected){ mutableStateOf(false) }
    fun sync(kind:String?=null){
        if(!connected){message="Connect a server in Schedule to refresh this report.";return}
        request=(request.first+1) to kind
    }
    LaunchedEffect(p.optString("zone")){while(true){current=today(b.prefs());delay(10000)}}
    LaunchedEffect(day,connected,request){
        if(!connected)return@LaunchedEffect
        busy=true
        try {
            val (sequence,kind)=request
            withContext(Dispatchers.IO){Api.sync(b,day.toString(),kind)}
            if(sequence>0)message="Updated from server."
        } catch(e:CancellationException){throw e}
        catch(e:Exception){message=e.message?:"Refresh failed."}
        finally{busy=false}
    }
    LaunchedEffect(Unit){Bells.reset(b.ctx)}
    fun select(t:Int){tab=t;detail=null;message="";if(t==3)dayOverride=null}
    BackHandler(enabled=detail!=null || tab!=0){if(detail!=null)detail=null else select(0)}
    val tabs=listOf("Today","Schedule","Tech Brief","Journal")
    val fatal by b.error.collectAsState()
    LaunchedEffect(message){if(message.isNotBlank()){snacks.showSnackbar(message,withDismissAction=true);message=""}}
    val typing=WindowInsets.ime.getBottom(LocalDensity.current)>0
    CompositionLocalProvider(LocalPages provides pages){
        Scaffold(modifier=Modifier.fillMaxSize().background(paper).statusBarsPadding(),
            contentWindowInsets=WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal+WindowInsetsSides.Bottom),
            containerColor=paper,snackbarHost={SnackbarHost(snacks)},bottomBar={
            if(!typing)
            Column(Modifier.background(paper)){
                if(tab==0 && detail==null)Button(onClick={tab=3},modifier=Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=4.dp),shape=RoundedCornerShape(13.dp)){
                    Mark(R.drawable.ic_pencil,Modifier.size(17.dp));Spacer(Modifier.width(9.dp));Text("Continue journal")
                    Spacer(Modifier.weight(1f));Mark(R.drawable.ic_arrow,Modifier.size(17.dp))
                }
                Rule()
                NavigationBar(containerColor=panel,tonalElevation=0.dp){tabs.forEachIndexed { i,t->
                    NavigationBarItem(modifier=Modifier.testTag("nav-$i"),selected=tab==i,onClick={select(i)},icon={Mark(tabIcons[i])},label={Text(t,fontSize=12.sp,lineHeight=14.sp)},
                        colors=NavigationBarItemDefaults.colors(selectedIconColor=leaf,indicatorColor=MaterialTheme.colorScheme.primaryContainer,
                            selectedTextColor=leaf,unselectedIconColor=muted,unselectedTextColor=muted))
                }}
            }
        }){padding->
            Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()){
                Row(Modifier.fillMaxWidth().padding(start=20.dp,end=14.dp,top=7.dp,bottom=5.dp),verticalAlignment=Alignment.CenterVertically){
                    Mark(R.drawable.ic_journal,Modifier.size(23.dp),leaf)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)){
                        Text("Marqicci",color=ink,fontFamily=FontFamily.Serif,fontWeight=FontWeight.Bold,fontSize=23.sp,lineHeight=26.sp)
                        if(!connected) Text(if(b.reports(day.toString()).any { it.optBoolean("demo") }) "Demo day" else "Offline",
                            color=muted,fontSize=11.sp,lineHeight=14.sp)
                    }
                    if(tab!=1)DateButton(day,{ dayOverride=if(it==current)null else it.toString();message="" })
                }
                Rule()
                if(busy) LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
                if(fatal.isNotEmpty())Text(fatal,Modifier.padding(12.dp),color=MaterialTheme.colorScheme.error)
                key(screenKey){screenState.SaveableStateProvider(screenKey){
                    when {
                        detail?.startsWith("activity-")==true->SessionScreen(b,day,detail!!,if(tab==3)"← Journal" else "← Agenda"){detail=null}
                        detail!=null->ReportScreen(b,day,detail!!,{detail=null},{sync(detail)})
                        tab==0->Today(b,day,{k->if(k=="tech")tab=2 else if(k=="journal")tab=3 else detail=k},{dayOverride=null},
                            {old->dayOverride=old;tab=3},{old,id->dayOverride=old;detail=id})
                        tab==1->Schedule(b){message=it}
                        tab==2->Brief(b,day){sync("tech")}
                        else->Journal(b,day,{dayOverride=it.toString()},{detail=it}){message=it}
                    }
                }}
            }
        }
    }
}
@Composable fun Today(b:Book,d:LocalDate,open:(String)->Unit,reset:()->Unit,resume:(String)->Unit,resumeSession:(String,String)->Unit){
    LocalPages.current
    val reports=b.reports(d.toString()).associateBy { it.getString("kind") };val p=b.prefs();val current=today(p)
    val sc=p.getJSONObject("schedule")
    val items=mutableListOf<AgendaItem>()
    val sessions=b.rows("session",d.toString()).associate { it.item to ActivitySession.read(it.body) }
    val kinds=(if(d<current)reports.keys+sessions.keys else sc.namesList()+reports.keys+sessions.keys).distinct()
    kinds.forEach { k->
        val r=reports[k];val rule=sc.optJSONObject(k)
        val session=sessions[k]
        if(session!=null){
            val status=if(session.complete)"Completed" else "In progress"
            items+=AgendaItem(session.time,k,session.title,listOf(status,session.excerpt()).filter { it.isNotBlank() }.joinToString(" · "))
            return@forEach
        }
        if(d>=current && rule!=null && (!rule.optBoolean("enabled") || d.dayOfWeek.value !in rule.weekdays()))return@forEach
        if(k.startsWith("activity-") && rule!=null){
            items+=AgendaItem(rule.getString("time"),k,rule.getString("title"),"Planned")
            return@forEach
        }
        val time=if(d<current)r?.optString("time") else rule?.optString("time")?:r?.optString("time")
        items+=AgendaItem(time?:"",k,r?.optString("title")?:rule?.optString("title")?:k,r?.optString("summary")?:if(k=="journal")"Return to today’s entry." else "Awaiting publication.")
    }
    b.rows("reminder",d.toString()).forEach { val r=JSONObject(it.body);items+=AgendaItem(r.getString("time"),it.item,r.getString("title"),"Personal reminder") }
    val previous=b.rows("draft").filter { it.day<d.toString() && it.body.isNotBlank() }.maxByOrNull { it.day }
    val unfinished=b.rows("session").filter { it.day<d.toString() }
        .map { it to ActivitySession.read(it.body) }.filterNot { it.second.complete }
        .sortedWith(compareByDescending<Pair<Page,ActivitySession>> { it.first.day }.thenByDescending { it.second.time })
    val journalFirst=previous!=null && (unfinished.isEmpty() || previous.day>unfinished.first().first.day)
    val sorted=items.sortedWith(compareBy<AgendaItem>{it.time}.thenBy { sc.namesList().indexOf(it.id) })
    var now by remember {mutableStateOf(LocalTime.now(ZoneId.of(p.getString("zone"))))}
    LaunchedEffect(d,p.optString("zone")){while(true){now=LocalTime.now(ZoneId.of(p.getString("zone")));delay(30000)}}
    val next=if(d==current) sorted.firstOrNull { (LocalTime.parse(it.time.ifBlank { "00:00" }))>=now && sessions[it.id]?.complete!=true } else null
    var showOlder by rememberSaveable {mutableStateOf(false)}
    Screen {
        DayHeader(d,sorted.size,next?.time,next?.title)
        if(d!=current)Action("Return to today",reset)
        if(unfinished.isNotEmpty() || previous!=null){
            Heading("Pick up unfinished work")
            CardBox {
                if(previous!=null && journalFirst){
                    Body(b.get("draft",previous.day,"unresolved").ifBlank { previous.body }.take(160))
                    Action("Reopen journal · "+previous.day){resume(previous.day)}
                }
                (if(showOlder)unfinished else if(journalFirst)emptyList() else unfinished.take(1)).forEach { (row,session)->
                    Action("Resume "+session.title+" · "+row.day){resumeSession(row.day,row.item)}
                    session.excerpt().takeIf { it.isNotEmpty() }?.let { Caption(it) }
                }
                if(previous!=null && !journalFirst && showOlder){
                    val question=b.get("draft",previous.day,"unresolved")
                    Body(question.ifBlank { previous.body }.take(160))
                    Action("Reopen journal · "+previous.day){resume(previous.day)}
                }
                val more=unfinished.size+(if(previous!=null)1 else 0)-1
                if(more>0)Action(if(showOlder)"Show fewer" else "Show $more more"){showOlder=!showOlder}
            }
        }
        Heading("Agenda")
        Column {
            if(sorted.isEmpty())Body("No scheduled items for this date. Your journal is open all day.")
            sorted.forEach { a->AgendaLine(a){open(a.id)};Rule() }
        }
    }
}

@Composable fun ReportScreen(b:Book,d:LocalDate,kind:String,back:()->Unit,refresh:()->Unit){
    LocalPages.current
    val r=b.reports(d.toString()).firstOrNull { it.optString("kind")==kind }
    val reminder=b.rows("reminder",d.toString()).firstOrNull { it.item==kind }
    Screen {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
            TextButton(onClick=back){Mark(R.drawable.ic_back,Modifier.size(17.dp));Spacer(Modifier.width(5.dp));Text("Agenda")}
            if(reminder==null && d==today(b.prefs()))IconAction(R.drawable.ic_refresh,"Refresh report",refresh)
        }
        PageTitle(r?.optString("title")?:reminder?.let { JSONObject(it.body).optString("title") }?:b.prefs().getJSONObject("schedule").optJSONObject(kind)?.optString("title")?:kind)
        if(reminder!=null){Body(JSONObject(reminder.body).optString("time")+" · "+d);return@Screen}
        if(r==null)Body("No published report for this date.")
        if(r!=null){
            if(r.optBoolean("demo"))Caption("ILLUSTRATIVE DEMO · sample conditions and events")
            CardBox { Body(r.optString("summary")) }
            if(!r.optBoolean("demo"))Caption("Last successful publication · "+r.optString("updated"))
            r.arr("gaps").texts().forEach { Caption(it) }
            r.arr("blocks").objects().forEach { section->
                val title=section.getString("title")
                val rows=section.arr("lines").texts()
                if(kind=="weather" && title.startsWith("Hourly",ignoreCase=true)){
                    var expanded by rememberSaveable(d.toString(),title){mutableStateOf(false)}
                    Row(Modifier.fillMaxWidth().clickable(role=Role.Button){expanded=!expanded}
                        .semantics { stateDescription=if(expanded)"Expanded" else "Collapsed" }.heightIn(min=52.dp),
                        verticalAlignment=Alignment.CenterVertically){
                        Column(Modifier.weight(1f)){Heading(title);Caption(rows.size.toString()+" hourly readings")}
                        Mark(if(expanded)R.drawable.ic_down else R.drawable.ic_chevron)
                    }
                    if(expanded)rows.forEach { Body(it);Rule() }
                }else{
                    Heading(title);rows.forEach { Body(it);Rule() }
                }
            }
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

