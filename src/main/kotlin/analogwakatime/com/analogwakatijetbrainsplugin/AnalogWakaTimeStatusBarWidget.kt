package analogwakatime.com.analogwakatimejetbrainsplugin

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.Timer

class AnalogWakaTimeStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ANALOG_WAKATIME_STATUS_BAR_WIDGET_ID

    override fun getDisplayName(): String = "Analog WakaTime"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = AnalogWakaTimeStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class AnalogWakaTimeStatusBarWidget(private val project: Project) : CustomStatusBarWidget, Disposable {
    private val label = JBLabel()
    private val timer = Timer(1_000) { update() }

    init {
        label.border = JBUI.Borders.empty(0, 6)
        label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        label.icon = AnalogWakaTimeIcons.STATUS_BAR_CLOCK
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val appService = service<AnalogWakaTimeAppService>()
                if (appService.isAuthenticated()) {
                    BrowserUtil.browse(AnalogWakaTimeRoutes.DASHBOARD)
                } else {
                    appService.login(project)
                }
            }
        })

        update()
        timer.start()
    }

    override fun ID(): String = ANALOG_WAKATIME_STATUS_BAR_WIDGET_ID

    override fun install(statusBar: StatusBar) {
    }

    override fun getComponent(): JComponent = label

    override fun dispose() {
        timer.stop()
    }

    private fun update() {
        if (project.isDisposed) {
            return
        }

        val appService = service<AnalogWakaTimeAppService>()
        val projectService = project.service<AnalogWakaTimeProjectService>()

        if (!appService.isAuthenticated()) {
            label.text = " Login"
            label.toolTipText = "Analog WakaTime\n\nPlease authenticate to track time.\nClick to log in."
            return
        }

        val savedSeconds = appService.getTotalTimeSeconds()
        val sessionSeconds = projectService.getSessionTimeSeconds()
        val totalSeconds = savedSeconds + sessionSeconds

        label.text = " ${formatShortDuration(totalSeconds)}"
        label.toolTipText = buildString {
            append("Analog WakaTime\n\n")
            append("Total: ${formatDetailedDuration(totalSeconds)}\n")
            append("Current session: ${formatDetailedDuration(sessionSeconds)}\n")
            append(
                if (appService.getUnsyncedCount() > 0) {
                    "Waiting for synchronization: ${appService.getUnsyncedCount()} records\n"
                } else {
                    "Everything synchronized\n"
                },
            )
            append(if (appService.isOnline()) "Status: online" else "Status: offline (saving locally)")
            append("\n\nClick to open Analog WakaTime in the browser.")
        }
    }
}
