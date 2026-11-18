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
import com.example.smartuse_frontend.nav.AppNavHost
import com.example.smartuse_frontend.nav.AppRoute
import com.example.smartuse_frontend.nav.LaunchDestination
import com.example.smartuse_frontend.nav.appRoute
import com.example.smartuse_frontend.ui.endofstudy.EndOfStudyScreen
import com.example.smartuse_frontend.ui.optout.OptOutDoneScreen
import com.example.smartuse_frontend.ui.optout.OptOutDoneViewModel
import com.example.smartuse_frontend.ui.setup.AutostartSettings
import com.example.smartuse_frontend.ui.setup.SetupScreen
import com.example.smartuse_frontend.ui.setup.SetupViewModel
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
                val hasOptedOut by appContainer.jobStateStore.hasOptedOut
                    .collectAsState(initial = null)
                val collectionEnded by appContainer.jobStateStore.collectionEnded
                    .collectAsState(initial = null)

                // Every launch, and right after setup finishes: learn the cutoff,
                // then end collection or heal the schedule. Skipped once opted out
                // or ended, so a later cold start never re-creates the jobs
                // cancelAll() tore down or calls the backend again.
                LaunchedEffect(setupComplete, hasOptedOut, collectionEnded) {
                    if (setupComplete == true && hasOptedOut == false && collectionEnded == false) {
                        appContainer.launchCheck.run()
                    }
                }

                when (appRoute(setupComplete, hasOptedOut, collectionEnded)) {
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
                                    )
                                }
                            },
                        )
                        SetupScreen(
                            viewModel = setupViewModel,
                            permissionChecker = appContainer.permissionChecker,
                        )
                    }
                    AppRoute.OPT_OUT_DONE -> {
                        val optOutDoneViewModel: OptOutDoneViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer { OptOutDoneViewModel(appContainer.optOutRepository) }
                            },
                        )
                        OptOutDoneScreen(viewModel = optOutDoneViewModel)
                    }
                    AppRoute.END_OF_STUDY -> EndOfStudyScreen()
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
