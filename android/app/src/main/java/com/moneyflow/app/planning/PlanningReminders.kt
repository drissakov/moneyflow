package com.moneyflow.app.planning

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.moneyflow.app.MainActivity
import com.moneyflow.app.R
import com.moneyflow.app.data.MoneyFlowRepository
import com.moneyflow.app.data.Session
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/** A daily reminder is deliberately generic: financial details stay behind sign-in. */
@SuppressLint("ApplySharedPref")
object PlanningReminders {
    private const val PREFERENCES = "moneyflow_planning_reminders"
    private const val ACTIVE_OWNER = "active_owner"
    private const val CHANNEL = "moneyflow_planning_v1"
    private const val PERIODIC_JOB = 61001
    private const val CHECK_JOB = 61002
    private const val NOTIFICATION = 61003
    internal const val OWNER = "owner"

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
    private fun scheduler(context: Context) = context.getSystemService(JobScheduler::class.java)
    internal fun today(): LocalDate = Instant.now().atOffset(ZoneOffset.ofHours(5)).toLocalDate()

    fun isEnabled(context: Context, userId: String): Boolean = preferences(context).getBoolean("enabled_$userId", false)

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return false
        return manager(context).areNotificationsEnabled() && manager(context).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    @Synchronized
    fun setEnabled(context: Context, userId: String, enabled: Boolean) {
        check(preferences(context).edit().putBoolean("enabled_$userId", enabled).commit()) { "Не удалось сохранить настройки напоминаний" }
        if (preferences(context).getString(ACTIVE_OWNER, null) == userId) {
            try {
                if (enabled) schedulePeriodic(context, userId) else cancel(context)
            } catch (error: RuntimeException) {
                preferences(context).edit().putBoolean("enabled_$userId", false).commit()
                cancel(context)
                throw error
            }
        }
    }

    @Synchronized
    fun synchronizeSession(context: Context, userId: String?) {
        val prefs = preferences(context)
        if (prefs.getString(ACTIVE_OWNER, null) != userId) {
            cancel(context)
            check(prefs.edit().putString(ACTIVE_OWNER, userId).commit())
        }
        if (userId != null && isEnabled(context, userId)) schedulePeriodic(context, userId) else cancel(context)
    }

    private fun cancel(context: Context) {
        scheduler(context).cancel(PERIODIC_JOB)
        scheduler(context).cancel(CHECK_JOB)
        manager(context).cancel(NOTIFICATION)
    }

    private fun createChannel(context: Context) {
        manager(context).createNotificationChannel(NotificationChannel(CHANNEL, "Запланированные платежи", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Ежедневное напоминание проверить плановые доходы и расходы"
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
        })
    }

    private fun schedulePeriodic(context: Context, userId: String) {
        createChannel(context)
        val current = scheduler(context).getPendingJob(PERIODIC_JOB)
        if (current?.extras?.getString(OWNER) == userId) return
        val info = JobInfo.Builder(PERIODIC_JOB, ComponentName(context, PlanningReminderService::class.java))
            .setExtras(PersistableBundle().apply { putString(OWNER, userId) })
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(TimeUnit.DAYS.toMillis(1), TimeUnit.HOURS.toMillis(6))
            .setPersisted(true)
            .setBackoffCriteria(TimeUnit.MINUTES.toMillis(30), JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        check(scheduler(context).schedule(info) == JobScheduler.RESULT_SUCCESS) { "Android не разрешил включить напоминания" }
    }

    fun checkSoon(context: Context, userId: String) {
        require(isEnabled(context, userId) && preferences(context).getString(ACTIVE_OWNER, null) == userId)
        val info = JobInfo.Builder(CHECK_JOB, ComponentName(context, PlanningReminderService::class.java))
            .setExtras(PersistableBundle().apply { putString(OWNER, userId) })
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(0)
            .setOverrideDeadline(TimeUnit.MINUTES.toMillis(1))
            .build()
        check(scheduler(context).schedule(info) == JobScheduler.RESULT_SUCCESS) { "Android не разрешил проверить напоминания" }
    }

    internal fun ownsEnabledJob(context: Context, userId: String): Boolean =
        preferences(context).getString(ACTIVE_OWNER, null) == userId && isEnabled(context, userId)

    @Synchronized
    @SuppressLint("MissingPermission") // Rechecked immediately before posting, including Android 13 runtime permission.
    internal fun deliver(context: Context, repository: MoneyFlowRepository, captured: Session, count: Int): Boolean {
        val prefs = preferences(context)
        val day = today()
        if (!canDeliverPlanningReminder(captured, repository.session.value,
                prefs.getString(ACTIVE_OWNER, null), isEnabled(context, captured.user.id),
                prefs.getString("sent_${captured.user.id}", null), day, count) || !canNotify(context)) return false
        createChannel(context)
        val intent = Intent(context, MainActivity::class.java).putExtra("open_planning", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val action = PendingIntent.getActivity(context, NOTIFICATION, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_planning_notification)
            .setContentTitle("Проверьте план на сегодня")
            .setContentText("Есть запланированные доходы или платежи, которые ждут подтверждения.")
            .setContentIntent(action)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        manager(context).notify(NOTIFICATION, notification)
        check(prefs.edit().putString("sent_${captured.user.id}", day.toString()).commit())
        return true
    }
}

internal fun canDeliverPlanningReminder(
    captured: Session, current: Session?, activeOwner: String?, enabled: Boolean,
    lastSent: String?, day: LocalDate, count: Int, now: Instant = Instant.now(),
): Boolean = enabled && count > 0 && activeOwner == captured.user.id && current?.user?.id == captured.user.id &&
    current?.token == captured.token && lastSent != day.toString() && runCatching { Instant.parse(captured.expiresAt).isAfter(now) }.getOrDefault(false)
