package ma.marqicci.daybook

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.json.*
import java.time.*
import java.time.temporal.ChronoUnit

data class Selection(val section:String,val snapshot:String,val start:Int,val end:Int){
    val quote:String get()=snapshot.substring(start,end)
}
fun parseCurriculum(raw:String):List<String>{
    val t=raw.trim().removePrefix("\uFEFF").trim()
    if(t.startsWith("[") || t.startsWith("{")){
        val a=if(t.startsWith("["))JSONArray(t) else JSONObject(t).getJSONArray("days")
        return (0 until a.length()).map { i->when(val v=a.get(i)){
            is JSONObject->listOf(v.optString("title"),v.optString("prompt",v.optString("text"))).filter { it.isNotBlank() }.joinToString("\n")
            is String->v
            else->error("Expected a prompt or a day object.")
        } }.also { require(it.isNotEmpty() && it.none(String::isBlank)){"Empty curriculum prompt"} }
    }
    val markers=Regex("""(?im)^\s*(?:#{1,6}\s*)?(?:\*\*)?(?:day|jour)\s+\d+\b[^\n]*""").findAll(t).toList()
    if(markers.isNotEmpty())return markers.mapIndexed { i,m->t.substring(m.range.first,markers.getOrNull(i+1)?.range?.first?:t.length).trim() }
    return t.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
}

