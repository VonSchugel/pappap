package ma.marqicci.daybook

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import org.junit.*
import org.junit.runner.RunWith
import java.time.*
import org.json.JSONObject
import java.util.Locale
import java.time.format.TextStyle

@RunWith(AndroidJUnit4::class)
class DeviceChecks {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    val ctx:Context get()=ApplicationProvider.getApplicationContext()
    val sample="An offline premise survives an Android process restart."
    fun openJournal(){ui.waitUntil(10000){ctx.book.ready.value};ui.onNodeWithText("Continue journal",substring=true).performClick()}

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
        ui.onNodeWithText("Today").performClick()
        ui.onNodeWithText("Bench experiment").performScrollTo().performClick()
        ui.onNodeWithText("What I tried").performScrollTo().performTextReplacement("Measured five step responses.")
        val id=activities(b).single{it.title=="Bench experiment"}.id
        Assert.assertFalse(skipped.value in activities(b).single{it.id==id}.weekdays)
        ui.waitUntil(10000){b.get("session",day.toString(),id).contains("five step responses") && b.saved.value>=b.queued}
    }

    @Test fun resumeAndCompleteAfterRestart(){
        val b=ctx.book
        ui.waitUntil(10000){b.ready.value}
        val day=today(b.prefs())
        val id=activities(b).single{it.title=="Bench experiment"}.id
        val prior=day.minusDays(1).toString()
        val snapshot=b.get("session",day.toString(),id)
        Assert.assertTrue(snapshot.contains("five step responses"))
        b.put("session",prior,id,snapshot)
        b.settings { it.getJSONObject("schedule").getJSONObject(id)
            .put("title","New experiment").put("prompts",org.json.JSONArray(listOf("New question"))).put("enabled",false) }
        ui.onNodeWithText("Resume Bench experiment · "+prior).performScrollTo().performClick()
        ui.onNodeWithText("Measured five step responses.").assertExists()
        ui.onNodeWithText("Mark complete").performScrollTo().performClick()
        ui.waitUntil(10000){ActivitySession.read(b.get("session",prior,id)).complete}
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
}

