package no.hanss.alarmclock.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val dayLetters = listOf("M", "T", "W", "T", "F", "S", "S")
private val dayFullNames = listOf(
    "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"
)

/**
 * Row of circular per-day toggles (Mon=1 .. Sun=7, matching the ISO
 * convention used everywhere else in the app). Replaces the older
 * FilterChip row; same semantics, denser and closer to the standard
 * clock-app idiom.
 */
@Composable
fun DayOfWeekSelector(
    selectedDays: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        dayLetters.forEachIndexed { index, letter ->
            val dayNumber = index + 1
            val selected = dayNumber in selectedDays
            val background by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                label = "dayBackground"
            )
            val content by animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "dayContent"
            )
            val fullName = dayFullNames[index]
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(background)
                    .clickable { onToggle(dayNumber) }
                    .semantics { contentDescription = fullName },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = letter,
                    color = content,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}
