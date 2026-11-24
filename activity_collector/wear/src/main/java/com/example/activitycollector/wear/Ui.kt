package com.example.activitycollector.wear

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text

/** Colors from the FallGuard Watch UI design (phone app's emerald and crimson, dark for OLED). */
object FG {
    val Bg = Color(0xFF000000)
    val Card = Color(0xFF1A211D)
    val Text = Color(0xFFF1F5F9)
    val Muted = Color(0xFFB4C2BB)
    val Emerald = Color(0xFF34D399)
    val EmeraldTint = Color(0xFF0B2A20)
    val OnEmerald = Color(0xFF052E1F)
    val Crimson = Color(0xFFDC2626)
    val CrimsonSoft = Color(0xFFF87171)
    val CrimsonTint = Color(0xFF3B0D0D)
    val AlertBg = Color(0xFF450A0A)
    val Amber = Color(0xFFFBBF24)
    val AmberTint = Color(0xFF2A2410)
    val Gray = Color(0xFF1F2937)
}

/** Icon in a tinted circle, as used on every card in the design. */
@Composable
fun IconBadge(@DrawableRes icon: Int, tint: Color, bg: Color, size: Dp = 20.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.52f))
    }
}

/** List card: tinted icon, title, optional subtitle and trailing content. At least 48 dp tall. */
@Composable
fun ListCard(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String? = null,
    iconTint: Color = FG.Emerald,
    iconBg: Color = FG.EmeraldTint,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    var m = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(26.dp)).background(if (selected) FG.EmeraldTint else FG.Card)
    if (selected) m = m.border(BorderStroke(2.dp, FG.Emerald), RoundedCornerShape(26.dp))
    if (onClick != null && enabled) m = m.clickable(role = Role.Button, onClick = onClick)
    Row(m.padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, if (enabled) iconTint else FG.Muted, iconBg, 30.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (enabled) FG.Text else FG.Muted)
            if (subtitle != null) Text(subtitle, fontSize = 11.sp, color = FG.Muted, lineHeight = 13.sp)
        }
        if (trailing != null) trailing()
        else if (onClick != null && enabled) Icon(painterResource(R.drawable.ic_chevron_right), null, tint = FG.Muted, modifier = Modifier.size(14.dp))
    }
}

/** Main action of a screen, at the bottom of the round display. */
@Composable
fun BoxScope.EdgeButton(
    text: String,
    @DrawableRes icon: Int? = null,
    color: Color,
    contentColor: Color,
    border: Color? = null,
    onClick: () -> Unit,
) {
    var m = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).width(150.dp).heightIn(min = 52.dp)
        .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomStart = 40.dp, bottomEnd = 40.dp))
        .background(color)
    if (border != null) m = m.border(BorderStroke(2.dp, border), RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomStart = 40.dp, bottomEnd = 40.dp))
    Row(
        m.clickable(role = Role.Button, onClick = onClick).padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, tint = contentColor, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = contentColor, textAlign = TextAlign.Center)
    }
}

/** Screen title with a small icon, as on History, Contacts, Sensitivity, Settings. */
@Composable
fun ScreenTitle(@DrawableRes icon: Int?, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        if (icon != null) {
            Icon(painterResource(icon), null, tint = FG.Emerald, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = FG.Text)
    }
}
