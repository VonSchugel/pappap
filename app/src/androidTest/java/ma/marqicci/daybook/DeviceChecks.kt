package ma.marqicci.daybook

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.*
import org.junit.runner.RunWith
import org.threeten.bp.*
import org.json.JSONObject
import java.util.Locale
import org.threeten.bp.format.TextStyle

@RunWith(AndroidJUnit4::class)
class DeviceChecks {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    val ctx:Context get()=ApplicationProvider.getApplicationContext()
    val sample="An offline premise survives an Android process restart."
    fun openJournal(){ui.waitUntil(10000){ctx.book.ready.value};ui.onNodeWithText("Continue journal",substring=true).performClick()}

    @Test fun verifyOfflineTransports(){
        val network=ctx.getSystemService(android.net.ConnectivityManager::class.java)
        ui.waitUntil(15000){network.activeNetwork==null}
    }

    @Test fun writeOffline(){
        openJournal()
        ui.onNodeWithText("What I studied").performScrollTo().performTextReplacement(sample)
        ui.waitUntil(10000){ ctx.book.get("draft",today(ctx.book.prefs()).toString(),"study")==sample && ctx.book.saved.value>=ctx.book.queued }
    }
    @Test fun readAfterForceStop(){
        openJournal()
        ui.onNodeWithText(sample).assertExists()
    }
    @Test fun editScheduleAndRetryBell(){
        val b=ctx.book;ui.waitUntil(10000){b.ready.value}
        val now=ZonedDateTime.now(ZoneId.of(b.prefs().getString("zone"))).withSecond(0).withNano(0)
        b.settings {it.getJSONObject("schedule").getJSONObject("weather").put("time","11:47")}
        val a=activities(b).single{it.id=="weather"}
        Assert.assertEquals(LocalTime.of(11,47),a.next(now)!!.toLocalTime())
        b.settings {it.getJSONObject("schedule").getJSONObject("weather").put("enabled",false)}
        Assert.assertFalse(activities(b).any{it.id=="weather"})
        val id=uid();val d=now.toLocalDate().toString()
        b.put("reminder",d,id,JSONObject().put("title","Test reminder").put("time",now.toLocalTime().toString()).put("notify",true).toString());b.flush()
        val event=Intent(ctx,Bell::class.java).setData(Uri.parse("marqicci://alarm/$id")).putExtra("at",now.toInstant().toEpochMilli())
        repeat(2){ctx.sendBroadcast(event)}
        val nm=ctx.getSystemService(NotificationManager::class.java)
        ui.waitUntil(10000){nm.activeNotifications.count { it.tag==id+d }==1}
        Bells.background.submit { }.get()
        Assert.assertEquals(1,b.db.pages().all().count { it.shelf=="delivered" && it.day==d && it.item==id })
        Assert.assertEquals(1,nm.activeNotifications.count { it.tag==id+d })
    }
    @Test fun import28Days(){
        val plan=(1..28).joinToString("\n"){"## Day $it\nInvestigate argument $it.\n"}
        Assert.assertEquals(28,parseCurriculum(plan).size)
        Assert.assertTrue(parseCurriculum(plan).last().contains("argument 28"))
    }

