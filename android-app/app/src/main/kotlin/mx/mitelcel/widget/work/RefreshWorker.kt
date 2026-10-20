package mx.mitelcel.widget.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import mx.mitelcel.widget.LowDataNotifier
import mx.mitelcel.widget.data.UsageRepository
import mx.mitelcel.widget.widget.WidgetRenderer

/**
 * Consulta las compañías en segundo plano y redibuja los widgets.
 * `Worker.doWork()` ya corre en un hilo de fondo, así que aquí sí se puede hacer red bloqueante.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        // En un reintento, las líneas que SÍ se actualizaron hace un momento no se vuelven a consultar.
        val minAge = if (runAttemptCount > 0) PERIODIC_MIN_AGE_MS else inputData.getLong(KEY_MIN_AGE, PERIODIC_MIN_AGE_MS)
        // Una línea concreta (toque en el widget) o todas las que se ven en algún widget.
        val accountIds = inputData.getString(KEY_ACCOUNT_ID)?.let { setOf(it) }
            ?: WidgetRenderer.accountIdsInUse(applicationContext)

        val repository = UsageRepository(applicationContext)
        val notifier = LowDataNotifier(applicationContext)
        var networkFailed = false
        accountIds.forEach { id -> // una por una, nunca en paralelo
            val result = repository.refresh(id, minAge)
            networkFailed = networkFailed || result.retryable
            repository.accounts.get(id)?.let { notifier.check(it, result.entry) }
        }
        // Se redibuja siempre (aun sin red): así un dato viejo se marca como "Sin actualizar desde…".
        WidgetRenderer.updateAll(applicationContext)

        // Reintentar SOLO si falló la red, y máximo 3 veces (con espera creciente: 1, 2, 4 min).
        // Un error de contraseña nunca se reintenta: repetir el login podría bloquear la cuenta.
        return if (networkFailed && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    companion object {
        /** Periódico: no volver a consultar si hay un dato de hace menos de 5 min. */
        const val PERIODIC_MIN_AGE_MS = 5 * 60 * 1000L

        /** Manual: solo evita el doble toque accidental. */
        const val MANUAL_MIN_AGE_MS = 10 * 1000L

        private const val MAX_RETRIES = 3
        private const val KEY_MIN_AGE = "min_age_ms"
        private const val KEY_ACCOUNT_ID = "account_id"
        private const val PERIODIC_WORK = "mitelcel_periodic_refresh"
        private const val ONE_SHOT_WORK = "mitelcel_refresh_now"

        private val NEEDS_NETWORK = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedulePeriodic(context: Context) {
            // 30 min, como la versión iOS. Android no permite menos de 15 min para trabajo periódico.
            // SIN restricción de red a propósito: sin señal igual corre para marcar el dato como viejo.
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            // UPDATE (y no KEEP): aplica la configuración nueva al trabajo que ya estaba programado
            // por versiones anteriores, sin reiniciar su reloj.
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** @param accountId null = todas las líneas visibles en widgets. */
        fun refreshNow(context: Context, accountId: String?, minAgeMillis: Long) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(NEEDS_NETWORK)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_MIN_AGE to minAgeMillis, KEY_ACCOUNT_ID to accountId))
                .build()
            // Un nombre por línea: tocar 5 veces el mismo widget corre una sola consulta,
            // pero tocar el widget de OTRA línea sí se atiende.
            WorkManager.getInstance(context)
                .enqueueUniqueWork("$ONE_SHOT_WORK:${accountId ?: "all"}", ExistingWorkPolicy.KEEP, request)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        }
    }
}
