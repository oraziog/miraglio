package com.miraglio.di

import com.miraglio.data.vision.NoOpObjectDetector
import com.miraglio.domain.vision.ObjectDetector
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class VisionModule {

    /** Detection YOLO disattivata: nessun box/quadrato verde nell'app. */
    @Binds
    @Singleton
    abstract fun bindObjectDetector(impl: NoOpObjectDetector): ObjectDetector
}