@Composable fun Journal(b:Book,d:LocalDate,go:(LocalDate)->Unit,openSession:(String)->Unit,status:(String)->Unit){
    LocalPages.current
    val date=d.toString();val ctx=LocalContext.current;val scope=rememberCoroutineScope()
    var dialog by rememberSaveable { mutableStateOf("") }
    var selection by remember { mutableStateOf<Selection?>(null) }
    val saved by b.saved.collectAsState()
    val sections=b.sections()
    var focused by rememberSaveable(date){mutableStateOf<String?>(null)}
    var menu by remember {mutableStateOf(false)}
    val focusIndex=sections.indexOfFirst { it.first==focused }
    val isFocused=focusIndex>=0
    val visible=if(isFocused) listOf(sections[focusIndex]) else sections
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")){uri->
        if(uri!=null){val body=b.markdown(date);scope.launch {
            runCatching { withContext(Dispatchers.IO){requireNotNull(ctx.contentResolver.openOutputStream(uri,"wt")).bufferedWriter().use { it.write(body) }} }
                .onSuccess { status("Markdown exported.") }.onFailure { status("Export failed · "+it.message) }
        }}
    }
    Screen {
        Column(verticalArrangement=Arrangement.spacedBy(3.dp)){
            Eyebrow(nice(d,"EEEE · d MMMM yyyy"))
            PageTitle("Intellectual journal")
            StatusLine(if(b.error.value.isNotEmpty()) b.error.value else if(saved>=b.queued)"Saved on this device" else "Saving…",
                if(saved>=b.queued && b.error.value.isEmpty())R.drawable.ic_check else R.drawable.ic_clock)
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            OutlinedButton(onClick={focused=if(isFocused)null else sections.firstOrNull()?.first}){
                Mark(R.drawable.ic_focus,Modifier.size(17.dp));Spacer(Modifier.width(7.dp))
                Text(if(isFocused)"All sections" else "Focus writing")
            }
            Spacer(Modifier.weight(1f))
            IconAction(R.drawable.ic_search,"Find an entry"){dialog="search"}
            Box {
                IconAction(R.drawable.ic_more,"Journal tools"){menu=true}
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
                    for((label,key) in listOf("Questions" to "questions","Essay workshops" to "essays","Link another entry" to "link",
                        "Customize sections" to "sections","Import or edit curriculum" to "curriculum")){
                        DropdownMenuItem(text={Text(label)},onClick={menu=false;dialog=key})
                    }
                    DropdownMenuItem(text={Text("Export Markdown")},onClick={menu=false;export.launch("Journal-"+date+".md")})
                }
            }
        }
        if(isFocused){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
                TextButton(enabled=focusIndex>0,onClick={focused=sections[focusIndex-1].first}){Text("Previous")}
                Caption("Section "+(focusIndex+1)+" of "+sections.size)
                TextButton(enabled=focusIndex<sections.lastIndex,onClick={focused=sections[focusIndex+1].first}){Text("Next")}
            }
        }
        val curr=b.get("meta","","curriculum")
        if(!isFocused && curr.isNotBlank()){
            val c=JSONObject(curr);val delta=ChronoUnit.DAYS.between(LocalDate.parse(c.getString("start")),d)
            val i=if(delta<0)-1 else (delta/c.getInt("pace")).toInt()
            if(i in 0 until c.arr("days").length())CardBox { Caption("CURRICULUM · DAY "+(i+1));Body(c.getJSONArray("days").getString(i));Action("Edit curriculum"){dialog="curriculum"} }
        }
        visible.forEach { (id,title)->key(id){
            var value by remember(date,id) { mutableStateOf(TextFieldValue(b.get("draft",date,id))) }
            OutlinedTextField(value=value,onValueChange={v->
                if(v.text!=value.text)b.put("draft",date,id,v.text)
                value=v
            },label={Text(title)},modifier=Modifier.fillMaxWidth(),minLines=if(isFocused)8 else 2,
                shape=RoundedCornerShape(12.dp),textStyle=MaterialTheme.typography.bodyLarge.copy(fontFamily=FontFamily.Serif,fontSize=18.sp,lineHeight=27.sp),
                colors=OutlinedTextFieldDefaults.colors(focusedContainerColor=panel,unfocusedContainerColor=panel,unfocusedBorderColor=line))
            if(!value.selection.collapsed){
                val start=value.selection.min;val end=value.selection.max
                Action("Work with selected passage"){selection=Selection(id,value.text,start,end);dialog="feedback"}
            }
        }}
        if(isFocused) return@Screen
        Field("Tags · comma separated",b.get("tags",date,"tags"),{b.put("tags",date,"tags",it)})
        b.rows("note",date).forEach { Body(it.body) }
        val annotations=b.rows("feedback",date)
        if(annotations.isNotEmpty())Heading("Passage notes")
        annotations.forEach { row->val a=JSONObject(row.body)
            CardBox { Caption(a.getString("kind"));Body("“"+a.getString("quote")+"”")
                if(b.get("draft",date,a.getString("section"))!=a.getString("snapshot"))Caption("Attached to an earlier revision")
                Body(a.getString("response"));Web("Reading",a.optString("url"))
            }
        }
        b.rows("link",date).forEach { row->Action("Related entry · "+row.body){go(LocalDate.parse(row.body))} }
        b.rows("question",date).forEach { Body("Open question · "+it.body) }
        val sessions=b.rows("session",date)
        if(sessions.isNotEmpty()) Heading("Activity sessions")
        sessions.forEach { row->
            val session=ActivitySession.read(row.body)
            Action(session.title+" · "+if(session.complete)"Completed" else "In progress"){openSession(row.item)}
        }
    }
    when(dialog){
        "search"->Archive(b,{go(it);dialog=""},{dialog=""})
        "sections"->SectionEditor(b){dialog=""}
        "curriculum"->Curriculum(b,d,{dialog=""},status)
        "questions"->Questions(b,d,go){dialog=""}
        "essays"->Workshops(b,d,go){dialog=""}
        "link"->{var other by remember {mutableStateOf(d.minusDays(1))}
            Sheet("Link related entry",{dialog=""}){DateButton(other,{other=it});Button(enabled=other!=d,onClick={
                b.edit(listOf(Page("link",date,other.toString(),other.toString()),Page("link",other.toString(),date,date)));dialog=""
            }){Text("Link both entries")}}
        }
        "feedback"->selection?.let { s->Passage(b,date,s){dialog=""} }
    }
}