    @Test fun createProjectSessionOffline(){
        val b=ctx.book
        ui.waitUntil(10000){b.ready.value}
        ui.onNodeWithText("Schedule").performClick()
        ui.onNodeWithText("Add recurring activity").performScrollTo().performClick()
        ui.onNodeWithText("Project",useUnmergedTree=false).performClick()
        ui.onNodeWithText("Activity name").performScrollTo().performTextReplacement("Bench experiment")
        val day=today(b.prefs())
        val skipped=day.plusDays(1).dayOfWeek
        ui.onNodeWithText(skipped.getDisplayName(TextStyle.SHORT,Locale.ENGLISH)).performScrollTo().performClick()
        ui.onNodeWithText("Create activity").performScrollTo().performClick()
        ui.waitUntil(5000){ui.onAllNodesWithTag("nav-0").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("nav-0").performClick()
        ui.onNodeWithText("Bench experiment").performScrollTo().performClick()
        ui.onNodeWithText("What I tried").performScrollTo().performTextReplacement("Measured five step responses.")
        val id=activities(b).single{it.title=="Bench experiment"}.id
        Assert.assertFalse(skipped.value in activities(b).single{it.id==id}.weekdays)
        ui.waitUntil(10000){b.get("session",day.toString(),id).contains("five step responses") && b.saved.value>=b.queued}
        val persisted=b.db.pages().all().single { it.shelf=="session" && it.day==day.toString() && it.item==id }
        Assert.assertTrue(ActivitySession.read(persisted.body).answers.contains("Measured five step responses."))
    }

    @Test fun resumeAndCompleteAfterRestart(){
        val b=ctx.book
        ui.waitUntil(10000){b.ready.value}
        val day=today(b.prefs())
        val id=activities(b).single{it.title=="Bench experiment"}.id
        val prior=day.minusDays(1).toString()
        val snapshot=b.get("session",day.toString(),id)
        Assert.assertTrue("Missing saved measurements for "+day+"; sessions="+b.rows("session").map { it.day to ActivitySession.read(it.body).answers },snapshot.contains("five step responses"))
        b.put("session",prior,id,snapshot)
        b.settings { it.getJSONObject("schedule").getJSONObject(id)
            .put("title","New experiment").put("prompts",org.json.JSONArray(listOf("New question"))).put("enabled",false) }
        ui.onNodeWithText("Resume Bench experiment · "+prior).performScrollTo().performClick()
        ui.onNodeWithText("Measured five step responses.").assertExists()
        ui.onNodeWithText(nice(LocalDate.parse(prior),"EEEE, MMMM d, yyyy")+" · "+ActivitySession.read(snapshot).time)
            .assertExists("Expected session for "+prior+". Screen after opening: "+ui.onRoot().printToString())
        ui.onNodeWithText("Mark complete").performScrollTo().assertIsDisplayed().performClick()
        ui.waitForIdle()
        Assert.assertTrue("Expected completed session on "+prior+"; records="+b.rows("session").map { it.day to ActivitySession.read(it.body).complete },
            ActivitySession.read(b.get("session",prior,id)).complete)
        Assert.assertFalse(ActivitySession.read(b.get("session",day.toString(),id)).complete)
        Assert.assertTrue(b.markdown(prior).contains("### What I tried\n\nMeasured five step responses."))
        ui.onNodeWithText("← Agenda").performScrollTo().performClick()
        ui.onNodeWithText("Return to today").performScrollTo().performClick()
        ui.onNodeWithText("Resume Bench experiment · "+prior).assertDoesNotExist()
        ui.onNodeWithText("Bench experiment").performScrollTo().performClick()
        ui.onNodeWithText("What I tried").assertExists()
        ui.onNodeWithText("New question").assertDoesNotExist()
    }

    @Test fun nextWeeklyRunAndLegacyDaily(){
        val zone=ZoneId.of("Africa/Casablanca")
        val after=LocalDate.of(2026,11,27).atTime(10,0).atZone(zone)
        val rule=ActivityTime("lab","Lab",LocalTime.of(9,0),null,true,setOf(1,5))
        Assert.assertEquals(LocalDate.of(2026,11,30),rule.next(after)!!.toLocalDate())
        Assert.assertEquals(LocalDate.of(2026,11,28),rule.copy(weekdays=JSONObject().weekdays()).next(after)!!.toLocalDate())
        val legacy=ActivitySession.fromRule(JSONObject().put("title","Daily").put("time","09:00"))
        Assert.assertEquals(listOf("Notes","Next step"),legacy.prompts)
    }

    private fun capture(name:String){
        ui.waitForIdle()
        val bitmap=checkNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            java.io.File(ctx.cacheDir,"ui-"+name+".png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))
            }
        } finally { bitmap.recycle() }
    }

    @Test fun captureScreens(){
        ui.waitUntil(10000){ctx.book.ready.value}
        for((index,name) in listOf("today","schedule","brief","journal").withIndex()){
            ui.onNodeWithTag("nav-"+index).performClick()
            capture(name)
        }
        ui.onNodeWithText("Focus writing").performClick()
        capture("focus")
    }

    @Test fun largerDarkToday(){
        ui.waitUntil(10000){ctx.book.ready.value}
        ui.onNodeWithText("Continue journal").assertIsDisplayed()
        ui.onNodeWithTag("nav-3").assertIsDisplayed()
        capture("dark-large")
    }

    @Test fun focusedJournalUsesDraft(){
        openJournal()
        ui.onNodeWithText("Focus writing").performClick()
        ui.onNodeWithText(sample).assertExists()
        ui.onNodeWithText("How I studied it").assertDoesNotExist()
        ui.onNodeWithText("Next").performClick()
        ui.onNodeWithText("How I studied it").performScrollTo().performTextReplacement("Compared two arguments.")
        ui.waitUntil(10000){ctx.book.get("draft",today(ctx.book.prefs()).toString(),"method")=="Compared two arguments."}
        ui.onNodeWithText("Previous").assertIsDisplayed().performClick()
        ui.onNodeWithText(sample).assertExists()
        ui.onNodeWithText("All sections").performScrollTo().performClick()
        ui.onNodeWithText("Compared two arguments.").assertExists()
        ui.onNodeWithContentDescription("Journal tools").performScrollTo().performClick()
        ui.onNodeWithText("Export Markdown").assertExists()
    }

