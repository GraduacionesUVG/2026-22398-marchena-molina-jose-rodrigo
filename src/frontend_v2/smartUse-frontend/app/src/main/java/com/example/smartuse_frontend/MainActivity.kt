package com.example.smartuse_frontend

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.smartuse_frontend.data.AppContainer
import com.example.smartuse_frontend.forms.FormDefinitions
import com.example.smartuse_frontend.jobs.JobRuntime
import com.example.smartuse_frontend.nav.AppNavHost
import com.example.smartuse_frontend.nav.AppRoute
import com.example.smartuse_frontend.nav.LaunchDestination
import com.example.smartuse_frontend.nav.appRoute
import com.example.smartuse_frontend.study.EndingState
import com.example.smartuse_frontend.ui.endforms.EndFormsScreen
import com.example.smartuse_frontend.ui.endforms.EndFormsViewModel
import com.example.smartuse_frontend.ui.endofstudy.EndOfStudyScreen
import com.example.smartuse_frontend.ui.setup.AutostartSettings
import com.example.smartuse_frontend.ui.setup.SetupScreen
import com.example.smartuse_frontend.ui.setup.SetupViewModel
import com.example.smartuse_frontend.ui.common.DebugUi
import com.example.smartuse_frontend.ui.setup.usmHasEarlierUsage
import com.example.smartuse_frontend.ui.theme.SmartUsefrontendTheme

class MainActivity : ComponentActivity() {
    private val appContainer by lazy { AppContainer(applicationContext) }

    // Set by a launch from a notification; AppNavHost navigates once and clears it.
    private var pendingDestination by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDestination = intent?.getStringExtra(LaunchDestination.EXTRA)
        enableEdgeToEdge()
        setContent {
            SmartUsefrontendTheme {
                val setupComplete by appContainer.setupStateRepository.isComplete
                    .collectAsState(initial = null)
                val endMarks by appContainer.jobStateStore.endMarks.collectAsState(initial = null)
                val endingState = endMarks?.state

                // Every launch, and at each step of the ending. While live (and set
                // up): learn the cutoff, then end collection or heal the schedule.
                // After the end: never the cycle again; move the ending on
                // (EndingCleanup) and keep the service in its mode.
                LaunchedEffect(setupComplete, endingState) {
                    when {
                        endingState == EndingState.LIVE && setupComplete == true -> appContainer.launchCheck.run()
                        endingState != null && endingState != EndingState.LIVE -> appContainer.resumeEnding()
                    }
                }

                when (appRoute(setupComplete, endingState)) {
                    AppRoute.LOADING -> Unit // brief: DataStore first emissions
                    AppRoute.SETUP -> {
                        val setupViewModel: SetupViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    SetupViewModel(
                                        registrationRepository = appContainer.registrationRepository,
                                        credentialStore = appContainer.credentialStore,
                                        keystoreKeyManager = appContainer.keystoreKeyManager,
                                        setupStateRepository = appContainer.setupStateRepository,
                                        permissionChecker = appContainer.permissionChecker,
                                        studyNotifier = appContainer.studyNotifier,
                                        announcedCutoff = appContainer.sessionRepository::announcedCutoff,
                                        needsAutostartStep = AutostartSettings.needsAutostartStep(Build.MANUFACTURER),
                                        earlierUsageCheck = { start, end ->
                                            (BuildConfig.DEBUG && DebugUi.assumeEarlierUsage) || usmHasEarlierUsage(applicationContext, start, end)
                                        },
                                        buildProfile = { JobRuntime.buildProfileNow(applicationContext) },
                                    )
                                }
                            },
                        )
                        SetupScreen(
                            viewModel = setupViewModel,
                            permissionChecker = appContainer.permissionChecker,
                        )
                    }
                    AppRoute.END_FORMS -> {
                        val endFormsViewModel: EndFormsViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    EndFormsViewModel(
                                        forms = FormDefinitions.END_FORMS,
                                        drafts = appContainer.formDraftStore,
                                        submitter = appContainer.formsSubmitter::submit,
                                    )
                                }
                            },
                        )
                        EndFormsScreen(endFormsViewModel)
                    }
                    AppRoute.ENDED -> EndOfStudyScreen(
                        sent = endingState == EndingState.SENT,
                        optedOut = endMarks?.optedOutAt != null,
                    )
                    AppRoute.APP -> AppNavHost(
                        appContainer = appContainer,
                        pendingDestination = pendingDestination,
                        onDestinationConsumed = { pendingDestination = null },
                    )
                }
            }
        }
    }

    // The activity is already open (SINGLE_TOP) and a notification was tapped.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDestination = intent.getStringExtra(LaunchDestination.EXTRA)
    }
}
