/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts.stylus

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.util.Log

/** Address-filtered, bounded scan before the stock LE bonding operation. */
internal class StylusPairingScanner(
    private val adapter: BluetoothAdapter,
    private val handler: Handler,
    private val onResult: (String?) -> Unit,
) : AutoCloseable {
    private var activeCallback: ScanCallback? = null
    private val timeout = Runnable {
        if (activeCallback != null) {
            close()
            onResult(null)
        }
    }

    fun start(address: String) {
        close()
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onResult(null)
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleResult(result.device.address)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { handleResult(it.device.address) }
            }

            private fun handleResult(found: String) {
                handler.post {
                    if (activeCallback === this && found.equals(address, ignoreCase = true)) {
                        close()
                        onResult(address)
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                handler.post {
                    if (activeCallback === this) {
                        close()
                        Log.w(TAG, "Pen scan failed: $errorCode")
                        onResult(null)
                    }
                }
            }
        }
        activeCallback = callback
        try {
            scanner.startScan(
                listOf(ScanFilter.Builder().setDeviceAddress(address).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
            handler.postDelayed(timeout, 20_000L)
        } catch (exception: RuntimeException) {
            close()
            Log.w(TAG, "Could not scan for pen", exception)
            onResult(null)
        }
    }

    override fun close() {
        handler.removeCallbacks(timeout)
        val callback = activeCallback ?: return
        activeCallback = null
        try {
            adapter.bluetoothLeScanner?.stopScan(callback)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not stop pen scan", exception)
        }
    }

    companion object {
        private const val TAG = "PianoStylusScanner"
    }
}
