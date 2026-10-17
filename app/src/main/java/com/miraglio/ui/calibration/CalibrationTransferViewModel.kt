package com.miraglio.ui.calibration

import android.content.Context
import android.os.Build
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miraglio.R
import com.miraglio.data.camera.CameraService
import com.miraglio.data.camera.QrScanAnalyzer
import com.miraglio.data.focal.FocalCalibrationStore
import com.miraglio.domain.calibration.CalibrationBundle
import com.miraglio.domain.calibration.CalibrationTransfer
import com.miraglio.domain.calibration.MappedLens
import com.miraglio.i18n.Texts
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Stato della schermata Condividi/Importa calibrazione.
 *
 * Due meta' che non si parlano: **cosa mando** (il testo costruito dai punti di
 * questo telefono) e **cosa ricevo** (un testo letto da un QR o incollato a
 * mano, in attesa di conferma). La seconda si tiene da parte in [pending] finche'
 * l'utente non conferma: importare e' una scrittura che cancella la
 * calibrazione precedente della lente, quindi si vede prima cosa arriva.
 */
data class TransferUiState(
    /** Marca e modello **di questo** telefono: va nel testo esportato. */
    val device: String = "",
    val exportText: String = "",
    val exportLensCount: Int = 0,
    val exportPointCount: Int = 0,
    /** Il testo non entra in un QR: si condivide come testo (lo dice la card). */
    val tooLongForQr: Boolean = false,
    val importInput: String = "",
    /** La camera sta inquadrando un QR. */
    val scanning: Boolean = false,
    /** Calibrazione letta e in attesa di conferma, con la mappa delle lenti. */
    val pending: CalibrationBundle? = null,
    val mapping: List<MappedLens> = emptyList(),
    val localLensCount: Int = 0,
    /** Esito dell'ultima importazione, o perche' il testo non si legge. */
    val message: String? = null
) {
    val hasExport: Boolean get() = exportPointCount > 0

    /** Lenti del file che qui non esistono: si dicono, non si nascondono. */
    val skippedCount: Int get() = mapping.count { it.skipped }
}

/**
 * Condivisione della calibrazione fra due telefoni.
 *
 * L'export si **ricostruisce** dai punti salvati invece di essere tenuto
 * d'aggiornato: la calibrazione cambia anche dalla schermata accanto (e
 * dall'importazione stessa), e un testo vecchio in giro per messaggi e' la
 * calibrazione di ieri scritta come se fosse di oggi.
 */
@HiltViewModel
class CalibrationTransferViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val focalStore: FocalCalibrationStore,
    private val cameraService: CameraService,
    private val texts: Texts
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransferUiState(device = deviceName()))
    val uiState: StateFlow<TransferUiState> = _uiState.asStateFlow()

    private var scanner: QrScanAnalyzer? = null

    init {
        refreshExport()
    }

    /**
     * Ricostruisce il testo da esportare dai punti salvati, lente per lente.
     *
     * Si esportano solo le lenti che **questo** telefono conosce, numerate come
     * le numera il mirino (1a, 2a, 3a posteriore): l'ordine e' l'unica cosa
     * confrontabile fra due telefoni (vedi [CalibrationTransfer.mapLenses]). Una
     * calibrazione rimasta da una lente che non c'e' piu' resta fuori: non ha
     * una posizione da dichiarare, e inventarla la farebbe finire su un'altra
     * lente del telefono che riceve.
     */
    private fun refreshExport() {
        val bundle = CalibrationTransferMapping.bundle(
            device = _uiState.value.device,
            lensIds = cameraService.backCameraIds(),
            pointsByLens = focalStore.allPointsByLens()
        )
        // Senza punti non si esporta niente: un file con dentro "zero punti"
        // importerebbe zero punti, cioe' cancellerebbe la calibrazione di chi
        // lo riceve. La card lo dice invece di offrire un pulsante inutile.
        val testo = if (bundle.lenses.isEmpty()) "" else CalibrationTransfer.encode(bundle)
        _uiState.update {
            it.copy(
                exportText = testo,
                exportLensCount = bundle.lenses.size,
                exportPointCount = bundle.totalPoints,
                tooLongForQr = testo.length > CalibrationTransfer.MAX_QR_CHARS
            )
        }
    }

    /** Marca e modello del telefono, come li riporta Android. */
    private fun deviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { Build.MODEL }

    // ---- Importazione ----

    fun onImportInputChange(value: String) {
        _uiState.update { it.copy(importInput = value, message = null) }
    }

    /**
     * Legge il testo incollato (o appena letto dal QR). Se regge si apre la
     * conferma con dentro la marca e il modello di chi l'ha mandata; se non
     * regge lo si dice, invece di non fare niente.
     */
    fun readImport() {
        val bundle = CalibrationTransfer.decode(_uiState.value.importInput)
        if (bundle == null) {
            _uiState.update {
                it.copy(
                    pending = null,
                    mapping = emptyList(),
                    message = texts.get(R.string.calibration_transfer_unreadable)
                )
            }
            return
        }
        showPending(bundle)
    }

    private fun showPending(bundle: CalibrationBundle) {
        val locali = cameraService.backCameraIds().size
        _uiState.update {
            it.copy(
                pending = bundle,
                mapping = CalibrationTransfer.mapLenses(bundle, locali),
                localLensCount = locali,
                message = null
            )
        }
    }

    fun cancelImport() {
        _uiState.update { it.copy(pending = null, mapping = emptyList()) }
    }

    /**
     * Scrive la calibrazione importata: le lenti del file vanno su quelle locali
     * **nella stessa posizione**, e quelle che qui non esistono si saltano.
     */
    fun confirmImport() {
        val state = _uiState.value
        val bundle = state.pending ?: return
        val perLente = CalibrationTransferMapping.localPoints(
            bundle = bundle,
            lensIds = cameraService.backCameraIds(),
            nowMillis = System.currentTimeMillis()
        )
        focalStore.replaceLenses(perLente)
        val lenti = perLente.size
        val punti = perLente.values.sumOf { it.size }
        _uiState.update {
            it.copy(
                pending = null,
                mapping = emptyList(),
                importInput = "",
                // Non basta dire "importato": la focale e' di un'altra
                // fotocamera finche' una misura di prova non conferma che qui
                // torna. La schermata del punto lo ricorda con il numero
                // atteso (FocalCaptureViewModel.verificationExpected).
                message = texts.get(R.string.calibration_transfer_imported, lenti, punti) +
                    " " + texts.get(R.string.calibration_transfer_verify_after)
            )
        }
        refreshExport()
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    // ---- Scansione del QR ----

    /**
     * Inquadra un QR. L'analizzatore e' quello condiviso del trasferimento: legge
     * solo codici QR e si ferma al primo che riconosce, perche' il secondo
     * fotogramma direbbe la stessa cosa.
     */
    fun startScan(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if (scanner != null) return
        val analyzer = QrScanAnalyzer { testo ->
            // L'analisi gira su un thread della camera: lo stato si tocca sulla
            // viewModelScope, come tutto il resto.
            viewModelScope.launch { onQrDecoded(testo) }
        }
        scanner = analyzer
        cameraService.setAnalyzer(analyzer)
        cameraService.bindCamera(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            onAnalyzerReady = {}
        )
        _uiState.update { it.copy(scanning = true, message = null) }
    }

    fun stopScan() {
        scanner?.stop()
        scanner = null
        if (_uiState.value.scanning) _uiState.update { it.copy(scanning = false) }
    }

    private fun onQrDecoded(testo: String) {
        stopScan()
        _uiState.update { it.copy(importInput = testo) }
        readImport()
    }
}
