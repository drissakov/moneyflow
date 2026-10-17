package com.moneyflow.app.planning

import android.app.job.JobParameters
import android.app.job.JobService
import com.moneyflow.app.MoneyFlowApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class PlanningReminderService : JobService() {
    private class Running(val stopped: AtomicBoolean = AtomicBoolean(false), var task: Job? = null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<Int, Running>()

    override fun onStartJob(params: JobParameters): Boolean {
        val owner = params.extras.getString(PlanningReminders.OWNER) ?: return false
        val repository = (application as MoneyFlowApplication).repository
        val captured = repository.session.value ?: return false
        if (captured.user.id != owner || !PlanningReminders.ownsEnabledJob(this, owner) || !PlanningReminders.canNotify(this)) return false
        val state = Running()
        state.task = scope.launch(start = CoroutineStart.LAZY) {
            var retry = false
            try {
                val count = repository.planningReminderDueCount(captured)
                if (count != null && !state.stopped.get()) PlanningReminders.deliver(this@PlanningReminderService, repository, captured, count)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                retry = error is IOException || error is HttpException && (error.code() == 429 || error.code() >= 500)
            } finally {
                running.remove(params.jobId, state)
                withContext(NonCancellable + Dispatchers.Main) {
                    if (!state.stopped.get()) jobFinished(params, retry && PlanningReminders.ownsEnabledJob(this@PlanningReminderService, owner))
                }
            }
        }
        running[params.jobId] = state
        state.task?.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.let { it.stopped.set(true); it.task?.cancel() }
        val owner = params.extras.getString(PlanningReminders.OWNER) ?: return false
        return PlanningReminders.ownsEnabledJob(this, owner)
    }

    override fun onDestroy() {
        running.values.forEach { it.stopped.set(true) }
        scope.cancel()
        super.onDestroy()
    }
}