@Composable fun Archive(b:Book,open:(LocalDate)->Unit,close:()->Unit){
    val rows=LocalPages.current
    var query by remember { mutableStateOf("") }
    val dates=rows.filter { it.shelf in setOf("draft","tags","note","feedback","link","question","observation","session") && it.body.isNotBlank() }.map { it.day }.distinct().sortedDescending()
    Sheet("Journal archive",close){
        Field("Search entries, tags and notes",query,{query=it})
        val matches=dates.filter { d->d.contains(query,true) || rows.any { it.day==d && it.shelf in setOf("draft","tags","note","feedback","question","observation","session") && it.body.contains(query,true) } }
        if(matches.isEmpty())Caption("No matching entries.")
        matches.forEach { day->Action(day+" · "+b.rows("draft",day).firstOrNull { it.body.isNotBlank() }?.body.orEmpty().take(55)){open(LocalDate.parse(day))} }
    }
}
@Composable fun SectionEditor(b:Book,close:()->Unit){
    LocalPages.current
    val sections=b.sections()
    var newTitle by remember {mutableStateOf("")}
    Sheet("Journal sections",close){
        sections.forEachIndexed { i,(id,label)->key(id){
            Field("Section title",label,{v->b.saveSections(sections.map { if(it.first==id)id to v else it })})
            Row {
                if(i>0)Action("Move up"){val list=sections.toMutableList();list.add(i-1,list.removeAt(i));b.saveSections(list)}
                if(i<sections.lastIndex)Action("Move down"){val list=sections.toMutableList();list.add(i+1,list.removeAt(i));b.saveSections(list)}
            }
        }}
        Field("New section",newTitle,{newTitle=it})
        Button(enabled=newTitle.isNotBlank(),onClick={b.saveSections(sections+(uid() to newTitle.trim()));newTitle=""}){Text("Add section")}
    }
}
@Composable fun Passage(b:Book,day:String,s:Selection,close:()->Unit){
    val options=listOf("Check a premise","Test an inference","Raise an objection","Find a follow-up reading")
    var choice by remember {mutableStateOf(options.first())}
    val reading=when {
        s.quote.contains("truth",true) || s.quote.contains("Tarski",true)->"Tarski’s Truth Definitions" to "https://plato.stanford.edu/entries/tarski-truth/"
        s.quote.contains("Popper",true) || s.quote.contains("falsif",true)->"Karl Popper" to "https://plato.stanford.edu/entries/popper/"
        else->"Logical Consequence" to "https://plato.stanford.edu/entries/logical-consequence/"
    }
    val prompt=when(choice){
        options[0]->"Which claim in this passage carries the argument? State what would count as evidence against it."
        options[1]->"Write down the premises and conclusion separately. Can all the premises hold while the conclusion fails?"
        options[2]->"Construct a case that satisfies your stated assumptions and challenges the conclusion. Which assumption would rule it out?"
        else->"Read “"+reading.first+"”. Identify the definition or distinction that bears on this passage."
    }
    var thoughts by remember {mutableStateOf("")}
    Sheet("Passage workshop",close){
        Caption("Offline guided feedback");Body("“"+s.quote+"”")
        options.forEach { FilterChip(selected=it==choice,onClick={choice=it},label={Text(it)}) }
        Body(prompt);if(choice==options.last())Web(reading.first,reading.second)
        Field("Your response",thoughts,{thoughts=it},false)
        Button(onClick={val obj=JSONObject().put("section",s.section).put("snapshot",s.snapshot).put("start",s.start).put("end",s.end).put("quote",s.quote).put("kind",choice).put("response",listOf(prompt,thoughts).filter { it.isNotBlank() }.joinToString("\n")).put("url",if(choice==options.last())reading.second else "")
            b.put("feedback",day,uid(),obj.toString());close()}){Text("Attach to passage")}
    }
}
@Composable fun Questions(b:Book,d:LocalDate,go:(LocalDate)->Unit,close:()->Unit){
    LocalPages.current
    var text by remember {mutableStateOf(b.get("draft",d.toString(),"unresolved"))}
    var target by remember {mutableStateOf(d.plusDays(1))}
    Sheet("Ongoing questions",close){
        Field("Question",text,{text=it},false);DateButton(target,{target=it},"Carry to "+target)
        Button(enabled=text.isNotBlank() && target>=d,onClick={val id=identity(d.toString()+text)
            val entries=mutableListOf(Page("question",d.toString(),id,text))
            if(target!=d)entries+=listOf(Page("question",target.toString(),id,text),Page("link",target.toString(),d.toString(),d.toString()),Page("link",d.toString(),target.toString(),target.toString()))
            b.edit(entries);text=""
        }){Text("Save question")}
        b.rows("question").sortedByDescending { it.day }.forEach { row->
            CardBox { Caption(row.day);Body(row.body);Action("Open entry"){close();go(LocalDate.parse(row.day))} }
        }
    }
}
@Composable fun Workshops(b:Book,d:LocalDate,go:(LocalDate)->Unit,close:()->Unit){
    LocalPages.current
    var selected by remember {mutableStateOf("")};var title by remember {mutableStateOf("")}
    Sheet("Essay workshops",close){
        Field("New essay question",title,{title=it})
        Button(enabled=title.isNotBlank(),onClick={val id=uid();b.put("essay","",id,JSONObject().put("title",title).put("days",JSONArray(listOf(d.toString()))).put("body","").toString());selected=id;title=""}){Text("Start workshop")}
        b.rows("essay").forEach { r->val e=JSONObject(r.body);Action(e.getString("title")){selected=r.item} }
        b.rows("essay").firstOrNull { it.item==selected }?.let { row->
            val e=JSONObject(row.body)
            Heading(e.getString("title"));Field("Working argument",e.optString("body"),{b.put("essay","",row.item,e.copyJson().put("body",it).toString())},false)
            val days=e.arr("days").texts()
            if(d.toString() !in days)Action("Link this entry"){b.put("essay","",row.item,e.put("days",JSONArray(days+d.toString())).toString())}
            days.sorted().forEach { day->Action("Read linked entry · $day"){close();go(LocalDate.parse(day))} }
        }
    }
}
@Composable fun Curriculum(b:Book,d:LocalDate,close:()->Unit,status:(String)->Unit){
    val ctx=LocalContext.current;val scope=rememberCoroutineScope()
    val existing=remember { JSONObject(b.get("meta","","curriculum","{}")) }
    var prompts by remember {mutableStateOf(existing.arr("days").texts())}
    var start by remember {mutableStateOf(LocalDate.parse(existing.optString("start",d.toString())))}
    var pace by remember {mutableStateOf(existing.optString("pace","1"))};var error by remember {mutableStateOf("")}
    var paste by remember {mutableStateOf("")};var chosen by remember {mutableIntStateOf(0)}
    val file=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)scope.launch {
            runCatching {withContext(Dispatchers.IO){requireNotNull(ctx.contentResolver.openInputStream(uri)).bufferedReader().use { parseCurriculum(it.readText()) }}}.onSuccess { prompts=it;chosen=0;error="" }.onFailure {error="Import failed · "+it.message}
        }
    }
    Sheet("Reading curriculum",close){
        Caption("Import your Popper–Deutsch–Taleb plan as JSON, Markdown with Day 1 headings, or one prompt per line.")
        Action("Choose curriculum file"){file.launch(arrayOf("text/*","application/json","application/octet-stream"))}
        Field("Or paste curriculum",paste,{paste=it},false)
        if(paste.isNotBlank())Action("Preview imported days"){runCatching {parseCurriculum(paste)}.onSuccess {prompts=it;chosen=0;error=""}.onFailure {error=it.message.orEmpty()}}
        if(error.isNotEmpty())Caption(error)
        if(prompts.isNotEmpty()){
            Caption("${prompts.size} curriculum days")
            DateButton(start,{start=it},"Start · $start");Field("Calendar days per prompt",pace,{pace=it})
            Row {if(chosen>0)Action("← Previous"){chosen--};if(chosen<prompts.lastIndex)Action("Next →"){chosen++}}
            Field("Day ${chosen+1} prompt",prompts[chosen],{v->prompts=prompts.mapIndexed { i,s->if(i==chosen)v else s }},false)
            Button(enabled=pace.toIntOrNull() in 1..365 && prompts.none { it.isBlank() },onClick={b.put("meta","","curriculum",JSONObject().put("start",start.toString()).put("pace",pace.toInt()).put("days",JSONArray(prompts)).toString());status("Curriculum saved · ${prompts.size} days.");close()}){Text("Save curriculum")}
        }
    }
}

