package com.miraglio.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.miraglio.ui.calibration.CalibrationScreen
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
                onNavigateToFocalCapture = { navController.navigate(Screen.FocalCapture.route) }
            )
        }

        composable(Screen.FocalCapture.route) {
            FocalCaptureScreen(
                onNavigateBack = { navController.popBackStack() }
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
