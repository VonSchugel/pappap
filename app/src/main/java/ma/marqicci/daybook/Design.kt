package ma.marqicci.daybook

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import java.time.LocalDate
import java.util.Locale

private val dayColors=lightColorScheme(
    primary=Color(0xff21584b), onPrimary=Color.White,
    primaryContainer=Color(0xffdcebe3), onPrimaryContainer=Color(0xff123f34),
    secondary=Color(0xff596655), onSecondary=Color.White,
    secondaryContainer=Color(0xffe3e9df), onSecondaryContainer=Color(0xff213b32),
    tertiary=Color(0xff955125), background=Color(0xfff8f7f1),
    surface=Color(0xfffffef9), onSurface=Color(0xff172e29), onBackground=Color(0xff172e29),
    surfaceVariant=Color(0xffeeefe6), onSurfaceVariant=Color(0xff485e54),
    outline=Color(0xff697d70), outlineVariant=Color(0xffd4dbd1)
)
private val nightColors=darkColorScheme(
    primary=Color(0xffa0d6bf), onPrimary=Color(0xff083b2b),
    primaryContainer=Color(0xff234a3b), onPrimaryContainer=Color(0xffc4ebd8),
    secondary=Color(0xffb9ccba), secondaryContainer=Color(0xff314339),
    onSecondaryContainer=Color(0xffd1e6d2), tertiary=Color(0xffeabe91),
    background=Color(0xff101a17), surface=Color(0xff192720),
    onSurface=Color(0xffe9ede3), onBackground=Color(0xffe9ede3),
    surfaceVariant=Color(0xff25342d), onSurfaceVariant=Color(0xffbacabe),
    outline=Color(0xff90a497), outlineVariant=Color(0xff3c4d42)
)
val ink:Color @Composable get()=MaterialTheme.colorScheme.onSurface
val muted:Color @Composable get()=MaterialTheme.colorScheme.onSurfaceVariant
val paper:Color @Composable get()=MaterialTheme.colorScheme.background
val line:Color @Composable get()=MaterialTheme.colorScheme.outlineVariant
val leaf:Color @Composable get()=MaterialTheme.colorScheme.primary
val panel:Color @Composable get()=MaterialTheme.colorScheme.surface
@Composable fun DaybookTheme(content:@Composable ()->Unit){
    MaterialTheme(colorScheme=if(isSystemInDarkTheme()) nightColors else dayColors,content=content)
}

@Composable fun Mark(icon:Int,modifier:Modifier=Modifier.size(21.dp),tint:Color=LocalContentColor.current){
    Icon(painterResource(icon),contentDescription=null,modifier=modifier,tint=tint)
}
@Composable fun IconAction(icon:Int,label:String,run:()->Unit){
    IconButton(onClick=run){ Icon(painterResource(icon),label,Modifier.size(21.dp),tint=leaf) }
}
@Composable fun Eyebrow(text:String,color:Color=muted){
    Text(text.uppercase(Locale.ENGLISH),color=color,fontSize=11.sp,lineHeight=16.sp,
        letterSpacing=1.1.sp,fontWeight=FontWeight.SemiBold)
}
@Composable fun PageTitle(text:String){
    Text(text,Modifier.semantics { heading() },color=ink,fontFamily=FontFamily.Serif,
        fontSize=36.sp,lineHeight=41.sp,letterSpacing=(-1).sp)
}
@Composable fun StatusLine(text:String,icon:Int=R.drawable.ic_check){
    Row(horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically){
        Mark(icon,Modifier.size(16.dp),leaf);Caption(text)
    }
}
fun kindIcon(kind:String)=when(kind){
    "weather"->R.drawable.ic_sun
    "swim"->R.drawable.ic_water
    "tech"->R.drawable.ic_brief
    "hacks"->R.drawable.ic_flag
    "journal"->R.drawable.ic_journal
    else->if(kind.startsWith("activity-")) R.drawable.ic_activity else R.drawable.ic_clock
}
@Composable fun KindMark(kind:String){
    val color=when(kind){
        "weather","hacks"->MaterialTheme.colorScheme.tertiary
        else->leaf
    }
    Box(Modifier.size(36.dp).background(color.copy(alpha=.10f),RoundedCornerShape(11.dp)),contentAlignment=Alignment.Center){
        Mark(kindIcon(kind),Modifier.size(20.dp),color)
    }
}
@Composable fun DayHeader(date:LocalDate,scheduled:Int,nextTime:String?,nextTitle:String?){
    val ivory=Color(0xfff5f2db)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xff163f39))){
        BoxWithConstraints(Modifier.fillMaxWidth()){
            val largeType=LocalDensity.current.fontScale>1.35f
            if(!largeType) CoastPrint(Modifier.align(Alignment.CenterEnd).width(maxWidth*.34f).height(104.dp))
            Column(Modifier.fillMaxWidth(if(largeType) 1f else .70f).padding(start=20.dp,top=17.dp,bottom=18.dp,end=4.dp)){
                Eyebrow(nice(date,"EEEE"),Color(0xffc6d7bf))
                Text(nice(date,"MMMM d"),color=ivory,fontSize=36.sp,lineHeight=42.sp,
                    fontFamily=FontFamily.Serif,letterSpacing=(-1).sp,modifier=Modifier.semantics { heading() })
            }
        }
        HorizontalDivider(color=Color.White.copy(alpha=.15f))
        Row(Modifier.padding(horizontal=18.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Mark(R.drawable.ic_clock,Modifier.size(16.dp),Color(0xffd8c697))
            Text(if(nextTitle!=null) "Next at "+nextTime+" · "+nextTitle
                else when(scheduled){0->"No scheduled items";1->"1 scheduled item";else->"$scheduled scheduled items"},
                color=ivory,fontSize=13.sp,lineHeight=19.sp)
        }
    }
}

// Decorative coastal print. Its shapes are independent of forecasts and tide data.
@Composable private fun CoastPrint(modifier:Modifier){
    Canvas(modifier){
        val w=size.width;val h=size.height
        val gold=Color(0xffdac391);val sea=Color(0xff709b89)
        drawCircle(gold,radius=w*.105f,center=Offset(w*.63f,h*.31f))
        for(i in 0..5){
            val y=h*(.60f+i*.067f)
            val curve=Path().apply{
                moveTo(-w*.06f,y+h*.12f);cubicTo(w*.22f,y+h*.12f,w*.30f,y-h*.18f,w*.57f,y-h*.08f)
                cubicTo(w*.73f,y,w*.88f,y-h*.1f,w*1.08f,y-h*.22f)
            }
            drawPath(curve,sea.copy(alpha=.90f-i*.06f),style=Stroke(width=1.dp.toPx()))
        }
    }
}
data class AgendaItem(val time:String,val id:String,val title:String,val summary:String)
@Composable fun AgendaLine(item:AgendaItem,open:()->Unit){
    Row(Modifier.fillMaxWidth().clickable(onClick=open).padding(vertical=12.dp),
        horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.Top){
        Column(Modifier.widthIn(min=48.dp),horizontalAlignment=Alignment.Start){
            Text(item.time,color=leaf,fontSize=13.sp,fontFamily=FontFamily.Monospace,fontWeight=FontWeight.Medium)
            Spacer(Modifier.height(7.dp));KindMark(item.id)
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text(item.title,color=ink,fontSize=20.sp,lineHeight=24.sp,fontFamily=FontFamily.Serif)
            Text(item.summary,color=muted,fontSize=14.sp,lineHeight=20.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
        Mark(R.drawable.ic_chevron,Modifier.padding(top=3.dp).size(15.dp),muted)
    }
}
