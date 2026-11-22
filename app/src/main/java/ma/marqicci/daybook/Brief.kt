package ma.marqicci.daybook

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import org.json.JSONObject
import java.time.LocalDate

@Composable fun Brief(b:Book,d:LocalDate,refresh:()->Unit){
    LocalPages.current
    val edition=b.reports(d.toString()).firstOrNull { it.optString("kind")=="tech" }
    var filter by rememberSaveable { mutableStateOf("All") };var prefs by remember { mutableStateOf(false) }
    val stories=if(filter=="Saved")b.rows("bookmark").map { JSONObject(it.body) } else edition?.arr("stories")?.objects().orEmpty()
    Screen {
        Caption(nice(d,"EEEE · MMMM d, yyyy").uppercase())
        Text("Tech Brief",fontFamily=FontFamily.Serif,fontSize=44.sp,color=ink)
        if(edition?.optBoolean("demo")==true)Caption("DEMO EDITION")
        Row {Action("Preferences"){prefs=true};if(d==today(b.prefs()))Action("Refresh edition",refresh)}
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            listOf("All","AI models","Developer tools","Robotics","Saved").forEach { t->FilterChip(selected=filter==t,onClick={filter=t},label={Text(t)}) }
        }
        edition?.arr("gaps")?.texts()?.forEach { Caption(it) }
        val visible=stories.filter { filter in listOf("All","Saved") || it.optString("topic")==filter }
        if(visible.isEmpty())Body(if(filter=="Saved")"No bookmarked stories yet." else "No stories available for this selection.")
        var last=""
        visible.forEachIndexed { i,s->
            val topic=s.optString("topic");if(filter=="All" && (i==0 || i==1 || last!=topic))Caption(if(i==0)"LEAD STORY" else topic.uppercase())
            last=topic
            key(s.getString("url")){ Story(b,d,s) }
            Rule()
        }
    }
    if(prefs)NewsSettings(b){prefs=false}
}
@Composable fun Story(b:Book,d:LocalDate,s:JSONObject){
    LocalPages.current
    val id=identity(s.getString("url"));val saved=b.get("bookmark","",id).isNotBlank();val added=b.get("note",d.toString(),id).isNotBlank()
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Caption(s.optString("status")+"  ·  "+s.optString("date").ifBlank { "Release date unverified" })
        Heading(s.getString("title"))
        listOf("what" to "What shipped","change" to "What changed","why" to "Why it matters","try" to "Try it").forEach { (key,label)->
            if(s.optString(key).isNotBlank() && !(key=="what" && s.optString(key)==s.getString("title"))){ Caption(label);Body(s.getString(key)) }
        }
        Caption(s.optString("access"));Web("Primary source",s.getString("url"))
        s.arr("links").objects().filter { it.getString("url")!=s.getString("url") }.forEach { Web(it.getString("title"),it.getString("url")) }
        Row {Action(if(saved)"Bookmarked ✓" else "Bookmark"){
            if(saved)b.rows("bookmark").firstOrNull { it.item==id }?.let(b::remove) else b.put("bookmark","",id,s.toString())
        };Action(if(added)"In journal ✓" else "Save to journal"){b.put("note",d.toString(),id,s.getString("title")+"\n"+s.getString("url"))}}
    }
}