    @Test fun scheduleAndHourlyDisclosure(){
        ui.waitUntil(10000){ctx.book.ready.value}
        ui.onNodeWithTag("nav-1").performClick()
        ui.onNodeWithText("Notify on device").assertDoesNotExist()
        ui.onNodeWithText("Mohammedia weather").performClick()
        ui.onNodeWithText("Notify on device").assertExists()
        ui.onNodeWithText("Mohammedia weather").performClick()
        ui.onNodeWithText("Notify on device").assertDoesNotExist()
        ui.onNodeWithTag("nav-0").performClick()
        ui.onNodeWithText("Mohammedia weather").performScrollTo().performClick()
        ui.onNodeWithText("00:00",substring=true).assertDoesNotExist()
        ui.onNodeWithText("Hourly forecast").performScrollTo().performClick()
        ui.onNodeWithText("00:00",substring=true).assertExists()
    }

    private fun reader()=ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))

    private fun assertReadingPosition(key:String, expected:Double){
        val action=ui.onNodeWithText("Save to journal")
        ui.waitForIdle()
        try {
            ui.waitUntil(8000){
                runCatching {
                    val axis=reader().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                    action.assertIsDisplayed()
                    kotlin.math.abs(axis.value()/axis.maxValue()-expected)<0.025
                }.getOrDefault(false)
            }
        } catch(failure:Throwable){
            val axis=runCatching {reader().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]}.getOrNull()
            throw AssertionError("Saved offset="+ctx.book.connection.getInt(key,0)+" saved range="+ctx.book.connection.getInt(key+".range",0)+
                " expected fraction="+expected+" actual offset="+axis?.value?.invoke()+" actual range="+axis?.maxValue?.invoke()+
                ". "+ui.onRoot().printToString(),failure)
        }
    }

    @Test fun resumeEditionAfterRestart(){
        val d=today(ctx.book.prefs()).toString()
        ui.waitUntil(10000){ctx.book.ready.value}
        ui.onNodeWithTag("nav-2").performClick()
        ui.onNodeWithText("Robotics").performScrollTo().performClick()
        ui.onNodeWithText("Save to journal").performScrollTo()
        reader().performSemanticsAction(SemanticsActions.ScrollBy){it(0f,10000f)}
        ui.onNodeWithText("Save to journal").assertIsDisplayed()
        val key="brief.position."+d+".Robotics"
        ui.waitUntil(10000){ctx.book.connection.getInt(key,0)>0 && ctx.book.connection.getInt(key,0)==ctx.book.connection.getInt(key+".range",-1)}
        val position=1.0
        ui.activityRule.scenario.recreate()
        assertReadingPosition(key,position)
        ui.onNodeWithTag("nav-0").performClick()
        ui.onNodeWithTag("nav-2").performClick()
        assertReadingPosition(key,position)
    }

    @Test fun reopenBriefAfterProcessStop(){
        ui.waitUntil(10000){ctx.book.ready.value}
        val key="brief.position."+today(ctx.book.prefs())+".Robotics"
        val position=ctx.book.connection.getInt(key,0).toDouble()/ctx.book.connection.getInt(key+".range",1)
        Assert.assertEquals(1.0,position,0.025)
        ui.onNodeWithTag("nav-2").performClick()
        assertReadingPosition(key,position)
        if(ui.onAllNodesWithText("Edition start").fetchSemanticsNodes().isNotEmpty()){
            ui.onNodeWithText("Edition start").performClick()
        }
        ui.onNode(isSelectable() and hasText("Robotics")).performScrollTo().assertIsSelected()
    }

    @Test fun archiveFindsEssayOnlyDate(){
        val b=ctx.book
        ui.waitUntil(10000){b.ready.value}
        val day=today(b.prefs()).minusDays(13)
        val text="Constraint from the second experiment"
        b.put("essay","","archive-qa",JSONObject().put("title","Workshop notebook").put("body",text)
            .put("days",org.json.JSONArray(listOf(day.toString()))).toString())
        openJournal()
        ui.onNodeWithContentDescription("Find an entry").performClick()
        ui.onNodeWithText("Search entries, tags and notes").performTextReplacement(text)
        ui.onNode(hasText(day.toString(),substring=true) and hasClickAction()).performClick()
        ui.onNodeWithText(nice(day,"MMM d, yyyy")).assertExists()
        Assert.assertTrue(b.markdown(day.toString()).contains(text))
    }

    @Test fun passageExportRetainsReadingAndQuotedLines(){
        val b=ctx.book
        ui.waitUntil(10000){b.ready.value}
        val day=today(b.prefs()).minusDays(3).toString()
        val quote="Premise one\nPremise two"
        val source="https://plato.stanford.edu/entries/tarski-truth/"
        val note=JSONObject().put("quote",quote).put("kind","Find a follow-up reading")
            .put("snapshot",quote).put("section","study").put("response","Check the definition.").put("url",source)
        b.put("draft",day,"study",quote+"\nMy conclusion.")
        b.put("feedback",day,"qa-passage",note.toString())
        val md=b.markdown(day)
        Assert.assertTrue("Missing reading URL",md.contains(source))
        Assert.assertTrue("Every selected line should be quoted",md.contains("> Premise one\n> Premise two"))
        Assert.assertEquals(quote+"\nMy conclusion.",b.get("draft",day,"study"))
    }

    @Test fun datedBookmarksAndBeachObservations(){
        val b=ctx.book
        ui.waitUntil(10000){b.ready.value}
        val day=today(b.prefs()).toString()
        val story=b.reports(day).single { it.getString("kind")=="tech" }.arr("stories").objects().first {it.getString("topic")=="Robotics"}
        val id=identity(story.getString("url"))
        ui.onNodeWithTag("nav-2").performClick()
        // The topic selector also has a button in the pinned reading controls.
        if(ui.onAllNodesWithText("Edition start").fetchSemanticsNodes().isNotEmpty())ui.onNodeWithText("Edition start").performClick()
        ui.onNode(isSelectable() and hasText("Robotics")).performScrollTo().performClick()
        ui.onNodeWithText("Bookmark").performScrollTo().performClick()
        ui.onNodeWithText("Save to journal").performScrollTo().performClick()
        Assert.assertEquals(story.getString("title")+"\n"+story.getString("url"),b.get("note",day,id))
        Assert.assertEquals(1,b.rows("bookmark").count {it.item==id})
        ui.onNodeWithTag("nav-0").performClick()
        ui.onNodeWithText("Mannesmann swim check").performScrollTo().performClick()
        ui.onNodeWithText("Waves, gap flow, flags and exit").performScrollTo().performTextReplacement("Observed posted flag at the exit.")
        ui.onNodeWithText("Save observation").performScrollTo().performClick()
        ui.waitUntil(10000){b.rows("observation",day).any { it.body.contains("Observed posted flag") }}
        Assert.assertTrue(b.markdown(day).contains("Observed posted flag"))
    }

    @Test fun malformedEventDeadlineCannotCreateAnAlarm(){
        val b=ctx.book
        ui.waitUntil(10000){b.ready.value}
        val day=today(b.prefs()).toString()
        val e=JSONObject().put("title","Test event").put("url","https://example.org/fixture-event")
            .put("mode","Remote").put("eligibility","Reviewed test entry").put("deadline","2026-02-30").put("time","25:68")
        val report=JSONObject().put("kind","hacks").put("title","Hackathon digest").put("summary","One fixture")
            .put("events",org.json.JSONArray().put(e))
        val row=Page("report",day,"hacks",report.toString())
        b.edit(listOf(row))
        try {
            ui.onNodeWithText("Hackathon digest").performScrollTo().performClick()
            ui.onNodeWithText("Application deadline unverified").assertExists()
            ui.onNodeWithText("Add deadline to Schedule").assertDoesNotExist()
        } finally { b.remove(row);b.flush() }
    }

    @Test fun rapidOfflineEditsStayOnTheirDate(){
        val b=ctx.book
        ui.waitUntil(10000){b.ready.value}
        val first=today(b.prefs()).minusDays(20).toString()
        val second=today(b.prefs()).minusDays(19).toString()
        repeat(120){n->
            b.put("draft",first,"study","Argument A "+n)
            b.put("draft",second,"study","Argument B "+n)
        }
        b.flush()
        val persisted=b.db.pages().all().filter { it.shelf=="draft" && it.item=="study" }
        Assert.assertEquals("Argument A 119",persisted.single{it.day==first}.body)
        Assert.assertEquals("Argument B 119",persisted.single{it.day==second}.body)
    }

    @Test fun realServerRecoversPendingChanges(){
        val b=ctx.book
        ui.waitUntil(10000){b.ready.value}
        val args=androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val token=requireNotNull(args.getString("apiToken"))
        val day=today(b.prefs()).toString()
        b.settings { p->
            val sources=p.getJSONObject("sources")
            sources.namesList().forEach { sources.put(it,false) }
            p.getJSONObject("schedule").getJSONObject("tech").put("time","08:17")
        }
        b.connection.edit().putString("url","http://127.0.0.1:18731").putString("key","wrong-credential").commit()
        try {
            val rejected=runCatching { Api.sync(b,day) }.exceptionOrNull()
            Assert.assertTrue("Expected an explicit authentication failure: "+rejected,rejected?.message?.contains("401")==true)
            Assert.assertTrue(b.get("meta","","pending").isNotEmpty())
            b.connection.edit().putString("key",token).commit()
            Api.sync(b,day,"tech")
            Api.sync(b,day,"tech")
            Assert.assertEquals("",b.get("meta","","pending"))
            Assert.assertEquals(1,b.rows("report",day).count { it.item=="tech" })
            val report=b.reports(day).single { it.getString("kind")=="tech" }
            Assert.assertFalse(report.getBoolean("demo"))
            Assert.assertEquals("08:17",report.getString("time"))
            Assert.assertEquals(day,report.getString("day"))
            b.connection.edit().remove("url").remove("key").commit()
            Assert.assertTrue(b.reports(day).any { it.getString("kind")=="tech" && !it.getBoolean("demo") })
        } finally { b.connection.edit().remove("url").remove("key").commit() }
    }

    @Test fun deadlineConversionHandlesMidnightAndPartialDates(){
        val zone=ZoneId.of("Africa/Casablanca")
        val event=JSONObject().put("deadlineAt","2026-11-26T23:30:00Z")
        val deadline=requireNotNull(eventDeadline(event,zone))
        Assert.assertEquals(LocalDate.of(2026,11,26),deadline.day)
        Assert.assertEquals(LocalTime.of(23,30),deadline.time)
        val before=eventDeadline(JSONObject().put("deadlineAt","2026-09-19T23:30:00Z"),zone)
        val after=eventDeadline(JSONObject().put("deadlineAt","2026-09-20T23:30:00Z"),zone)
        Assert.assertEquals(EventDeadline(LocalDate.of(2026,9,20),LocalTime.of(0,30)),before)
        Assert.assertEquals(EventDeadline(LocalDate.of(2026,9,20),LocalTime.of(23,30)),after)
        val winter=eventDeadline(JSONObject().put("deadlineAt","2026-02-26T23:30:00Z"),zone)
        Assert.assertEquals(EventDeadline(LocalDate.of(2026,2,26),LocalTime.of(23,30)),winter)
        Assert.assertNull(eventDeadline(event.put("deadlineAt","unconfirmed"),zone))
        event.remove("deadlineAt")
        event.put("deadline","2026-11-27")
        Assert.assertNull(requireNotNull(eventDeadline(event,zone)).time)
        Assert.assertNull(eventDeadline(event.put("deadline","2026-02-30"),zone))
    }

    @Test fun badLegacyReminderLeavesAgendaAndOtherAlarmsAvailable(){
        val b=ctx.book
        ui.waitUntil(10000){b.ready.value}
        val day=today(b.prefs()).toString()
        val old=Page("reminder",day,"qa-bad-time",JSONObject().put("title","Old reminder").put("time","25:68").toString())
        val valid=Page("reminder",day,"qa-good-time",JSONObject().put("title","Valid reminder").put("time","21:30").toString())
        b.edit(listOf(old,valid))
        try {
            ui.onNodeWithText("Old reminder").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("Personal reminder · time unverified").assertExists()
            val ids=activities(b).map { it.id }
            Assert.assertTrue(valid.item in ids)
            Assert.assertFalse(old.item in ids)
        } finally {b.edit(removals=listOf(old,valid));b.flush()}
    }

    @Test fun pausingFromScheduleUpdatesToday(){
        val b=ctx.book
        ui.waitUntil(10000){b.ready.value}
        val original=b.prefs().getJSONObject("schedule").getJSONObject("swim").getBoolean("enabled")
        try {
            ui.onNodeWithTag("nav-1").performClick()
            ui.onNodeWithText("Mannesmann swim check").performScrollTo().performClick()
            ui.onNodeWithText("Enabled").performScrollTo().performClick()
            ui.waitUntil(5000){b.prefs().getJSONObject("schedule").getJSONObject("swim").getBoolean("enabled")!=original}
            ui.onNodeWithTag("nav-0").performClick()
            if(original)ui.onNodeWithText("Mannesmann swim check").assertDoesNotExist()
            else ui.onNodeWithText("Mannesmann swim check").assertExists()
        } finally {
            b.settings { it.getJSONObject("schedule").getJSONObject("swim").put("enabled",original) };b.flush()
        }
    }
}

