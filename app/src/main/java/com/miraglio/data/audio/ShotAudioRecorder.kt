package com.miraglio.data.audio

import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Un pezzo di audio già in memoria, pronto per essere scritto dove vuole
 * l'utente.
 *
 * È il risultato di [ShotAudioRecorder.clip]: campioni PCM 16 bit già
 * convertiti, più la frequenza di campionamento per l'intestazione WAV. Non
 * tocca il disco finché qualcuno non chiama [writeTo]: finché l'utente non
 * preme Salva, il file non esiste e non occupa spazio.
 */
class ShotAudioClip(
    private val pcm16: ByteArray,
    val sampleRateHz: Int,
    val fileName: String
) {

    val sizeBytes: Int get() = WAV_HEADER_BYTES + pcm16.size

    /** Scrive un WAV PCM 16 bit mono completo (intestazione e dati). */
    fun writeTo(stream: OutputStream) {
        val dataSize = pcm16.size
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(1) // mono
        header.putInt(sampleRateHz)
        header.putInt(sampleRateHz * 2) // byte al secondo
        header.putShort(2) // allineamento
        header.putShort(16) // bit per campione
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)

        stream.write(header.array())
        stream.write(pcm16)
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44
    }
}

/**
 * Tiene in memoria gli ultimi secondi di audio del telemetro, in un buffer
 * circolare.
 *
 * Serve al debugging sul campo: quando una misura non torna (Δt troppo corto,
 * impatto scambiato per l'eco del rilascio) l'unica prova è riascoltare quello
 * che il telefono ha davvero sentito. Il microfono consegna blocchi da 20 ms già
 * in float; qui si accumulano come PCM 16 bit, che è il formato che qualunque
 * programma di analisi apre senza conversioni.
 *
 * **Perché circolare.** Con l'ascolto continuo una sessione può durare quanto
 * una sessione di tiro: un buffer che cresce sarebbe 96 kB al secondo, cioè
 * cinquantotto megabyte in dieci minuti e oltre centosettanta in mezz'ora, fino
 * al crash. Qui la memoria è fissa: [KEEP_SECONDS] di audio (circa un megabyte),
 * gli ultimi, che sono gli unici che possono servire.
 *
 * **Perché il salvataggio è a richiesta.** Il WAV è uno strumento di messa a
 * punto, non un archivio: [clip] restituisce i campioni del tiro senza scrivere
 * niente, e chi chiama li scrive solo se l'utente ha scelto una cartella. In
 * calibrazione resta [save], che scrive subito il file nella cartella privata
 * dell'app per la condivisione.
 *
 * Non conosce Android: prende una cartella e ci scrive. Così la scrittura del
 * WAV si prova su JVM come il resto del DSP.
 */
class ShotAudioRecorder(
    private val sampleRateHz: Int,
    private val directory: File,
    private val keepFiles: Int = MAX_FILES,
    keepSeconds: Float = KEEP_SECONDS
) {

    private val capacitySamples =
        (keepSeconds * sampleRateHz).toInt().coerceAtLeast(sampleRateHz)

    /** Sempre pari: una coppia di byte non viene mai spezzata dal giro. */
    private val capacityBytes = capacitySamples * 2
    private val ring = ByteArray(capacityBytes)

    private var writeByte = 0

    /** Campioni validi nel buffer: satura a [capacitySamples]. */
    private var filledSamples = 0L

    /** Campioni scritti dall'inizio: è l'indice assoluto del flusso. */
    var totalSamples: Long = 0L
        private set

    fun start() {
        writeByte = 0
        filledSamples = 0L
        totalSamples = 0L
    }

    /** Aggiunge un blocco di campioni, gli stessi che vede il DSP. */
    fun append(samples: FloatArray, length: Int) {
        for (i in 0 until length) {
            val value = samples[i]
            val clipped = if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f
            val quantized = (clipped * 32767f).toInt()
            ring[writeByte] = (quantized and 0xFF).toByte()
            writeByte++
            if (writeByte >= capacityBytes) writeByte = 0
            ring[writeByte] = ((quantized shr 8) and 0xFF).toByte()
            writeByte++
            if (writeByte >= capacityBytes) writeByte = 0
            totalSamples++
            if (filledSamples < capacitySamples) filledSamples++
        }
    }

    /**
     * Copia i campioni fra [fromSample] e [toSample] (indici assoluti del
     * flusso, `null` = inizio/fine di quello che c'è) e li restituisce pronti
     * per essere scritti. Non tocca il disco e non consuma il buffer: dopo il
     * prelievo l'ascolto continua normalmente.
     */
    fun clip(fromSample: Long?, toSample: Long?, fileName: String): ShotAudioClip? {
        if (filledSamples == 0L) return null
        val first = totalSamples - filledSamples
        val start = (fromSample ?: first).coerceIn(first, totalSamples)
        val end = (toSample ?: totalSamples).coerceIn(start, totalSamples)
        val count = (end - start).toInt()
        if (count <= 0) return null

        val bytes = ByteArray(count * 2)
        val startByte = ((start % capacitySamples) * 2).toInt()
        val firstChunk = minOf(bytes.size, capacityBytes - startByte)
        System.arraycopy(ring, startByte, bytes, 0, firstChunk)
        if (firstChunk < bytes.size) {
            System.arraycopy(ring, 0, bytes, firstChunk, bytes.size - firstChunk)
        }
        return ShotAudioClip(bytes, sampleRateHz, fileName)
    }

    /**
     * Scrive subito il WAV nella cartella del registratore. È il percorso della
     * calibrazione, dove il file serve alla condivisione; la misura continua usa
     * [clip] e scrive solo se l'utente lo chiede.
     */
    fun save(fileName: String, fromSample: Long? = null, toSample: Long? = null): File? {
        val clip = clip(fromSample, toSample, fileName) ?: return null
        directory.mkdirs()
        val file = File(directory, fileName)
        return runCatching {
            file.outputStream().use { stream -> clip.writeTo(stream) }
            file
        }.getOrNull()?.also { prune() }
    }

    /** Butta la registrazione senza salvarla. */
    fun discard() {
        start()
    }

    /** Tiene solo le ultime registrazioni: il telefono non è un archivio. */
    private fun prune() {
        val files = directory.listFiles { file -> file.isFile && file.extension == "wav" }
            ?: return
        if (files.size <= keepFiles) return
        files.sortedByDescending { it.lastModified() }
            .drop(keepFiles)
            .forEach { it.delete() }
    }

    private companion object {
        /** Le ultime registrazioni restano disponibili per la condivisione. */
        const val MAX_FILES = 12

        /**
         * Quanto audio resta in memoria: dodici secondi.
         *
         * Il pezzo che si salva è al più di sei secondi (due prima del rilascio,
         * due dopo l'impatto, più il tempo di volo), quindi c'è il doppio del
         * margine e la memoria resta intorno a un megabyte.
         */
        const val KEEP_SECONDS = 12f
    }
}
