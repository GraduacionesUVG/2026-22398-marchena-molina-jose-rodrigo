package com.example.smartuse_frontend.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.smartuse_frontend.data.AppContainer
import com.example.smartuse_frontend.jobs.JobLimits
import com.example.smartuse_frontend.ui.dataoverview.DataOverviewScreen
import com.example.smartuse_frontend.ui.dataoverview.DataOverviewViewModel
import com.example.smartuse_frontend.ui.optout.OptOutScreen
import com.example.smartuse_frontend.ui.optout.OptOutViewModel
import com.example.smartuse_frontend.ui.status.StatusScreen
import com.example.smartuse_frontend.ui.status.StatusViewModel
import com.example.smartuse_frontend.ui.terms.TermsScreen
import com.example.smartuse_frontend.ui.welcome.WelcomeScreen
import com.example.smartuse_frontend.ui.welcome.WelcomeViewModel

@Composable
fun AppNavHost(
    appContainer: AppContainer,
    pendingDestination: String? = null,
    onDestinationConsumed: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Routes.WELCOME) {
        composable(Routes.WELCOME) {
            val viewModel: WelcomeViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        WelcomeViewModel(
                            sessionRepository = appContainer.sessionRepository,
                            permissionChecker = appContainer.permissionChecker,
                            interventionsBox = appContainer.interventionsBox,
                        )
                    }
                },
            )
            WelcomeScreen(
                viewModel = viewModel,
                onNavigateToStatus = { navController.navigate(Routes.STATUS) },
                onNavigateToDataOverview = { navController.navigate(Routes.DATA_OVERVIEW) },
                onNavigateToOptOut = { navController.navigate(Routes.OPT_OUT) },
                onNavigateToTerms = { navController.navigate(Routes.TERMS) },
            )
        }
        composable(Routes.STATUS) {
            val viewModel: StatusViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        StatusViewModel(
                            permissionChecker = appContainer.permissionChecker,
                            jobStateStore = appContainer.jobStateStore,
                            stuckAfterFailedRuns = JobLimits.current.stuckAfterFailedRuns,
                        )
                    }
                },
            )
            StatusScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onNavigateToOptOut = { navController.navigate(Routes.OPT_OUT) },
            )
        }
        composable(Routes.DATA_OVERVIEW) {
            val viewModel: DataOverviewViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        DataOverviewViewModel(
                            usageStatsRepository = appContainer.usageStatsRepository,
                            profileRepository = appContainer.profileRepository,
                            credentialStore = appContainer.credentialStore,
                        )
                    }
                },
            )
            DataOverviewScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.OPT_OUT) {
            val viewModel: OptOutViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { OptOutViewModel(appContainer.optOutRepository) }
                },
            )
            OptOutScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.TERMS) {
            TermsScreen(onBack = { navController.popBackStack() })
        }
    }

    // A launch from the permission notification: open the requested screen once,
    // on top of Welcome so Back returns there.
    LaunchedEffect(pendingDestination) {
        if (pendingDestination != null) {
            LaunchDestination.route(pendingDestination)?.let { route ->
                navController.navigate(route) { launchSingleTop = true }
            }
            onDestinationConsumed()
        }
    }
}
