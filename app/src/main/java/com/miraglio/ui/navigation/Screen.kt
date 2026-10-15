package com.miraglio.ui.navigation

sealed class Screen(val route: String) {
    data object Capture : Screen("capture")
    data object Measurement : Screen("measurement")
    data object Calibration : Screen("calibration")
    data object Settings : Screen("settings")
    data object FocalCapture : Screen("focal_capture")
    data object Places : Screen("places")
    data object PlaceDetail : Screen("places/{id}")
}
