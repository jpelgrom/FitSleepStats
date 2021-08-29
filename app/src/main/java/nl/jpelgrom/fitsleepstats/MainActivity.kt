package nl.jpelgrom.fitsleepstats

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.fitness.Fitness
import com.google.android.gms.fitness.FitnessOptions
import com.google.android.gms.fitness.data.DataType
import com.google.android.gms.fitness.data.Field
import com.google.android.gms.fitness.request.SessionReadRequest
import nl.jpelgrom.fitsleepstats.databinding.ActivityMainBinding
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit


class MainActivity : AppCompatActivity() {
    private val TAG = "FitSleepStats"
    private lateinit var binding: ActivityMainBinding
    private lateinit var googleSignInClient: GoogleSignInClient
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.signinButton.setOnClickListener {
            signInToGoogleFit()
        }
        binding.readButton.setOnClickListener {
            readData()
        }
        binding.workerButton.setOnClickListener {
            scheduleWorker()
        }

        fitnessOptions.impliedScopes
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, options)
    }

    override fun onStart() {
        super.onStart()
        val existingGoogleAcount = GoogleSignIn.getLastSignedInAccount(this)
        if (existingGoogleAcount != null
            && GoogleSignIn.hasPermissions(existingGoogleAcount, fitnessOptions)
        ) {
            binding.signinButton.text = "Signed in ✔"
        }
    }

    private fun signInToGoogleFit() {
        startActivityForResult(googleSignInClient.signInIntent, 1)
    }

    private fun getAccessToGoogleFit() {
        GoogleSignIn.requestPermissions(
            this,
            2,
            GoogleSignIn.getLastSignedInAccount(this),
            fitnessOptions
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 1) {
            val signinTask = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                signinTask.getResult(Exception::class.java)
                getAccessToGoogleFit()
            } catch (e: ApiException) {
                Log.w(TAG, "signInResult:failed code=" + e.statusCode);
                binding.signinButton.text = "Failed sign in ❌"
            }
        } else if (requestCode == 2) {
            if (resultCode == Activity.RESULT_OK) {
                binding.signinButton.text = "Signed in ✔"
            } else {
                binding.signinButton.text = "Failed sign in ❌"
            }
        }
    }

    private fun readData() {
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

        Fitness.getSessionsClient(this, GoogleSignIn.getAccountForExtension(this, fitnessOptions))
            .readSession(req)
            .addOnSuccessListener { response ->
                for (session in response.sessions) {
                    val sessionStart = session.getStartTime(TimeUnit.MILLISECONDS)
                    val sessionEnd = session.getEndTime(TimeUnit.MILLISECONDS)
                    Log.i(TAG, "Sleep between $sessionStart and $sessionEnd")

                    // If the sleep session has finer granularity sub-components, extract them:
                    val dataSets = response.getDataSet(session)
                    for (dataSet in dataSets) {
                        for (point in dataSet.dataPoints) {
                            val sleepStageVal =
                                point.getValue(Field.FIELD_SLEEP_SEGMENT_TYPE).asInt()
                            val sleepStage = SLEEP_STAGE_NAMES[sleepStageVal]
                            val segmentStart = point.getStartTime(TimeUnit.MILLISECONDS)
                            val segmentEnd = point.getEndTime(TimeUnit.MILLISECONDS)
                            Log.i(TAG, "\t* Type $sleepStage between $segmentStart and $segmentEnd")
                        }
                    }
                }
            }
            .addOnFailureListener { response ->
                response.printStackTrace()
            }
    }

    private fun scheduleWorker() {
        val summaryWorkerRequest = PeriodicWorkRequestBuilder<NotificationWorker>(2, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "summary",
            ExistingPeriodicWorkPolicy.KEEP, summaryWorkerRequest
        )

        WorkManager.getInstance(this)
            .enqueue(OneTimeWorkRequest.from(NotificationWorker::class.java))
    }
}