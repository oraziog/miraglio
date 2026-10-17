package com.miraglio.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.miraglio.ui.calibration.CalibrationScreen
import com.miraglio.ui.calibration.CalibrationTransferScreen
import com.miraglio.ui.capture.CaptureScreen
import com.miraglio.ui.focal.FocalCaptureScreen
import com.miraglio.ui.measurement.MeasurementScreen
import com.miraglio.ui.places.PlaceDetailScreen
import com.miraglio.ui.places.PlacesScreen
import com.miraglio.ui.settings.SettingsScreen

@Composable
fun MiraglioNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Capture.route
    ) {
        composable(Screen.Capture.route) {
            CaptureScreen(
                onNavigateToCalibration = { navController.navigate(Screen.Calibration.route) },
                onNavigateToMeasurement = { navController.navigate(Screen.Measurement.route) },
                onNavigateToPlaces = { navController.navigate(Screen.Places.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Measurement.route) {
            MeasurementScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToPlaces = { navController.navigate(Screen.Places.route) }
            )
        }

        composable(Screen.Calibration.route) {
            CalibrationScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToFocalCapture = {
                    navController.navigate(Screen.FocalCapture.route())
                },
                // Dal grafico: la schermata si apre con la misura sbagliata
                // gia' compilata, cosi' "rifai" non vuol dire ricopiare numeri.
                onRedoDistance = { distanceM, sizeM, axis ->
                    navController.navigate(Screen.FocalCapture.route(distanceM, sizeM, axis))
                },
                onNavigateToTransfer = {
                    navController.navigate(Screen.CalibrationTransfer.route)
                }
            )
        }

        composable(Screen.CalibrationTransfer.route) {
            CalibrationTransferScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.FocalCapture.route,
            arguments = listOf(
                navArgument(Screen.FocalCapture.ARG_DISTANCE) {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument(Screen.FocalCapture.ARG_SIZE) {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument(Screen.FocalCapture.ARG_AXIS) {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { entry ->
            val argomenti = entry.arguments
            FocalCaptureScreen(
                onNavigateBack = { navController.popBackStack() },
                prefillDistanceM = argomenti
                    ?.getString(Screen.FocalCapture.ARG_DISTANCE)
                    ?.toFloatOrNull(),
                prefillSizeM = argomenti
                    ?.getString(Screen.FocalCapture.ARG_SIZE)
                    ?.toFloatOrNull(),
                prefillAxis = argomenti
                    ?.getString(Screen.FocalCapture.ARG_AXIS)
                    ?.takeIf { it == "H" || it == "V" }
            )
        }

        composable(Screen.Places.route) {
            PlacesScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenPlace = { id ->
                    navController.navigate("places/$id")
                }
            )
        }

        composable(
            route = Screen.PlaceDetail.route,
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) {
            PlaceDetailScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
