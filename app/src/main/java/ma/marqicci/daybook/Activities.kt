package ma.marqicci.daybook

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

val everyDay = (1..7).toSet()

fun JSONObject.weekdays(): Set<Int> {
    val values = optJSONArray("weekdays") ?: return everyDay
    return (0 until values.length()).map { values.getInt(it) }.toSet()
}

fun describeDays(days: Set<Int>): String = when (days) {
    everyDay -> "Every day"
    (1..5).toSet() -> "Weekdays"
    setOf(6, 7) -> "Weekends"
    else -> days.sorted().joinToString(", ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
}

private val starters = linkedMapOf(
    "Custom" to listOf("Notes", "Next step"),
    "Study" to listOf("Learning goal", "What I worked out", "Question to revisit"),
    "Project" to listOf("What I planned to build", "What I tried", "Next experiment"),
    "Writing" to listOf("Working question", "Draft", "Next revision"),
    "Review" to listOf("What changed", "What needs attention", "Next action")
)

data class ActivitySession(
    val title: String, val time: String, val prompts: List<String>,
    val answers: List<String>, val complete: Boolean
) {
    fun excerpt(): String = prompts.zip(answers).lastOrNull { it.second.isNotBlank() }
        ?.let { (prompt,answer) -> prompt+" · "+answer.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(100) }.orEmpty()

    fun json(): String = JSONObject().put("title", title).put("time", time)
        .put("prompts", JSONArray(prompts)).put("answers", JSONArray(answers))
        .put("complete", complete).toString()

    companion object {
        fun fromRule(rule: JSONObject): ActivitySession {
            val prompts = rule.arr("prompts").texts().ifEmpty { starters.getValue("Custom") }
            return ActivitySession(rule.getString("title"), rule.getString("time"), prompts, prompts.map { "" }, false)
        }

        fun read(text: String): ActivitySession {
            val s = JSONObject(text)
            return ActivitySession(s.getString("title"), s.getString("time"), s.arr("prompts").texts(),
                s.arr("answers").texts(), s.getBoolean("complete"))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun WeekdaySelector(days: Set<Int>, change: (Set<Int>) -> Unit) {
    Caption("Repeat on")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DayOfWeek.values().forEach { d ->
                val selected = d.value in days
                FilterChip(selected = selected, onClick = { change(if (selected) days - d.value else days + d.value) },
                    label = { Text(d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)) })
            }
    }
    if (days.isEmpty()) Caption("Select at least one day.")
}

@Composable fun ActivityEditor(b: Book, id: String?, close: () -> Unit) {
    val original = remember(id) { id?.let { b.prefs().getJSONObject("schedule").optJSONObject(it) } }
    var title by rememberSaveable { mutableStateOf(original?.optString("title").orEmpty()) }
    var clock by rememberSaveable { mutableStateOf(original?.optString("time") ?: "12:00") }
    var repeat by rememberSaveable { mutableStateOf((original?.weekdays() ?: everyDay).sorted().joinToString(",")) }
    val days = repeat.split(",").mapNotNull { it.toIntOrNull() }.toSet()
    var template by rememberSaveable { mutableStateOf("Custom") }
    var lines by rememberSaveable { mutableStateOf((original?.arr("prompts")?.texts().orEmpty()
        .ifEmpty { starters.getValue("Custom") }).joinToString("\n")) }
    val prompts = lines.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val valid = title.isNotBlank() && title.trim().length <= 100 && days.isNotEmpty() &&
        prompts.size in 1..8 && prompts.all { it.length <= 100 } && prompts.distinct().size == prompts.size
    Sheet(if (id == null) "New activity" else "Edit activity", close, closeLabel = "Cancel") {
        if (id == null) {
            Caption("Start from a template")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                starters.keys.forEach { name ->
                    FilterChip(selected = template == name, onClick = {
                        template = name
                        lines = starters.getValue(name).joinToString("\n")
                        if (title.isBlank() || title in starters.keys) title = if (name == "Custom") "" else name
                    }, label = { Text(name) })
                }
            }
        }
        Field("Activity name", title, { title = it.take(100) })
        ClockButton("Time", clock) { clock = it }
        WeekdaySelector(days) { repeat = it.sorted().joinToString(",") }
        Field("Session prompts · one per line", lines, { lines = it }, false)
        if (prompts.size !in 1..8 || prompts.any { it.length > 100 } || prompts.distinct().size != prompts.size)
            Caption("Use 1–8 distinct prompts, up to 100 characters each.")
        Button(enabled = valid, onClick = {
            val key = id ?: "activity-" + uid()
            b.settings { prefs ->
                val schedule = prefs.getJSONObject("schedule")
                val rule = schedule.optJSONObject(key) ?: JSONObject().put("enabled", true).put("notify", false)
                schedule.put(key, rule.put("title", title.trim()).put("time", clock)
                    .put("weekdays", JSONArray(days.sorted())).put("prompts", JSONArray(prompts)))
            }
            close()
        }) { Text(if (id == null) "Create activity" else "Save changes") }
    }
}

@Composable fun SessionScreen(b: Book, day: LocalDate, id: String, backLabel: String, back: () -> Unit) {
    LocalPages.current
    val date = day.toString()
    val stored = b.get("session", date, id)
    val rule = b.prefs().getJSONObject("schedule").optJSONObject(id)
    val session = when {
        stored.isNotBlank() -> ActivitySession.read(stored)
        rule != null -> ActivitySession.fromRule(rule)
        else -> null
    }
    fun save(value: ActivitySession) = b.put("session", date, id, value.json())
    Screen {
        Action(backLabel, back)
        if (session == null) { Body("This activity is unavailable."); return@Screen }
        Caption(nice(day, "EEEE, MMMM d, yyyy") + " · " + session.time)
        Heading(session.title)
        Caption(if (session.complete) "Completed" else if (stored.isEmpty()) "Planned" else "In progress")
        session.prompts.forEachIndexed { i, prompt ->
            key(i) {
                if (session.complete) {
                    Caption(prompt); Body(session.answers[i].ifBlank { "—" })
                } else {
                    Field(prompt, session.answers[i], { text ->
                        // Read the newest draft even when multiple input events precede recomposition.
                        val latest = b.get("session", date, id).takeIf { it.isNotEmpty() }?.let { ActivitySession.read(it) } ?: session
                        val answers = latest.answers.toMutableList()
                        answers[i] = text
                        save(latest.copy(answers = answers))
                    }, false)
                }
            }
        }
        if (stored.isEmpty()) Action("Start session") { save(session) }
        Button(onClick = {
            val latest = b.get("session", date, id).takeIf { it.isNotEmpty() }?.let { ActivitySession.read(it) } ?: session
            save(latest.copy(complete = !session.complete))
        }) { Text(if (session.complete) "Reopen session" else "Mark complete") }
        if (stored.isNotEmpty()) {
            val saved by b.saved.collectAsState()
            Caption(if (b.error.value.isNotEmpty()) b.error.value else if (saved >= b.queued) "Saved on this device" else "Saving…")
        }
    }
}

