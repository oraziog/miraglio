package com.miraglio.ui.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miraglio.data.camera.CameraService
import com.miraglio.data.focal.FocalCalibrationStore
import com.miraglio.data.focal.FocalPoint
import com.miraglio.domain.camera.CameraIntrinsics
import com.miraglio.domain.focal.LensSummary
import com.miraglio.domain.focal.lensSummaryWithPoints
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 
 * Calibrazione = solo focale camera (la calibrazione balistica a 2 punti
 * e' stata rimossa: l'app misura distanze).
 * 
 * La calibrazione e' per **lente**, quindi la schermata deve dire quale lente
 * sta mostrando: [activeLensPosition] e' la stessa numerazione che l'utente
 * vede sul mirino ("Lente 2/3"), [lensRows] dice dove sono finiti i punti
 * salvati quando le lenti sono piu' d'una.
 * La distanza dal GPS si compila nello scatto del punto di calibrazione
 * (FocalCapture: "Segna bersaglio" + "Distanza GPS"), dove serve davvero.
 */
@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val focalStore: FocalCalibrationStore,
    cameraService: CameraService
) : ViewModel() {

    val focalPoints: StateFlow<List<FocalPoint>> = focalStore.pointsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val focal: StateFlow<Pair<Float?, Float?>> = focalStore.focalFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Pair(null, null))

    /** Lenti posteriori del telefono, nell'ordine in cui le scorre la home. */ 
    private val backLensIds: List<String> = cameraService.backCameraIds()

    /** Una riga per ogni lente che ha punti salvati: la lente attiva per prima. */ 
    val lensRows: StateFlow<List<LensSummary>> = combine(
        focalStore.pointCountsByLens,
        focalStore.activeLens
    ) { conteggi, attiva -> 
        lensSummaryWithPoints(conteggi, attiva, backLensIds)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 
     * Numero della lente che si sta calibrando e quante lenti posteriori ha il
     * telefono, per l'etichetta "Lente 1/3". Null con una lente sola: dove non
     * c'è ambiguita' un'etichetta non serve.
     */ 
    val activeLensPosition: StateFlow<Pair<Int, Int>?> = focalStore.activeLens
        .map { lens -> positionOf(lens) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Focale teorica alla risoluzione corrente dell'analisi (1280x960). 
    val theoreticalFocalPx: Float =
        CameraIntrinsics.fromDiagonalFov(67f, 1280, 960).fxPixels

    private fun positionOf(lens: String?): Pair<Int, Int>? {
        if (lens == null || backLensIds.size <= 1) return null
        val index = backLensIds.indexOf(lens)
        if (index < 0) return null
        return Pair(index + 1, backLensIds.size)
    }

    fun deleteFocalPoint(id: Long) {
        focalStore.deletePoint(id)
    }

    /** Cancella i punti della lente in calibrazione (le altre lenti restano). */ 
    fun clearActiveLensPoints() {
        focalStore.clearPoints()
    }

    /** Cancella punti e focali di tutte le lenti. */ 
    fun clearAllLensPoints() {
        focalStore.clearAllPoints()
    }
}