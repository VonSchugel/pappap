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
}

