package ma.marqicci.daybook

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONObject
import java.time.LocalDate

@Composable fun Brief(b:Book,d:LocalDate,refresh:()->Unit){
    LocalPages.current
    val edition=b.reports(d.toString()).firstOrNull { it.optString("kind")=="tech" }
    val options=listOf("All","AI models","Developer tools","Robotics","Saved")
    val filterKey="brief.filter."+d
    var filter by rememberSaveable(d.toString()){
        mutableStateOf(b.connection.getString(filterKey,"All")?.takeIf { it in options } ?: "All")
    }
    fun selectTopic(topic:String){
        b.connection.edit().putString(filterKey,topic).apply()
        filter=topic
    }
    val scope=rememberCoroutineScope()
    var prefs by remember {mutableStateOf(false)}
    val stories=if(filter=="Saved") b.rows("bookmark").map { JSONObject(it.body) } else edition?.arr("stories")?.objects().orEmpty()
    val visible=stories.filter { filter in listOf("All","Saved") || it.optString("topic")==filter }
    key(d,filter){
        val positionKey="brief.position."+d+"."+filter
        val scroll=remember(positionKey){ScrollState(b.connection.getInt(positionKey,0))}
        if(visible.isNotEmpty()){
            LaunchedEffect(scroll,positionKey){
                snapshotFlow { if(!scroll.isScrollInProgress && scroll.maxValue in 1 until Int.MAX_VALUE) scroll.value else null }
                    .filterNotNull().distinctUntilChanged().collect { pos->b.connection.edit().putInt(positionKey,pos).apply() }
            }
            DisposableEffect(scroll,positionKey){
                onDispose { if(scroll.maxValue in 1 until Int.MAX_VALUE)b.connection.edit().putInt(positionKey,scroll.value).apply() }
            }
        }
        val threshold=with(LocalDensity.current){200.dp.roundToPx()}
        val reading by remember(scroll,threshold){derivedStateOf { scroll.value>threshold }}
        var choosingTopic by remember {mutableStateOf(false)}
        Box(Modifier.fillMaxSize()){
            Screen(scroll=scroll){
                Column {
                    Eyebrow(nice(d,"EEEE · MMMM d"))
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        Box(Modifier.weight(1f)){PageTitle("Tech Brief")}
                        IconAction(R.drawable.ic_sliders,"Edition preferences"){prefs=true}
                        if(d==today(b.prefs()))IconAction(R.drawable.ic_refresh,"Refresh edition",refresh)
                    }
                    if(edition!=null){
                        val count=edition.arr("stories").length()
                        Caption((if(edition.optBoolean("demo"))"Demo edition · " else "")+count+(if(count==1)" story" else " stories"))
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    options.forEach { t->FilterChip(selected=filter==t,onClick={selectTopic(t)},label={Text(t)}) }
                }
                if(filter!="Saved") edition?.arr("gaps")?.texts()?.takeIf { it.isNotEmpty() }?.let { Caption(it.joinToString("\n")) }
                if(visible.isEmpty())Body(if(filter=="Saved")"Bookmarked stories appear here." else "No stories available for this selection.")
                visible.forEachIndexed { i,s->
                    val topic=s.optString("topic")
                    if(filter=="All"){
                        if(i==0)Eyebrow("Lead story · "+topic,leaf)
                        else if(i==1 || visible[i-1].optString("topic")!=topic)Eyebrow(topic,leaf)
                    }
                    key(s.getString("url")){ Story(b,d,s,lead=i==0 && filter=="All") }
                    Rule()
                }
            }
            if(reading)Surface(modifier=Modifier.align(Alignment.TopCenter),color=paper,shadowElevation=2.dp){
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.SpaceBetween,
                    verticalAlignment=Alignment.CenterVertically){
                    TextButton(onClick={scope.launch{scroll.animateScrollTo(0)}}){Text("Edition start")}
                    Box {
                        TextButton(onClick={choosingTopic=true}){
                            Mark(R.drawable.ic_sliders,Modifier.size(16.dp));Spacer(Modifier.width(5.dp))
                            Text(if(filter=="All")"All topics" else filter)
                        }
                        DropdownMenu(expanded=choosingTopic,onDismissRequest={choosingTopic=false}){
                            options.forEach { topic->DropdownMenuItem(text={Text(topic)},onClick={choosingTopic=false;selectTopic(topic)}) }
                        }
                    }
                }
            }
        }
    }
    if(prefs)NewsSettings(b){prefs=false}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun Story(b:Book,d:LocalDate,s:JSONObject,lead:Boolean){
    LocalPages.current
    val id=identity(s.getString("url"))
    val saved=b.get("bookmark","",id).isNotBlank()
    val added=b.get("note",d.toString(),id).isNotBlank()
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text(s.getString("title"),Modifier.semantics { heading() },fontFamily=FontFamily.Serif,color=ink,
            fontSize=if(lead)32.sp else 25.sp,lineHeight=if(lead)37.sp else 31.sp,letterSpacing=(-.45).sp)
        Caption(s.optString("status")+"  ·  "+s.optString("date").ifBlank { "Release date unverified" })
        for((k,label) in listOf("what" to "What shipped","change" to "What changed","why" to "Why it matters","try" to "Try it")){
            val value=s.optString(k)
            if(value.isBlank() || k=="what" && value==s.getString("title"))continue
            if(k=="why"){
                Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(12.dp)){
                    Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                        Eyebrow(label,MaterialTheme.colorScheme.onPrimaryContainer);Body(value)
                    }
                }
            } else {
                Column(verticalArrangement=Arrangement.spacedBy(3.dp)){Text(label,color=leaf,fontSize=13.sp,fontWeight=FontWeight.SemiBold);Body(value)}
            }
        }
        Caption(s.optString("access"));Web("Primary source",s.getString("url"))
        s.arr("links").objects().filter { it.getString("url")!=s.getString("url") }.forEach { Web(it.getString("title"),it.getString("url")) }
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            TextButton(onClick={
                if(saved)b.rows("bookmark").firstOrNull { it.item==id }?.let(b::remove) else b.put("bookmark","",id,s.toString())
            }){Mark(if(saved)R.drawable.ic_check else R.drawable.ic_save,Modifier.size(16.dp));Spacer(Modifier.width(5.dp));Text(if(saved)"Bookmarked" else "Bookmark")}
            TextButton(enabled=!added,onClick={b.put("note",d.toString(),id,s.getString("title")+"\n"+s.getString("url"))}){
                Mark(if(added)R.drawable.ic_check else R.drawable.ic_journal,Modifier.size(16.dp));Spacer(Modifier.width(5.dp));Text(if(added)"In journal" else "Save to journal")
            }
        }
    }
}

