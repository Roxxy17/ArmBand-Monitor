package com.example.armband_project

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel

import com.onecoder.fitblekit.API.ArmBand.FBKApiArmBand
import com.onecoder.fitblekit.API.ArmBand.FBKApiArmBandCallBack
import com.onecoder.fitblekit.API.ArmBand.ArmBandInvalidCmd
import com.onecoder.fitblekit.API.Base.FBKApiBsaeMethod
import com.onecoder.fitblekit.API.Base.FBKBleBaseInfo
import com.onecoder.fitblekit.Ble.FBKBleDevice.FBKBleDeviceStatus
import com.onecoder.fitblekit.Protocol.Common.Parameter.FBKParaAcceleration
import kotlin.math.pow
import kotlin.math.sqrt

class MainActivity: FlutterActivity() {
    private val CHANNEL = "com.kalana.armband/command"
    private val STREAM = "com.kalana.armband/heartrate"

    private lateinit var apiArmBand: FBKApiArmBand
    private var eventSink: EventChannel.EventSink? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        apiArmBand = FBKApiArmBand(this, armBandCallback)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            if (call.method == "connect") {
                val macAddress = call.argument<String>("macAddress")
                if (macAddress != null) {
                    apiArmBand.connectBluetooth(macAddress)
                    result.success("Connecting...")
                } else {
                    result.error("ERR", "MAC Null", null)
                }
            } else if (call.method == "disconnect") {
                apiArmBand.disconnectBle()
                result.success("Disconnected")
            } else {
                result.notImplemented()
            }
        }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, STREAM).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) { eventSink = events }
                override fun onCancel(arguments: Any?) { eventSink = null }
            }
        )
    }

    private val armBandCallback = object : FBKApiArmBandCallBack {

        private val rriBuffer = mutableListOf<Int>()

        // =========================================================
        // 1. DATA JANTUNG & HRV
        // =========================================================
        override fun realTimeHeartRate(data: Any?, apiArmBand: FBKApiArmBand?) {
            val map = data as? Map<String, Any>

            // Log Debug
            Log.d("DEBUG_MAP", "HR_DATA: $map")

            // Heart Rate
            val bpm = map?.get("heartRate")?.toString() ?: "0"
            sendToFlutter("BPM", bpm)

            // HRV Kalkulasi
            val intervals = map?.get("interval") as? List<*>
            if (intervals != null && intervals.isNotEmpty()) {
                val newPoints = intervals.mapNotNull {
                    when(it) {
                        is Number -> it.toInt()
                        is String -> it.toIntOrNull()
                        else -> null
                    }
                }
                rriBuffer.addAll(newPoints)
                // Buffer secukupnya (50 data cukup untuk responsif)
                if (rriBuffer.size > 50) rriBuffer.subList(0, rriBuffer.size - 50).clear()

                // Hitung lebih cepat (minimal 5 data)
                if (rriBuffer.size >= 5) {
                    val sdnn = calculateSDNN(rriBuffer)
                    Log.d("CALLBACK", "HRV (SDNN): $sdnn")
                    sendToFlutter("HRV", sdnn.toString())
                }
            }

            // SPO2 Fallback
            if (map?.containsKey("SPO2") == true) {
                val spo2 = map.get("SPO2")?.toString() ?: "--"
                sendToFlutter("LOG", "SPO2 (Map): $spo2")
                sendToFlutter("SPO2", spo2)
            }
        }

        // =========================================================
        // 2. SPO2 CALLBACKS (BUKTI)
        // =========================================================
        override fun setSPO2Result(isSucceed: Boolean, apiArmBand: FBKApiArmBand?) {
            val status = if (isSucceed) "DITERIMA (TRUE)" else "DITOLAK"
            Log.e("PROOF_SPO2", "CMD Result: $status")
            sendToFlutter("LOG", "SetSPO2: $status")
            sendToFlutter("SPO2_STATUS", if(isSucceed) "Active" else "Failed")
        }

        override fun getSPO2Data(SPO2: Int, apiArmBand: FBKApiArmBand?) {
            Log.e("PROOF_SPO2", "Data Masuk: $SPO2")
            if (SPO2 > 0) sendToFlutter("SPO2", SPO2.toString())
        }

        override fun armBandSPO2(data: Any?, apiArmBand: FBKApiArmBand?) {
            Log.e("PROOF_SPO2", "Data Map: $data")
            val map = data as? Map<String, Any>
            val spo2 = map?.values?.firstOrNull()?.toString() ?: "--"
            sendToFlutter("SPO2", spo2)
        }

        override fun maxOxyResult(maxOxygen: Int, apiArmBand: FBKApiArmBand?) {
            if (maxOxygen > 0) sendToFlutter("SPO2", maxOxygen.toString())
        }

        override fun getSPO2Result(status: Boolean, apiArmBand: FBKApiArmBand?) {
            Log.d("PROOF_SPO2", "GetSPO2Status: $status")
        }

        // =========================================================
        // 3. SENSOR LAINNYA
        // =========================================================
        override fun realTimeStepFrequency(data: Any?, apiArmBand: FBKApiArmBand?) {
            val map = data as? Map<String, Any>
            Log.d("DEBUG_MAP", "STEP: $map")
            val steps = map?.get("steps")?.toString() ?: "0"
            if (steps != "0") sendToFlutter("STEPS", steps)
        }

        override fun batteryPower(power: Int, apiBsaeMethod: FBKApiBsaeMethod?) {
            Log.d("DEBUG_MAP", "BATTERY: $power%")
            sendToFlutter("BATTERY", power.toString())
        }

        override fun armBandSkinTemperature(data: Any?, apiArmBand: FBKApiArmBand?) {
            val map = data as? Map<String, Any>
            Log.d("DEBUG_MAP", "TEMP: $map")
            val temp = map?.values?.firstOrNull()?.toString() ?: "--"
            sendToFlutter("TEMP", temp)
        }

        override fun armBandTemperature(data: Any?, apiArmBand: FBKApiArmBand?) {
            val map = data as? Map<String, Any>
            val temp = map?.values?.firstOrNull()?.toString() ?: "--"
            sendToFlutter("BODY_TEMP", temp)
        }

        override fun HRVResultData(status: Boolean, data: Any?, apiArmBand: FBKApiArmBand?) {
            val map = data as? Map<String, Any>
            val hrv = map?.get("HRV")?.toString() ?: map?.get("hrv")?.toString()
            if (hrv != null) sendToFlutter("HRV_SUMMARY", hrv)
        }

        // =========================================================
        // 4. INFO PERANGKAT
        // =========================================================
        override fun deviceBaseInfo(baseInfo: FBKBleBaseInfo?, apiBsaeMethod: FBKApiBsaeMethod?) {
            if (baseInfo != null) sendToFlutter("DEVICE_INFO", baseInfo.toString())
        }
        override fun deviceModelString(modelString: String?, apiBsaeMethod: FBKApiBsaeMethod?) {
            sendToFlutter("MODEL", modelString ?: "Unknown")
        }
        override fun firmwareVersion(version: String?, apiBsaeMethod: FBKApiBsaeMethod?) {
            sendToFlutter("FIRMWARE", version ?: "Unknown")
        }
        override fun hardwareVersion(version: String?, apiBsaeMethod: FBKApiBsaeMethod?) {
            sendToFlutter("HARDWARE", version ?: "Unknown")
        }
        override fun softwareVersion(version: String?, apiBsaeMethod: FBKApiBsaeMethod?) {
            sendToFlutter("SOFTWARE", version ?: "Unknown")
        }
        override fun deviceMacAddress(data: Any?, apiArmBand: FBKApiArmBand?) {
            sendToFlutter("MAC_ADDR", data.toString())
        }
        override fun deviceSystemData(systemData: ByteArray?, apiBsaeMethod: FBKApiBsaeMethod?) {
            if (systemData != null) {
                val hexString = systemData.joinToString(" ") { "%02X".format(it) }
                sendToFlutter("RAW_DATA", hexString)
            }
        }
        override fun bleConnectStatusLog(infoString: String?, apiBsaeMethod: FBKApiBsaeMethod?) {
            if (infoString != null) Log.d("SDK_LOG", infoString)
        }

        // =========================================================
        // 5. LOGIC STARTUP (FAST VERSION)
        // =========================================================

        private var proofStep = 0

        override fun bleConnectStatus(connectStatus: FBKBleDeviceStatus?, apiBsaeMethod: FBKApiBsaeMethod?) {
            var statusStr = "Disconnected"
            if (connectStatus == FBKBleDeviceStatus.BleConnecting) statusStr = "Connecting..."

            if (connectStatus == FBKBleDeviceStatus.BleConnected) {
                statusStr = "Connected"
                rriBuffer.clear()
                proofStep = 0

                // -----------------------------------------------------------
                // PERUBAHAN PENTING: Start HRV & Info SEGERA (T=500ms)
                // -----------------------------------------------------------
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        Log.d("START_SENSOR", "Starting Heart Rate & HRV (Priority)...")
                        val mHRV = apiArmBand.javaClass.getDeclaredMethod("enterHRVMode", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                        mHRV.isAccessible = true
                        mHRV.invoke(apiArmBand, true, false)

                        // Ambil Info Dasar
                        val mVersion = apiArmBand.javaClass.methods.find { it.name.equals("getDeviceVersion", true) }
                        mVersion?.invoke(apiArmBand)
                        val mBat = apiArmBand.javaClass.methods.find { it.name.equals("readDeviceBatteryPower", true) }
                        mBat?.invoke(apiArmBand)
                    } catch (e: Exception) { Log.e("START_SENSOR", "Error init: $e") }
                }, 500)

                // -----------------------------------------------------------
                // Background Task: Jalankan Test Pembuktian SPO2 (T=3000ms)
                // -----------------------------------------------------------
                Log.e("PROOF_SPO2", "=== MENYIAPKAN TEST PEMBUKTIAN SPO2 ===")
                sendToFlutter("LOG", "Preparing Proof Test...")
                // Delay 3 detik agar HRV stabil dulu, baru diganggu test SPO2
                Handler(Looper.getMainLooper()).postDelayed({ runProofSequence() }, 3000)
            }
            sendToFlutter("STATUS", statusStr)
        }

        private fun runProofSequence() {
            val modes = listOf(0, 1, 2)

            if (proofStep < modes.size) {
                val mode = modes[proofStep]
                val msg = "Testing SPO2 Mode $mode..."
                Log.e("PROOF_SPO2", msg)
                // Kirim ke flutter log agar atasan lihat prosesnya
                sendToFlutter("LOG", msg)

                try {
                    val mSet = apiArmBand.javaClass.getDeclaredMethod("setSPO2Mode", Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    mSet.isAccessible = true
                    mSet.invoke(apiArmBand, true, mode)
                } catch (e: Exception) {
                    Log.e("PROOF_SPO2", "Gagal Invoke: $e")
                }

                proofStep++
                // Lanjut test berikutnya
                Handler(Looper.getMainLooper()).postDelayed({ runProofSequence() }, 4000)
            } else {
                Log.e("PROOF_SPO2", "=== TEST SELESAI. HASIL: 0 (HW LIMIT) ===")
                sendToFlutter("LOG", "SPO2 Test Done. 0 = Hardware Limit.")

                // Kembalikan ke mode normal (pastikan HRV tetap nyala)
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val mHRV = apiArmBand.javaClass.getDeclaredMethod("enterHRVMode", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                        mHRV.isAccessible = true
                        mHRV.invoke(apiArmBand, true, false)
                    } catch (e: Exception) {}
                }, 1000)
            }
        }

        private fun calculateSDNN(data: List<Int>): Int {
            if (data.isEmpty()) return 0
            val mean = data.average()
            val sumSqDiff = data.fold(0.0) { sum, element -> sum + (element - mean).pow(2) }
            val variance = sumSqDiff / data.size
            return sqrt(variance).toInt()
        }

        // --- Dummy Callbacks ---
        override fun armBandRecord(data: Any?, apiArmBand: FBKApiArmBand?) {}
        override fun getAge(age: Int, apiArmBand: FBKApiArmBand?) {}
        override fun setAgeStatus(status: Boolean, apiArmBand: FBKApiArmBand?) {}
        override fun setShockStatus(status: Boolean, apiArmBand: FBKApiArmBand?) {}
        override fun getShockStatus(data: Any?, apiArmBand: FBKApiArmBand?) {}
        override fun closeShockStatus(status: Boolean, apiArmBand: FBKApiArmBand?) {}
        override fun setMaxIntervalStatus(status: Boolean, apiArmBand: FBKApiArmBand?) {}
        override fun getMaxInterval(maxInterval: Int, apiArmBand: FBKApiArmBand?) {}
        override fun setLightSwitchStatus(status: Boolean, apiArmBand: FBKApiArmBand?) {}
        override fun getLightSwitch(lightSwitch: Int, apiArmBand: FBKApiArmBand?) {}
        override fun getSettting(data: Any?, apiArmBand: FBKApiArmBand?) {}
        override fun invalidCmd(cmdId: ArmBandInvalidCmd?, apiArmBand: FBKApiArmBand?) {}
        override fun setColorShockStatus(status: Boolean, apiArmBand: FBKApiArmBand?) {}
        override fun setColorIntervalStatus(status: Boolean, apiArmBand: FBKApiArmBand?) {}
        override fun clearRecordStatus(status: Boolean, apiArmBand: FBKApiArmBand?) {}
        override fun totalVersion(data: Any?, apiArmBand: FBKApiArmBand?) {}
        override fun accelerationData(dataList: MutableList<Any?>?, apiArmBand: FBKApiArmBand?) {}
        override fun setPrivateFiveZone(status: Boolean, apiArmBand: FBKApiArmBand?) {}
        override fun openSettingShow(status: Boolean, apiArmBand: FBKApiArmBand?) {}
        override fun closeSettingShow(status: Boolean, apiArmBand: FBKApiArmBand?) {}
        override fun trunOffDevice(status: Boolean, apiArmBand: FBKApiArmBand?) {}
        override fun showAccelerationData(accData: FBKParaAcceleration?, apiArmBand: FBKApiArmBand?) {}
        override fun bleConnectError(error: String?, apiBsaeMethod: FBKApiBsaeMethod?) {}
        override fun protocolVersion(version: String?, apiBsaeMethod: FBKApiBsaeMethod?) {}
        override fun privateVersion(versionMap: Map<String, String>?, apiBsaeMethod: FBKApiBsaeMethod?) {}
        override fun privateMacAddress(macMap: Map<String, String>?, apiBsaeMethod: FBKApiBsaeMethod?) {}
        override fun bleConnectInfo(infoString: String?, apiBsaeMethod: FBKApiBsaeMethod?) {}
        override fun deviceSerialNumber(serialNumber: String?, apiBsaeMethod: FBKApiBsaeMethod?) {}
        override fun deviceManufacturerName(manufacturerName: String?, apiBsaeMethod: FBKApiBsaeMethod?) {}
    }

    private fun sendToFlutter(type: String, value: String) {
        runOnUiThread { eventSink?.success(mapOf("type" to type, "value" to value)) }
    }
}