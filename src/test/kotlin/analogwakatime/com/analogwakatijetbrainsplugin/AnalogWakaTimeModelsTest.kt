package analogwakatime.com.analogwakatimejetbrainsplugin

import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.TimeZone

class AnalogWakaTimeModelsTest {
    @Test
    fun `formatShortDuration formats seconds and minutes`() {
        assertEquals("45s", formatShortDuration(45))
        assertEquals("2m", formatShortDuration(120))
        assertEquals("1h 5m", formatShortDuration(3_900))
    }

    @Test
    fun `formatShortDuration handles boundary values`() {
        assertEquals("59s", formatShortDuration(59))
        assertEquals("1m", formatShortDuration(60))
        assertEquals("59m", formatShortDuration(3_599))
        assertEquals("1h 0m", formatShortDuration(3_600))
    }

    @Test
    fun `formatDetailedDuration formats human readable duration`() {
        assertEquals("12 sec", formatDetailedDuration(12))
        assertEquals("2 min 5 sec", formatDetailedDuration(125))
        assertEquals("1 h 1 min", formatDetailedDuration(3_660))
    }

    @Test
    fun `formatDetailedDuration handles exact minute and hour`() {
        assertEquals("1 min 0 sec", formatDetailedDuration(60))
        assertEquals("1 h 0 min", formatDetailedDuration(3_600))
    }

    @Test
    fun `sanitizedNetLines never returns negative value`() {
        assertEquals(4, sanitizedNetLines(7, 3))
        assertEquals(0, sanitizedNetLines(1, 3))
    }

    @Test
    fun `hasActivity returns true for time keystrokes or line changes`() {
        assertFalse(hasActivity(ActivityStats()))

        assertTrue(hasActivity(ActivityStats(totalKeystrokes = 1)))
        assertTrue(hasActivity(ActivityStats(totalTimeSpentMillis = 1_000)))
        assertTrue(
            hasActivity(
                ActivityStats(
                    activeFiles = mapOf(
                        "/tmp/file.kt" to FileActivity(linesAdded = 1),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `hasActivity returns true when file only has deleted lines`() {
        assertTrue(
            hasActivity(
                ActivityStats(
                    activeFiles = mapOf(
                        "/tmp/file.kt" to FileActivity(linesDeleted = 2),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `midpointDateInfo uses midpoint date and hour`() {
        withTimeZone("UTC") {
            val start = Instant.parse("2026-04-06T23:30:00Z").toEpochMilli()
            val end = Instant.parse("2026-04-07T00:30:00Z").toEpochMilli()

            assertEquals("2026-04-07" to 0, midpointDateInfo(start, end))
        }
    }

    @Test
    fun `resolveProjectContext keeps project root for files inside project`() {
        val project = fakeProject("Analog WakaTime", "/workspace/analog-wakatime")

        assertEquals(
            ProjectContext(
                path = "/workspace/analog-wakatime",
                projectName = "Analog WakaTime",
            ),
            resolveProjectContext(project, "/workspace/analog-wakatime/src/Main.kt"),
        )
    }

    @Test
    fun `resolveProjectContext falls back to parent folder for external files`() {
        val project = fakeProject("Analog WakaTime", "/workspace/analog-wakatime")

        assertEquals(
            ProjectContext(
                path = "/tmp/outside",
                projectName = "outside",
            ),
            resolveProjectContext(project, "/tmp/outside/file.kt"),
        )
    }

    @Test
    fun `resolveFileName extracts final path segment`() {
        assertEquals("Main.kt", resolveFileName("/workspace/analog-wakatime/src/Main.kt"))
        assertEquals("Main.kt", resolveFileName("""C:\workspace\analog-wakatime\src\Main.kt"""))
        assertEquals("file.kt", resolveFileName("file.kt"))
    }

    private fun withTimeZone(timeZoneId: String, block: () -> Unit) {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(timeZoneId))
        try {
            block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    private fun fakeProject(name: String, basePath: String?): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getName" -> name
                "getBasePath" -> basePath
                "isDisposed" -> false
                "toString" -> "FakeProject(name=$name, basePath=$basePath)"
                "hashCode" -> arrayOf(name, basePath).contentHashCode()
                "equals" -> false
                else -> throw UnsupportedOperationException("Unsupported Project method in test: ${method.name}")
            }
        } as Project
    }
}
