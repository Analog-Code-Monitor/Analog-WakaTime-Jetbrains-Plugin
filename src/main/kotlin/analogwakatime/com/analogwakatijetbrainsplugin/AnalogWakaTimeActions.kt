<<<<<<< HEAD
package analogwakatime.com.analogwakatimejetbrainsplugin
=======
package analogwakatime.com.analogwakatijetbrainsplugin
>>>>>>> 3355d92 (start)

import analogwakatime.com.analogwakatimejetbrainsplugin.AnalogWakaTimeAppService
import analogwakatime.com.analogwakatimejetbrainsplugin.AnalogWakaTimeProjectService
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

private fun projectFrom(event: AnActionEvent): Project? {
    return event.project ?: CommonDataKeys.PROJECT.getData(event.dataContext)
}

class AnalogWakaTimeLoginAction : DumbAwareAction("Login") {
    override fun actionPerformed(event: AnActionEvent) {
        service<AnalogWakaTimeAppService>().login(projectFrom(event))
    }
}


class AnalogWakaTimeLogoutAction : DumbAwareAction("Logout") {
    override fun actionPerformed(event: AnActionEvent) {
        val project = projectFrom(event)
        val confirmation = Messages.showYesNoDialog(
            project,
            "Are you sure you want to log out of Analog WakaTime?",
            "Analog WakaTime",
            Messages.getWarningIcon(),
        )

        if (confirmation == Messages.YES) {
            service<AnalogWakaTimeAppService>().logout(project)
        }
    }
}

class AnalogWakaTimeSetApiTokenAction : DumbAwareAction("Set API Token") {
    override fun actionPerformed(event: AnActionEvent) {
        service<AnalogWakaTimeAppService>().promptForApiToken(projectFrom(event))
    }
}

class AnalogWakaTimeShowStatsAction : DumbAwareAction("Show Stats") {
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = projectFrom(event) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        projectFrom(event)?.service<AnalogWakaTimeProjectService>()?.showStatsDialog()
    }
}

class AnalogWakaTimeForceSyncAction : DumbAwareAction("Force Sync") {
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = projectFrom(event) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        projectFrom(event)?.service<AnalogWakaTimeProjectService>()?.forceSync()
    }
}
