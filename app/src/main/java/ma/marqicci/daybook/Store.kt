package ma.marqicci.daybook

import android.app.Application
import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.util.concurrent.Executors
import java.util.UUID

@Entity(tableName = "pages", primaryKeys = ["shelf","day","item"])
data class Page(val shelf:String, val day:String, val item:String, val body:String)

@Dao interface Pages {
    @Query("SELECT * FROM pages") fun all(): List<Page>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(p:Page)
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun claim(p:Page):Long
    @Query("DELETE FROM pages WHERE shelf=:s AND day=:d AND item=:i") fun remove(s:String,d:String,i:String)
}

@Database(entities=[Page::class],version=1,exportSchema=false)
abstract class BookDb:RoomDatabase(){ abstract fun pages():Pages }

fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
fun JSONArray.texts() = (0 until length()).map { getString(it) }
fun JSONObject.arr(k:String) = optJSONArray(k) ?: JSONArray()
fun JSONObject.copyJson() = JSONObject(toString())
fun JSONObject.namesList() = keys().asSequence().toList()
fun uid():String = UUID.randomUUID().toString()
fun identity(s:String):String = UUID.nameUUIDFromBytes(s.toByteArray(Charsets.UTF_8)).toString()
val initialSections = listOf("study" to "What I studied","method" to "How I studied it","argument" to "My reconstruction of the argument","unresolved" to "What remains unresolved")
fun today(p:JSONObject):LocalDate = LocalDate.now(ZoneId.of(p.getString("zone")))

class Daybook:Application(){
    lateinit var book:Book
    override fun onCreate(){
        super.onCreate()
        book=Book(this)
        SyncWorker.schedule(this)
    }
}
val Context.book:Book get()=(applicationContext as Daybook).book

