package com.termux.app.data.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.app.TermuxService
import com.termux.shared.notification.NotificationUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE

class ServiceNotification(private val context: Context) {

    fun setupChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        NotificationUtils.setupNotificationChannel(
            context,
            TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID,
            TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        )
    }

    fun build(sessionCount: Int, taskCount: Int, wakeLockHeld: Boolean): Notification? {
        val res: Resources = context.resources

        // Set pending intent to be launched when notification is clicked
        val notificationIntent = TermuxActivity.newInstance(context)
        val contentIntent = PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        // Set notification text
        var notificationText = sessionCount.toString() + " session" + if (sessionCount == 1) "" else "s"
        if (taskCount > 0) {
            notificationText += ", " + taskCount + " task" + if (taskCount == 1) "" else "s"
        }

        if (wakeLockHeld) notificationText += " (wake lock held)"

        // Set notification priority
        // If holding a wake or wifi lock consider the notification of high priority since it's using power,
        // otherwise use a low priority
        val priority = if (wakeLockHeld) Notification.PRIORITY_HIGH else Notification.PRIORITY_LOW

        // Build the notification
        val builder = NotificationUtils.geNotificationBuilder(
            context,
            TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID, priority,
            TermuxConstants.TERMUX_APP_NAME, notificationText, null,
            contentIntent, null, NotificationUtils.NOTIFICATION_MODE_SILENT
        ) ?: return null

        // No need to show a timestamp:
        builder.setShowWhen(false)

        // Set notification icon
        builder.setSmallIcon(R.drawable.ic_service_notification)

        // Set background color for small notification icon
        builder.setColor(0xFF607D8B.toInt())

        // TermuxSessions are always ongoing
        builder.setOngoing(true)

        // Set Exit button action
        val exitIntent = Intent(context, TermuxService::class.java).setAction(TERMUX_SERVICE.ACTION_STOP_SERVICE)
        builder.addAction(
            android.R.drawable.ic_delete,
            res.getString(R.string.notification_action_exit),
            PendingIntent.getService(context, 0, exitIntent, PendingIntent.FLAG_IMMUTABLE)
        )

        // Set Wakelock button actions
        val newWakeAction = if (wakeLockHeld) TERMUX_SERVICE.ACTION_WAKE_UNLOCK else TERMUX_SERVICE.ACTION_WAKE_LOCK
        val toggleWakeLockIntent = Intent(context, TermuxService::class.java).setAction(newWakeAction)
        val actionTitle = res.getString(
            if (wakeLockHeld) R.string.notification_action_wake_unlock else R.string.notification_action_wake_lock
        )
        val actionIcon = if (wakeLockHeld) android.R.drawable.ic_lock_idle_lock else android.R.drawable.ic_lock_lock
        builder.addAction(
            actionIcon,
            actionTitle,
            PendingIntent.getService(context, 0, toggleWakeLockIntent, PendingIntent.FLAG_IMMUTABLE),
        )

        // Set Float button action to toggle the in-app floating terminal
        val toggleFloatIntent = Intent(context, TermuxService::class.java).setAction(TermuxService.ACTION_TOGGLE_FLOAT)
        builder.addAction(
            android.R.drawable.ic_menu_view,
            res.getString(R.string.notification_action_float),
            PendingIntent.getService(context, 0, toggleFloatIntent, PendingIntent.FLAG_IMMUTABLE)
        )

        return builder.build()
    }
}
