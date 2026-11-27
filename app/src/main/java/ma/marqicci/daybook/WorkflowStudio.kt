package ma.marqicci.daybook

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.*
import org.threeten.bp.LocalDate

private val stepPrefix = Regex("""^\s*(?:[-*•]|\d{1,2}[.)])\s+""")

fun readProposedSteps(output: String): List<String> {
    val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val listed = lines.filter { stepPrefix.containsMatchIn(it) }
    val steps = (listed.ifEmpty { lines }).map { it.replaceFirst(stepPrefix, "").trim() }
    require(steps.size in 2..8 && steps.all { it.length in 2..100 } && steps.distinct().size == steps.size) {
        "The model did not return usable steps. Try a more specific goal or enter the steps yourself."
    }
    return steps
}

@Composable fun WorkflowStudio(b: Book, day: LocalDate, close: () -> Unit, open: (String) -> Unit) {
    LocalPages.current
    val rules = b.prefs().getJSONObject("schedule")
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var setup by rememberSaveable { mutableStateOf(false) }
    val model = b.phoneModel
    val state by model.status.collectAsState()
    val workflows = rules.namesList().filter { it.startsWith("activity-") }.sortedBy { rules.getJSONObject(it).optString("title") }
    Screen {
        Action("← Back", close)
        PageTitle("Workflows")
        Body("Build a repeatable routine from a goal. Each run keeps its own notes and results.")
        Button(onClick = { editor = "new" }) { Text("Design a workflow") }
        OutlinedButton(onClick = { setup = true }) {
            Text(if (state.ready) "Phone model · installed" else "Set up phone model")
        }
        Heading("Your workflows")
        if (workflows.isEmpty()) Caption("Start with a project, study session, review, or your own steps.")
        workflows.forEach { id ->
            val rule = rules.getJSONObject(id)
            CardBox {
                Heading(rule.getString("title"))
                Caption(rule.arr("prompts").texts().joinToString(" → "))
                Caption(if (rule.optBoolean("enabled")) describeDays(rule.weekdays()) + " · " + rule.optString("time") else "Schedule paused")
                Action("Open run · " + nice(day, "MMM d")) { open(id) }
                Action("Edit workflow") { editor = id }
            }
        }
    }
    if (setup) ModelSetup(model) { setup = false }
    editor?.let { key -> ActivityEditor(b, key.takeUnless { it == "new" }, smart = true) { editor = null } }
}

