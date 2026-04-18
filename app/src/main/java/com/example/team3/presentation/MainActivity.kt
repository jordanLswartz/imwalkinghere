package com.example.team3.presentation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.example.team3.presentation.theme.Team3Theme
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            Team3Theme {
                SensorApp()
            }
        }
    }
}

private data class AccelerometerReading(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val accuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE,
    val hasReading: Boolean = false,
) {
    val magnitude: Float
        get() = sqrt((x * x) + (y * y) + (z * z))
}

private data class GyroscopeReading(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val accuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE,
    val hasReading: Boolean = false,
) {
    val magnitude: Float
        get() = sqrt((x * x) + (y * y) + (z * z))
}

private data class SensorSample(
    val watchWallTimeMs: Long,
    val sensorEventTimeNs: Long,
    val recordingElapsedMs: Long,
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val accelMagnitude: Float,
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
    val gyroMagnitude: Float,
    val accelAccuracy: Int,
    val gyroAccuracy: Int,
)

private class UdpCsvStreamer {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var socket: DatagramSocket? = null

    fun send(
        host: String,
        port: Int,
        line: String,
    ) {
        executor.execute {
            try {
                val activeSocket =
                    socket ?: DatagramSocket().also {
                        socket = it
                    }
                val payload = line.toByteArray(Charsets.UTF_8)
                val packet =
                    DatagramPacket(
                        payload,
                        payload.size,
                        InetAddress.getByName(host),
                        port,
                    )
                activeSocket.send(packet)
            } catch (error: Exception) {
                Log.e("Team3", "UDP send failed", error)
            }
        }
    }

    fun close() {
        socket?.close()
        socket = null
        executor.shutdownNow()
    }
}

private const val SAMPLE_PERIOD_US = 100_000
private const val RECORDING_DURATION_NS = 30_000_000_000L
private const val RECORDING_PENDING_START_NS = -1L

