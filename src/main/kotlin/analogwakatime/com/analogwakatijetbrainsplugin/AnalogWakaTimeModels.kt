package analogwakatime.com.analogwakatimejetbrainsplugin

import com.intellij.openapi.project.Project
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.max

const val ANALOG_WAKATIME_PLUGIN_ID = "analogwakatime.com.AnalogWakaTimeJetbrainsPlugin"
const val ANALOG_WAKATIME_NOTIFICATION_GROUP = "Analog WakaTime"
const val ANALOG_WAKATIME_STATUS_BAR_WIDGET_ID = "AnalogWakaTimeStatusBar"
const val DEFAULT_SEND_INTERVAL_MS = 5_000L
const val AUTH_REMINDER_INTERVAL_MS = 2 * 60 * 1_000L
const val PING_INTERVAL_MS = 10_000L
const val PERIODIC_SYNC_INTERVAL_MS = 30_000L
const val TELEMETRY_INTERVAL_MS = 30 * 60 * 1_000L
const val CLEANUP_INTERVAL_MS = 24 * 60 * 60 * 1_000L
const val INACTIVITY_THRESHOLD_MS = 120_000L
const val TIME_UPDATE_INTERVAL_MS = 1_000L
const val MAX_TIME_PER_TICK_MS = 2_000L
const val MAX_TIME_PER_SAVE_INTERVAL_MS = 35_000L

data class FileActivity(
    var filePath: String = "",
    var language: String = "unknown",
    var keystrokes: Int = 0,
    var linesAdded: Int = 0,
    var linesDeleted: Int = 0,
    var timeSpentMillis: Long = 0,
    var firstActive: Long = 0,
    var lastActive: Long = 0,
)


data class ActivityStats(
    var sessionStart: Long = 0,
    var sessionEnd: Long = 0,
    var activeFiles: Map<String, FileActivity> = emptyMap(),
    var totalKeystrokes: Int = 0,
    var totalTimeSpentMillis: Long = 0,
)

data class StoredActivity(
    var id: String = UUID.randomUUID().toString(),
    var language: String = "unknown",
    var lines: Int = 0,
    var time: Int = 0,
    var date: String = "",
    var hour: Int = 0,
    var path: String? = null,
    var projectName: String? = null,
    var ideName: String? = null,
    var fileName: String? = null,
    var synced: Boolean = false,
    var timestamp: Long = 0,
)

data class ProjectContext(
    val path: String? = null,
    val projectName: String? = null,
)

data class DeviceFlowResult(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresIn: Int,
    val interval: Int,
)

data class ActivityUploadRequest(
    val language: String,
    var lines: Int,
    var time: Int,
    val date: String,
    val hour: Int,
    val path: String? = null,
    val project_name: String? = null,
    val ide_name: String? = null,
    val filename: String? = null,
)

fun resolveProjectContext(project: Project, filePath: String): ProjectContext {
    val basePath = project.basePath
    if (!basePath.isNullOrBlank() && filePath.startsWith(basePath)) {
        return ProjectContext(
            path = basePath,
            projectName = project.name.ifBlank { Paths.get(basePath).fileName?.toString() ?: basePath },
        )
    }

    val parent = Paths.get(filePath).parent?.toString() ?: filePath
    return ProjectContext(
        path = parent,
        projectName = Paths.get(parent).fileName?.toString() ?: parent,
    )
}

fun resolveFileName(filePath: String): String? {
    val trimmed = filePath.trim()
    if (trimmed.isEmpty()) {
        return null
    }

    return trimmed
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .ifBlank { null }
}

fun formatShortDuration(totalSeconds: Int): String {
    if (totalSeconds < 60) {
        return "${totalSeconds}s"
    }

    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    return if (hours > 0) {
        "${hours}h ${minutes}m"
    } else {
        "${minutes}m"
    }
}

fun formatDetailedDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60

    return when {
        hours > 0 -> "${hours} h ${minutes} min"
        minutes > 0 -> "${minutes} min ${seconds} sec"
        else -> "${seconds} sec"
    }
}

fun hasActivity(stats: ActivityStats): Boolean {
    return stats.totalTimeSpentMillis > 0 || stats.totalKeystrokes > 0 || stats.activeFiles.values.any {
        it.linesAdded != 0 || it.linesDeleted != 0
    }
}

fun midpointDateInfo(timestampStart: Long, timestampEnd: Long): Pair<String, Int> {
    val midpoint = (timestampStart + timestampEnd) / 2
    val zoned = Instant.ofEpochMilli(midpoint).atZone(ZoneId.systemDefault())
    return zoned.toLocalDate().toString() to zoned.hour
}

fun sanitizedNetLines(linesAdded: Int, linesDeleted: Int): Int = max(0, linesAdded - linesDeleted)
