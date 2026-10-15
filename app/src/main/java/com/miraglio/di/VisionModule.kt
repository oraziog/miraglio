package com.miraglio.di

import com.miraglio.data.vision.YoloObjectDetector
import com.miraglio.domain.vision.ObjectDetector
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class VisionModule {

    /**
     * Il rilevatore YOLO non costa nulla finche' non viene usato: la sessione
     * ONNX e' lazy e nasce alla prima detect(), cioe' al fermo immagine e solo
     * con "Riconoscimento paglione" acceso (vedi
     * CaptureViewModel.analyzeFrozenLines). A interruttore spento il modello
     * non viene mai aperto.
     */
    @Binds
    @Singleton
    abstract fun bindObjectDetector(impl: YoloObjectDetector): ObjectDetector
}
