package io.github.aedev.flow.ui.screens.music

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.player.dlna.DlnaCastManager
import io.github.aedev.flow.player.dlna.DlnaDevice
import io.github.aedev.flow.player.dlna.DlnaMusicDriver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Casting the music queue to a TV or speaker from the music player: the picker and the renderer it plays on. */
@HiltViewModel
class MusicCastViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val driver: DlnaMusicDriver,
    ) : ViewModel() {
        val device: StateFlow<DlnaDevice?> = driver.device
        val pickerOpen: MutableStateFlow<Boolean> = driver.pickerOpen
        val devices: StateFlow<List<DlnaDevice>> = DlnaCastManager.devices
        val discovering: StateFlow<Boolean> = DlnaCastManager.isDiscovering

        fun openPicker() {
            if (driver.device.value == null) DlnaCastManager.startDiscovery(context)
            pickerOpen.value = true
        }

        fun cast(device: DlnaDevice) {
            driver.start(device)
            closePicker()
        }

        fun stop() {
            driver.stopCasting()
            closePicker()
        }

        fun closePicker() {
            DlnaCastManager.stopDiscovery()
            pickerOpen.value = false
        }
    }