@Composable private fun ModelSetup(model: PhoneModel, close: () -> Unit) {
    val state by model.status.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.install(uri) }
    Sheet("Phone model", close) {
        Heading("SmolLM2 · 360M")
        Body("The English instruction model drafts workflows and works through your steps on this phone. After setup you can use it in airplane mode.")
        when {
            !model.supported -> Caption("Model inference requires a 64-bit phone. You can create and run workflows manually here.")
            state.ready -> StatusLine("Verified file installed · ready offline")
            state.copiedMB != null -> {
                LinearProgressIndicator(progress = { (state.copiedMB!! / 271f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Caption("Installing · ${state.copiedMB} of 271 MB")
                Action("Cancel transfer") { model.cancelInstall() }
            }
            else -> {
                Caption("Allow 280 MB of free storage. Generation speed depends on the phone.")
                Button(onClick = { model.install() }) { Text("Download · 271 MB") }
                Action("Import matching GGUF file") { picker.launch(arrayOf("*/*")) }
            }
        }
        if (state.error.isNotEmpty()) Body(state.error)
        Web("Model details and license", "https://huggingface.co/bartowski/SmolLM2-360M-Instruct-GGUF")
    }
}

@Composable fun WorkflowDesigner(model: PhoneModel, apply: (String, List<String>) -> Unit) {
    var goal by rememberSaveable { mutableStateOf("") }
    var setup by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var preview by rememberSaveable { mutableStateOf("") }
    var previewGoal by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val status by model.status.collectAsState()
    Field("What should this workflow help you do?", goal, { goal = it.take(500) }, false)
    if (status.ready) {
        Button(enabled = goal.isNotBlank() && job == null, onClick = {
            val request = goal.trim()
            job = scope.launch {
                try {
                    error = ""
                    val result = model.generate("Design a repeatable workflow. Return exactly three short numbered instructions, one per line. First collect the user's notes, then transform them, then produce a useful result. No introduction.", request, limit = 200, planning = true)
                    preview = readProposedSteps(result).joinToString("\n")
                    previewGoal = request
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "Could not draft the workflow." }
                finally { job = null }
            }
        }) { Text(if (job == null) "Propose steps" else "Drafting on phone…") }
        if (job != null) Action("Stop drafting") { job?.cancel() }
    } else Action("Set up model to propose steps") { setup = true }
    if (preview.isNotEmpty()) Sheet("Proposed workflow", { preview = "" }, "Discard") {
        preview.lines().forEachIndexed { i, value -> Body("${i + 1}. $value") }
        Button(onClick = {
            apply(previewGoal, preview.lines())
            preview = ""
        }) { Text("Use these steps") }
    }
    if (error.isNotBlank()) Caption(error)
    if (setup) ModelSetup(model) { setup = false }
}

suspend fun draftRemaining(session: ActivitySession, onStep: (Int) -> Unit, generate: suspend (String, String) -> String): ActivitySession {
    require(session.answers.size == session.prompts.size && !session.complete)
    require(session.answers.first().isNotBlank()) { "Enter your material in the first step." }
    val answers = session.answers.toMutableList()
    for (i in 1 until answers.size) {
        if (answers[i].isNotBlank()) continue
        onStep(i)
        val notes = (0 until i).joinToString("\n\n") { j -> session.prompts[j] + "\n" + answers[j] }
        val input = "Workflow: " + session.title + "\nEarlier steps:\n" + notes + "\nCurrent instruction: " + session.prompts[i]
        answers[i] = generate("Complete the current instruction using the earlier steps. Be concise. Preserve the supplied facts. Identify missing information when necessary.", input)
    }
    return session.copy(answers = answers)
}

@Composable fun RunAssistant(b: Book, session: ActivitySession, current: () -> ActivitySession, save: (ActivitySession) -> Unit) {
    var original by remember { mutableStateOf<ActivitySession?>(null) }
    var proposed by remember { mutableStateOf<ActivitySession?>(null) }
    var message by remember { mutableStateOf("") }
    var step by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    var setup by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val model = b.phoneModel
    val status by model.status.collectAsState()
    if (session.answers.drop(1).none { it.isBlank() }) return
    CardBox {
        Heading("Run with phone model")
        Caption("Enter source material in the first step. The model drafts the empty steps in order, using each result in the next step.")
        if (!status.ready) Action("Set up phone model") { setup = true }
        else if (job != null) {
            Caption("Drafting step ${step + 1} of ${session.prompts.size}…")
            Action("Stop generation") { job?.cancel() }
        } else Button(enabled = session.answers.first().isNotBlank() && proposed == null, onClick = {
            original = session
            job = scope.launch {
                try {
                    message = ""
                    proposed = draftRemaining(session, { step = it }) { instruction, input -> model.generate(instruction, input) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = e.message ?: "Generation failed. Your notes are saved." }
                finally { job = null }
            }
        }) { Text("Draft remaining steps") }
        if (message.isNotBlank()) Caption(message)
    }
    if (setup) ModelSetup(model) { setup = false }
    proposed?.let { draft ->
        Sheet("Review generated steps", { proposed = null }, "Discard") {
            draft.answers.forEachIndexed { i, value ->
                if (original?.answers?.get(i).isNullOrBlank())
                    Field(draft.prompts[i], value, { edited ->
                        proposed = draft.copy(answers = draft.answers.toMutableList().apply { set(i, edited) })
                    }, false)
            }
            Button(onClick = {
                if (current() == original) save(draft)
                else message = "The run changed during generation. Draft again using your latest notes."
                proposed = null
            }) { Text("Apply to this run") }
        }
    }
}
