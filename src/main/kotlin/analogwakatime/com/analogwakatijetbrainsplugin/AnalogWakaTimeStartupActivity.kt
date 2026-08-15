package analogwakatime.com.analogwakatimejetbrainsplugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class AnalogWakaTimeStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        val application = ApplicationManager.getApplication()
        if (application.isHeadlessEnvironment || application.isUnitTestMode) {
            return
        }

        service<AnalogWakaTimeAppService>().start()
        project.service<AnalogWakaTimeProjectService>()
    }
}
