package analogwakatime.com.analogwakatimejetbrainsplugin

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

object AnalogWakaTimeNotifications {
    fun info(project: Project?, content: String, action: NotificationAction? = null) {
        notify(project, content, NotificationType.INFORMATION, action)
    }

    fun warn(project: Project?, content: String, action: NotificationAction? = null) {
        notify(project, content, NotificationType.WARNING, action)
    }

    fun error(project: Project?, content: String, action: NotificationAction? = null) {
        notify(project, content, NotificationType.ERROR, action)
    }

    private fun notify(project: Project?, content: String, type: NotificationType, action: NotificationAction?) {
        ApplicationManager.getApplication().invokeLater {
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(ANALOG_WAKATIME_NOTIFICATION_GROUP)
                .createNotification(content, type)

            if (action != null) {
                notification.addAction(action)
            }

            if (project != null && !project.isDisposed) {
                notification.notify(project)
            } else {
                notification.notify(null)
            }
        }
    }

    fun createLoginAction(project: Project): NotificationAction {
        return object : NotificationAction("Login") {
            override fun actionPerformed(
                e: com.intellij.openapi.actionSystem.AnActionEvent,
                notification: Notification,
            ) {
                notification.expire()
                service<AnalogWakaTimeAppService>().login(project)
            }
        }
    }
}
