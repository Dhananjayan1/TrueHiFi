package com.fakehifi.detector.analysis

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

data class HardwareCapabilities(
    val deviceName: String,
    val maxSampleRateHz: Int,
    val maxBitDepth: Int,
    val isHighResCapable: Boolean
)

object HardwareScanner {

    fun getCurrentOutputCapabilities(context: Context): HardwareCapabilities {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        
        // Find the "active" output device (simplification: take the first non-built-in-earpiece if available, or first device)
        val activeDevice = devices.find { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || 
                                       it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || 
                                       it.type == AudioDeviceInfo.TYPE_USB_DEVICE || 
                                       it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                                       it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                          ?: devices.firstOrNull { it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                          ?: devices.firstOrNull()

        if (activeDevice == null) {
            return HardwareCapabilities("Unknown", 48000, 16, false)
        }

        val name = when (activeDevice.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Internal Speakers"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headphones"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB DAC/Headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth (A2DP)"
            else -> "External Device"
        }

        // Query supported sample rates
        val rates = activeDevice.sampleRates
        val maxRate = if (rates.isEmpty()) 48000 else rates.maxOrNull() ?: 48000
        
        // Bit depth is trickier to query directly via Public API, 
        // we'll use device type as a heuristic + Android's default 16/24 limits.
        val maxBit = when (activeDevice.type) {
            AudioDeviceInfo.TYPE_USB_DEVICE -> 24
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 16
            else -> 16
        }

        return HardwareCapabilities(
            deviceName = name,
            maxSampleRateHz = maxRate,
            maxBitDepth = maxBit,
            isHighResCapable = maxRate > 48000
        )
    }
}
