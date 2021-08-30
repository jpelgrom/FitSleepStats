package nl.jpelgrom.fitsleepstats

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.fitness.Fitness
import com.google.android.gms.fitness.FitnessOptions
import com.google.android.gms.fitness.HistoryApi
import com.google.android.gms.fitness.data.DataSet
import com.google.android.gms.fitness.data.DataType
import com.google.android.gms.fitness.data.Field
import com.google.android.gms.fitness.data.Session
import com.google.android.gms.fitness.request.SessionReadRequest
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.streams.toList

class NotificationWorker(appContext: Context, workerParams: WorkerParameters) :
    Worker(appContext, workerParams) {
    private val SLEEP_STAGE_NAMES = arrayOf(
        "Unused",
        "Awake (during sleep)",
        "Sleep",
        "Out-of-bed",
        "Light sleep",
        "Deep sleep",
        "REM sleep"
    )
    private val fitnessOptions = FitnessOptions.builder()
        .addDataType(DataType.TYPE_SLEEP_SEGMENT, FitnessOptions.ACCESS_READ)
        .build()
    private val sharedPref =
        applicationContext.getSharedPreferences("FitSleepStats", Context.MODE_PRIVATE)

    override fun doWork(): Result {
        val existingGoogleAcount = GoogleSignIn.getLastSignedInAccount(applicationContext)
        if (existingGoogleAcount == null || !GoogleSignIn.hasPermissions(
                existingGoogleAcount,
                fitnessOptions
            )
        ) {
            return Result.success()
        }

        val req = SessionReadRequest.Builder()
            .readSessionsFromAllApps()
            .includeSleepSessions()
            .read(DataType.TYPE_SLEEP_SEGMENT)
            .setTimeInterval(
                LocalDateTime.now().minusDays(8).atZone(ZoneId.systemDefault()).toEpochSecond(),
                LocalDateTime.now().atZone(ZoneId.systemDefault()).toEpochSecond(),
                TimeUnit.SECONDS
            )
            .build()
        val sessionRead = Fitness.getSessionsClient(
            applicationContext,
            GoogleSignIn.getAccountForExtension(applicationContext, fitnessOptions)
        )
            .readSession(req)

        while (!sessionRead.isComplete) {
            Thread.sleep(1)
        }
        try {
            val response = sessionRead.getResult(ApiException::class.java)
            val sessions = response.sessions
                .stream()
                .sorted { a, b ->
                    b.getStartTime(TimeUnit.SECONDS).compareTo(a.getStartTime(TimeUnit.SECONDS))
                }
                .filter { !it.isOngoing }
                .limit(7)
                .toList()

            if (sessions.isNotEmpty()) {
                val latestSession = sessions[0]
                if (latestSession.identifier != sharedPref.getString("latestSession", "")) {
                    val sleepDuration =
                        getSleepDuration(latestSession, response.getDataSet(latestSession))
                    val averageSleepDuration = sessions.stream()
                        .mapToLong { getSleepDuration(it, response.getDataSet(it)) }
                        .average().asDouble

                    val sleepDurationHours = floor(sleepDuration / 60 / 60.0)
                    val sleepDurationMinutes = floor((sleepDuration / 60.0) % 60)
                    val averageSleepDurationHours = floor(averageSleepDuration / 60 / 60)
                    val averageSleepDurationMinutes = floor((averageSleepDuration / 60) % 60)
                    val averageSleepComparisonEmoji =
                        if (sleepDuration.toDouble() > averageSleepDuration) "📈" else "📉"

                    val latestSessionSource =
                        response.getDataSet(latestSession)[0].dataPoints[0].originalDataSource
                    val fitIntent = HistoryApi.ViewIntentBuilder(
                        applicationContext,
                        DataType.TYPE_SLEEP_SEGMENT
                    )
                        .setTimeInterval(
                            latestSession.getStartTime(TimeUnit.SECONDS),
                            latestSession.getEndTime(TimeUnit.SECONDS),
                            TimeUnit.SECONDS
                        )
                        .setDataSource(latestSessionSource)
                        .build()
                    val notificationIntent =
                        PendingIntent.getActivity(
                            applicationContext,
                            0,
                            fitIntent,
                            PendingIntent.FLAG_IMMUTABLE
                        )

                    createNotificationChannel()
                    val notification =
                        NotificationCompat.Builder(applicationContext, "sleepsummary")
                            .setContentTitle("Last night's sleep: ${sleepDurationHours.toInt()}h ${sleepDurationMinutes.toInt()}m")
                            .setContentText("$averageSleepComparisonEmoji Weekly average: ${averageSleepDurationHours.toInt()}h ${averageSleepDurationMinutes.toInt()}m")
                            .setSmallIcon(R.drawable.ic_bed)
                            .setContentIntent(notificationIntent)
                            .setAutoCancel(true)
                            .build()
                    with(NotificationManagerCompat.from(applicationContext)) {
                        notify(
                            "${BuildConfig.APPLICATION_ID}-summary-${latestSession.identifier}".hashCode(),
                            notification
                        )
                    }

                    sharedPref.edit().putString("latestSession", latestSession.identifier).apply()
                } // else no change since last check
            }
        } catch (e: ApiException) {
            e.printStackTrace()
        }
        return Result.success()
    }

    private fun getSleepDuration(session: Session, dataSets: List<DataSet>): Long {
        // Simple, backup value
        var duration = session.getEndTime(TimeUnit.SECONDS) - session.getStartTime(TimeUnit.SECONDS)

        // Check for detailed information
        if (dataSets.isNotEmpty()) {
            duration = 0

            for (dataSet in dataSets) {
                for (point in dataSet.dataPoints) {
                    val sleepStageVal =
                        point.getValue(Field.FIELD_SLEEP_SEGMENT_TYPE).asInt()
                    val sleepStage = SLEEP_STAGE_NAMES[sleepStageVal]
                    if (sleepStage != SLEEP_STAGE_NAMES[0] && sleepStage != SLEEP_STAGE_NAMES[1] && sleepStage != SLEEP_STAGE_NAMES[3]) { // it's some form of sleeping
                        duration += (point.getEndTime(TimeUnit.SECONDS) - point.getStartTime(
                            TimeUnit.SECONDS
                        ))
                    }
                }
            }
        }

        return duration
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "sleepsummary",
            "Sleep summary",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "A summary of your latest sleep session"
        }
        val notificationManager: NotificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}