package com.miraglio.ui.calibration

import androidx.lifecycle.ViewModel
import com.miraglio.data.focal.FocalCalibrationStore
import com.miraglio.data.focal.FocalPoint
import com.miraglio.domain.camera.CameraIntrinsics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import javax.inject.Inject

/**
 * Calibrazione = solo focale camera (la calibrazione balistica a 2 punti
 * e' stata rimossa: l'app misura distanze).
 */
@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val focalStore: FocalCalibrationStore
) : ViewModel() {

    val focalPoints: StateFlow<List<FocalPoint>> = focalStore.pointsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val focal: StateFlow<Pair<Float?, Float?>> = focalStore.focalFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Pair(null, null))

    // Focale teorica alla risoluzione corrente dell'analisi (1280x960).
    val theoreticalFocalPx: Float =
        CameraIntrinsics.fromDiagonalFov(67f, 1280, 960).fxPixels

    fun deleteFocalPoint(id: Long) {
        focalStore.deletePoint(id)
    }
}