@Composable
private fun SensorApp() {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val streamer = remember { UdpCsvStreamer() }
    DisposableEffect(Unit) {
        onDispose {
            streamer.close()
        }
    }

    var isSampling by rememberSaveable { mutableStateOf(true) }
    var recordingStartNs by remember { mutableStateOf<Long?>(null) }
    var recordingStatus by rememberSaveable { mutableStateOf("Not recording") }
    var savedFileName by rememberSaveable { mutableStateOf("") }
    var recordedSampleCount by rememberSaveable { mutableStateOf(0) }
    val recordedSamples = remember { mutableListOf<SensorSample>() }

    var streamHost by rememberSaveable { mutableStateOf("") }
    var streamPortText by rememberSaveable { mutableStateOf("8989") }
    var isStreaming by rememberSaveable { mutableStateOf(false) }
    var streamStatus by rememberSaveable { mutableStateOf("Streaming off") }

    val gyroReading = rememberGyroscopeReading(isSampling = isSampling)

    val accelerometerReading =
        rememberAccelerometerReading(
            isSampling = isSampling,
            onSample = { timestampNs, accelerometer ->
                val watchWallTimeMs = System.currentTimeMillis()
                val sample =
                    SensorSample(
                        watchWallTimeMs = watchWallTimeMs,
                        sensorEventTimeNs = timestampNs,
                        recordingElapsedMs = 0L,
                        accelX = accelerometer.x,
                        accelY = accelerometer.y,
                        accelZ = accelerometer.z,
                        accelMagnitude = accelerometer.magnitude,
                        gyroX = gyroReading.x,
                        gyroY = gyroReading.y,
                        gyroZ = gyroReading.z,
                        gyroMagnitude = gyroReading.magnitude,
                        accelAccuracy = accelerometer.accuracy,
                        gyroAccuracy = gyroReading.accuracy,
                    )

                if (isStreaming) {
                    val port = streamPortText.toIntOrNull()
                    if (streamHost.isNotBlank() && port != null) {
                        streamer.send(
                            host = streamHost.trim(),
                            port = port,
                            line = sample.toCsvLine(),
                        )
                    }
                }

                val startNs =
                    when (val currentStart = recordingStartNs) {
                        null -> return@rememberAccelerometerReading
                        RECORDING_PENDING_START_NS -> {
                            recordingStartNs = timestampNs
                            timestampNs
                        }
                        else -> currentStart
                    }
                val elapsedNs = timestampNs - startNs

                if (elapsedNs <= RECORDING_DURATION_NS) {
                    recordedSamples += sample.copy(recordingElapsedMs = elapsedNs / 1_000_000)
                    recordedSampleCount = recordedSamples.size
                    val secondsRemaining =
                        ((RECORDING_DURATION_NS - elapsedNs + 999_999_999L).coerceAtLeast(0L) / 1_000_000_000L)
                    recordingStatus = "Recording... ${secondsRemaining}s left"
                } else {
                    val outputFile = writeCsv(context, recordedSamples)
                    savedFileName = outputFile.name
                    recordingStatus = "Saved ${recordedSamples.size} rows"
                    recordingStartNs = null
                    recordedSamples.clear()
                }
            },
        )

    AppScaffold {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Watch Sensors",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text =
                        when {
                            !isSampling -> "Paused"
                            !accelerometerReading.hasReading -> "Waiting for sensor data..."
                            else -> "Streaming at 10 Hz"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                )

                Text(
                    text = "Accelerometer (m/s²)",
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                )
                ReadingLine(label = "AX", value = accelerometerReading.x)
                ReadingLine(label = "AY", value = accelerometerReading.y)
                ReadingLine(label = "AZ", value = accelerometerReading.z)
                ReadingLine(label = "AMag", value = accelerometerReading.magnitude)
                Text(
                    text = "Accel accuracy: ${accuracyLabel(accelerometerReading.accuracy)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )

                Text(
                    text = "Gyroscope (rad/s)",
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
                ReadingLine(label = "GX", value = gyroReading.x)
                ReadingLine(label = "GY", value = gyroReading.y)
                ReadingLine(label = "GZ", value = gyroReading.z)
                ReadingLine(label = "GMag", value = gyroReading.magnitude)
                Text(
                    text = "Gyro accuracy: ${accuracyLabel(gyroReading.accuracy)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )

                Text(
                    text = "UDP stream to Mac",
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                )
                LabeledInput(
                    label = "Mac IP",
                    value = streamHost,
                    onValueChange = { streamHost = it },
                    keyboardType = KeyboardType.Decimal,
                )
                LabeledInput(
                    label = "Port",
                    value = streamPortText,
                    onValueChange = { newValue ->
                        streamPortText = newValue.filter { it.isDigit() }
                    },
                    keyboardType = KeyboardType.Number,
                )
                Text(
                    text = streamStatus,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )

                Button(
                    onClick = {
                        val port = streamPortText.toIntOrNull()
                        if (streamHost.isBlank() || port == null) {
                            streamStatus = "Set Mac IP and port"
                        } else {
                            isStreaming = !isStreaming
                            streamStatus =
                                if (isStreaming) {
                                    "Sending to ${streamHost.trim()}:$port"
                                } else {
                                    "Streaming off"
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (isStreaming) "Stop UDP Stream" else "Start UDP Stream")
                }

                Text(
                    text = recordingStatus,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )

                if (recordingStartNs != null) {
                    Text(
                        text = "Samples: $recordedSampleCount",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                } else if (savedFileName.isNotBlank()) {
                    Text(
                        text = savedFileName,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                Button(
                    onClick = { isSampling = !isSampling },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (isSampling) "Pause" else "Resume")
                }

                Button(
                    onClick = {
                        recordedSamples.clear()
                        recordedSampleCount = 0
                        savedFileName = ""
                        recordingStartNs = RECORDING_PENDING_START_NS
                        recordingStatus = "Waiting for first sample..."
                        isSampling = true
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    enabled = recordingStartNs == null,
                ) {
                    Text("Record 30s CSV")
                }
            }
        }
    }
}

@Composable
private fun LabeledInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth(),
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle =
            TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            ),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = ImeAction.Done,
            ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        decorationBox = { innerTextField ->
            if (value.isBlank()) {
                Text(
                    text = if (label == "Mac IP") "192.168.1.10" else "8989",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            innerTextField()
        },
    )
}

@Composable
private fun ReadingLine(
    label: String,
    value: Float,
) {
    Text(
        text = "$label: ${"%.3f".format(Locale.US, value)}",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun rememberAccelerometerReading(
    isSampling: Boolean,
    onSample: (timestampNs: Long, reading: AccelerometerReading) -> Unit,
): AccelerometerReading {
    val context = LocalContext.current
    val sensorManager =
        remember(context) {
            context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        }
    val accelerometer =
        remember(sensorManager) {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
    val latestOnSample = rememberUpdatedState(onSample)

    var reading by remember {
        mutableStateOf(AccelerometerReading())
    }

    DisposableEffect(sensorManager, accelerometer, isSampling) {
        if (!isSampling || accelerometer == null) {
            onDispose {}
        } else {
            val listener =
                object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        val updatedReading =
                            AccelerometerReading(
                                x = event.values[0],
                                y = event.values[1],
                                z = event.values[2],
                                accuracy = event.accuracy,
                                hasReading = true,
                            )
                        reading = updatedReading
                        latestOnSample.value(event.timestamp, updatedReading)
                    }

                    override fun onAccuracyChanged(
                        sensor: Sensor?,
                        accuracy: Int,
                    ) {
                        reading = reading.copy(accuracy = accuracy)
                    }
                }

            sensorManager.registerListener(
                listener,
                accelerometer,
                SAMPLE_PERIOD_US,
            )

            onDispose {
                sensorManager.unregisterListener(listener)
            }
        }
    }

    return reading
}

@Composable
private fun rememberGyroscopeReading(isSampling: Boolean): GyroscopeReading {
    val context = LocalContext.current
    val sensorManager =
        remember(context) {
            context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        }
    val gyroscope =
        remember(sensorManager) {
            sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        }

    var reading by remember {
        mutableStateOf(GyroscopeReading())
    }

    DisposableEffect(sensorManager, gyroscope, isSampling) {
        if (!isSampling || gyroscope == null) {
            onDispose {}
        } else {
            val listener =
                object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        reading =
                            GyroscopeReading(
                                x = event.values[0],
                                y = event.values[1],
                                z = event.values[2],
                                accuracy = event.accuracy,
                                hasReading = true,
                            )
                    }

                    override fun onAccuracyChanged(
                        sensor: Sensor?,
                        accuracy: Int,
                    ) {
                        reading = reading.copy(accuracy = accuracy)
                    }
                }

            sensorManager.registerListener(
                listener,
                gyroscope,
                SAMPLE_PERIOD_US,
            )

            onDispose {
                sensorManager.unregisterListener(listener)
            }
        }
    }

    return reading
}

private fun SensorSample.toCsvLine(): String =
    String.format(
        Locale.US,
        "%d,%d,%d,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%s,%s",
        watchWallTimeMs,
        sensorEventTimeNs,
        recordingElapsedMs,
        accelX,
        accelY,
        accelZ,
        accelMagnitude,
        gyroX,
        gyroY,
        gyroZ,
        gyroMagnitude,
        accuracyLabel(accelAccuracy),
        accuracyLabel(gyroAccuracy),
    )

private fun writeCsv(
    context: Context,
    samples: List<SensorSample>,
): File {
    val outputDir = context.getExternalFilesDir(null) ?: context.filesDir
    val timestampLabel =
        SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())
    val outputFile = File(outputDir, "watch-sensors-$timestampLabel.csv")
    val csvContent =
        buildString {
            appendLine(csvHeader())
            samples.forEach { sample ->
                appendLine(sample.toCsvLine())
            }
        }
    outputFile.writeText(csvContent)
    return outputFile
}

private fun csvHeader(): String =
    "watch_wall_time_ms,sensor_event_time_ns,recording_elapsed_ms,accel_x,accel_y,accel_z,accel_magnitude,gyro_x,gyro_y,gyro_z,gyro_magnitude,accel_accuracy,gyro_accuracy"

private fun accuracyLabel(accuracy: Int): String =
    when (accuracy) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "High"
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Medium"
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "Low"
        SensorManager.SENSOR_STATUS_UNRELIABLE -> "Unreliable"
        else -> "Unknown"
    }
