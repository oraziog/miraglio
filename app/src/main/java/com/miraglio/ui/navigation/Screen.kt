package com.miraglio.ui.navigation

sealed class Screen(val route: String) {
    data object Capture : Screen("capture")
    data object Measurement : Screen("measurement")
    data object Calibration : Screen("calibration")
    data object CalibrationTransfer : Screen("calibration_transfer")
    data object Settings : Screen("settings")

    /**
     * Calibrazione di un punto. Distanza, dimensione e asse sono vuoti quando
     * si arriva dal pulsante "+"; sono compilati quando si arriva dal grafico,
     * che sa gia' cosa c'era di sbagliato da rifare.
     */
    data object FocalCapture : Screen(
        "focal_capture?distance={distance}&size={size}&axis={axis}"
    ) {
        const val ARG_DISTANCE = "distance"
        const val ARG_SIZE = "size"
        const val ARG_AXIS = "axis"

        fun route(distanceM: Float? = null, sizeM: Float? = null, axis: String? = null): String =
            "focal_capture?distance=${distanceM ?: ""}&size=${sizeM ?: ""}&axis=${axis ?: ""}"
    }

    data object Places : Screen("places")
    data object PlaceDetail : Screen("places/{id}")
}
