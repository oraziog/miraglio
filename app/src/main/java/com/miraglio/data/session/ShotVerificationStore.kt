package com.miraglio.data.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShotVerificationStore @Inject constructor() {

    private val _estimatedDistanceM = MutableStateFlow<Float?>(null)
    val estimatedDistanceM: StateFlow<Float?> = _estimatedDistanceM.asStateFlow()

    private val _measuredDistanceM = MutableStateFlow<Float?>(null)
    val measuredDistanceM: StateFlow<Float?> = _measuredDistanceM.asStateFlow()

    fun setEstimated(distanceM: Float?) {
        _estimatedDistanceM.value = distanceM
    }

    fun setMeasured(distanceM: Float?) {
        _measuredDistanceM.value = distanceM
    }
}