class Book(val ctx:Context){
    val db=Room.databaseBuilder(ctx,BookDb::class.java,"marqicci.db").build()
    private val writer=Executors.newSingleThreadExecutor()
    val state=MutableStateFlow<List<Page>>(emptyList())
    val ready=MutableStateFlow(false)
    val saved=MutableStateFlow(0L)
    @Volatile var queued=0L
        private set
    val error=MutableStateFlow("")
    val defaults=JSONObject(ctx.assets.open("defaults.json").bufferedReader().use { it.readText() })
    val connection=ctx.getSharedPreferences("connection",Context.MODE_PRIVATE)
    init {
        writer.execute {
            val dao=db.pages()
            val rows=dao.all().toMutableList()
            if(rows.none { it.shelf=="meta" && it.item=="demoDay" }){
                val d=today(defaults)
                val seeded=mutableListOf(Page("meta","","demoDay",d.toString()))
                val examples=JSONArray(ctx.assets.open("demo.json").bufferedReader().use { it.readText() })
                examples.objects().forEach { r ->
                    r.put("demo",true).put("day",d.toString()).put("time",defaults.getJSONObject("schedule").getJSONObject(r.getString("kind")).getString("time"))
                    r.arr("events").objects().forEach { e -> e.put("deadline",d.plusDays(e.getString("deadline").substringAfter("+").toLong()).toString()) }
                    seeded.add(Page("demo",d.toString(),r.getString("kind"),r.toString()))
                }
                db.runInTransaction { seeded.forEach(dao::put) }; rows.addAll(seeded)
            }
            state.value=rows
            ready.value=true
        }
    }
    fun rows(s:String,d:String?=null)=state.value.filter { it.shelf==s && (d==null || it.day==d) }
    fun get(s:String,d:String,i:String, fallback:String="")=state.value.firstOrNull { it.shelf==s && it.day==d && it.item==i }?.body ?: fallback
    fun prefs()=JSONObject(get("meta","","prefs",defaults.toString()))
    fun reports(d:String):List<JSONObject>{
        // Once connected, illustrative data remains separately available in the demo archive.
        val live=rows("report",d)
        val selected=if(connection.getString("url","").isNullOrBlank() && live.isEmpty()) rows("demo",d) else live
        return selected.map { JSONObject(it.body) }
    }
    @Synchronized fun edit(puts:List<Page> = emptyList(), removals:List<Page> = emptyList()){
        val touched=(puts+removals).map { Triple(it.shelf,it.day,it.item) }.toSet()
        state.value=state.value.filterNot { Triple(it.shelf,it.day,it.item) in touched }+puts
        val seq=++queued
        writer.execute {
            try {
                db.runInTransaction {
                    removals.forEach { db.pages().remove(it.shelf,it.day,it.item) }
                    puts.forEach { db.pages().put(it) }
                }
                saved.value=seq
            } catch(e:Exception){ error.value="Device save failed: "+e.javaClass.simpleName }
        }
    }
    fun put(s:String,d:String,i:String,v:String) = edit(listOf(Page(s,d,i,v)))
    fun remove(p:Page)=edit(removals=listOf(p))
    fun flush(){ writer.submit { }.get() }
    @Synchronized fun settings(fn:(JSONObject)->Unit){
        val p=prefs(); fn(p)
        edit(listOf(Page("meta","","prefs",p.toString()),Page("meta","","pending",uid())))
        Bells.reset(ctx)
        SyncWorker.request(ctx)
    }
    @Synchronized fun acknowledge(revision:String){
        if(get("meta","","pending")==revision) put("meta","","pending","")
    }
    @Synchronized fun receivedPrefs(p:JSONObject){
        if(get("meta","","pending").isNotEmpty()) return
        val cached=prefs()
        val rules=p.getJSONObject("schedule")
        // Older servers return only the original four schedule fields.
        for(id in rules.namesList()){
            val old=cached.getJSONObject("schedule").optJSONObject(id) ?: continue
            val incoming=rules.getJSONObject(id)
            for(key in listOf("weekdays","prompts")){
                if(!incoming.has(key) && old.has(key)) incoming.put(key,old.get(key))
            }
        }
        if(cached.toString()!=p.toString()){
            put("meta","","prefs",p.toString()); Bells.reset(ctx)
        }
    }
    fun sections():List<Pair<String,String>>{
        val data=get("meta","","sections")
        return if(data.isBlank()) initialSections else JSONArray(data).objects().map { it.getString("id") to it.getString("label") }
    }
    fun saveSections(s:List<Pair<String,String>>) = put("meta","","sections",JSONArray(s.map { JSONObject().put("id",it.first).put("label",it.second) }).toString())
    fun markdown(d:String):String=buildString {
        appendLine("# Journal · $d"); appendLine(); appendLine(get("tags",d,"tags"))
        sections().forEach { (id,title)->appendLine("\n## $title\n"); appendLine(get("draft",d,id)) }
        rows("session",d).map { ActivitySession.read(it.body) }.sortedBy { it.time }.forEach { session->
            appendLine("\n## "+session.title+" · "+session.time+"\n")
            appendLine(if(session.complete)"Completed" else "In progress")
            session.prompts.zip(session.answers).forEach { (prompt,answer)->appendLine("\n### $prompt\n");appendLine(answer) }
        }
        for (s in listOf("note","feedback","link","question","observation")){
            val records=rows(s,d)
            if(records.isNotEmpty()) appendLine("\n## "+s.replaceFirstChar { it.uppercase() }+"\n")
            records.forEach { r->
                if(s=="feedback"){
                    val a=JSONObject(r.body)
                    a.getString("quote").lineSequence().forEach { appendLine("> "+it) }
                    appendLine()
                    appendLine(a.getString("kind")+" · "+a.getString("response"))
                    a.optString("url").takeIf { it.isNotBlank() }?.let { appendLine();appendLine(it) }
                }
                else appendLine(r.body)
                appendLine()
            }
        }
        rows("essay").forEach { r->val e=JSONObject(r.body)
            if(d in e.arr("days").texts()) appendLine("\n## "+e.getString("title")+"\n\n"+e.optString("body"))
        }
    }
}

