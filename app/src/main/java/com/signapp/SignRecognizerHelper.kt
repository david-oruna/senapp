/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.signapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder


class SignRecognizerHelper(
    var minHandDetectionConfidence: Float = DEFAULT_HAND_DETECTION_CONFIDENCE,
    var minHandTrackingConfidence: Float = DEFAULT_HAND_TRACKING_CONFIDENCE,
    var minHandPresenceConfidence: Float = DEFAULT_HAND_PRESENCE_CONFIDENCE,
    var currentDelegate: Int = DELEGATE_CPU,
    var runningMode: RunningMode = RunningMode.LIVE_STREAM,
    private val context: Context,
    private val gestureRecognizerListener: GestureRecognizerListener? = null
) {
    private var handLandmarker: HandLandmarker? = null
    private var interpreter: Interpreter? = null
    private val frameBuffer = FrameBuffer(30)


    init {
        setupGestureRecognizer()
    }

    fun clearGestureRecognizer() {
        handLandmarker?.close()
        handLandmarker = null
        interpreter?.close()
        interpreter = null
    }

    fun setupGestureRecognizer() {
        val baseOptionsBuilder = BaseOptions.builder()

        when (currentDelegate) {
            DELEGATE_GPU -> baseOptionsBuilder.setDelegate(Delegate.GPU)
            DELEGATE_CPU -> baseOptionsBuilder.setDelegate(Delegate.CPU)
        }

        baseOptionsBuilder.setModelAssetPath("hand_landmarker.task")


        val handLandmarkerOptions = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptionsBuilder.build())
            .setMinHandDetectionConfidence(minHandDetectionConfidence)
            .setMinTrackingConfidence(minHandTrackingConfidence)
            .setMinHandPresenceConfidence(minHandPresenceConfidence)
            .setRunningMode(runningMode)
            .setNumHands(1)

        if (runningMode == RunningMode.LIVE_STREAM) {
            handLandmarkerOptions
                .setResultListener(this::returnLivestreamResult)
        }

        try {
            handLandmarker = HandLandmarker.createFromOptions(context, handLandmarkerOptions.build())
            val model = FileUtil.loadMappedFile(context, "lstm_model.tflite")
            interpreter = Interpreter(model)
        } catch (e: IllegalStateException) {
            gestureRecognizerListener?.onError(
                "Gesture recognizer failed to initialize. See error logs for details",
                OTHER_ERROR
            )
            Log.e(
                "ERRORRECOGNIZER", "MediaPipe failed to load the task with error: " + e
                    .message
            )
        } catch (e: RuntimeException) {
            gestureRecognizerListener?.onError(
                "Gesture recognizer failed to initialize. See error logs for details",
                GPU_ERROR
            )
            Log.e(
                "ERRORRECOGNIZER", "MediaPipe failed to load the task with error: " + e
                    .message
            )
        }
    }

    fun recognizeLiveStream(imageProxy: ImageProxy, isFrontCamera: Boolean) {
        val frameTime = SystemClock.uptimeMillis()

        val bitmapBuffer = Bitmap.createBitmap(
            imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
        )
        imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
        imageProxy.close()

        val matrix = Matrix().apply {
            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            if (isFrontCamera) {
                postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
            }
        }

        val rotatedBitmap = Bitmap.createBitmap(
            bitmapBuffer,
            0,
            0,
            bitmapBuffer.width,
            bitmapBuffer.height,
            matrix,
            true
        )

        val mpImage = BitmapImageBuilder(rotatedBitmap).build()

        handLandmarker?.detectAsync(mpImage, frameTime)
    }



    private fun runInference(sequence: Array<FloatArray>): Pair<String, Float> {
        // Convert sequence to float32 and add batch dimension
        val inputBuffer = ByteBuffer.allocateDirect(4 * 1 * 30 * 42)
            .order(ByteOrder.nativeOrder())

        sequence.forEach { frame ->
            frame.forEach { inputBuffer.putFloat(it.toFloat()) } // Convert to Float32
        }

        val outputBuffer = ByteBuffer.allocateDirect(4 * GESTURES.size)
            .order(ByteOrder.nativeOrder())

        interpreter?.run(inputBuffer, outputBuffer)

        outputBuffer.rewind()
        val outputs = FloatArray(GESTURES.size)
        outputBuffer.asFloatBuffer().get(outputs)

        return interpretOutput(outputs)
    }
    private fun interpretOutput(output: FloatArray): Pair<String, Float> {
        val maxIndex = output.indices.maxByOrNull { output[it] } ?: -1
        val maxConfidence = output.maxOrNull() ?: 0f

        return if (maxIndex in GESTURES.indices) {
            Pair(GESTURES[maxIndex], maxConfidence)
        } else {
            Pair("none", maxConfidence)
        }
    }

    private fun returnLivestreamResult(result: HandLandmarkerResult, input: MPImage) {
        if (result.landmarks().isNotEmpty()) {
            val landmarks = result.landmarks()[0]
            val keypoints = FloatArray(42) // 21 landmarks * 2 (x and y)
            landmarks.forEachIndexed { index, landmark ->
                keypoints[index * 2] = landmark.x()
                keypoints[index * 2 + 1] = landmark.y()
            }

            frameBuffer.addFrame(keypoints)

            if (frameBuffer.isFull()) {
                val sequence = frameBuffer.getFrames()
                val (recognizedGesture, confidence) = runInference(sequence)
                gestureRecognizerListener?.onResults(
                    ResultBundle(
                        recognizedGesture,
                        confidence,
                        result,
                        0, // inferenceTime
                        input.height,
                        input.width
                    )
                )
            }
        }
    }


    fun isClosed(): Boolean {
        return handLandmarker == null && interpreter == null
    }

    inner class FrameBuffer(private val maxSize: Int) {
        private val buffer = ArrayDeque<FloatArray>()

        fun addFrame(frame: FloatArray) {
            if (buffer.size >= maxSize) buffer.removeFirst()
            buffer.addLast(frame)
        }

        fun getFrames(): Array<FloatArray> = buffer.toTypedArray()
        fun isFull() = buffer.size == maxSize
    }

    data class ResultBundle(
        val results: String,
        val confidence: Float,
        val handLandmarkerResult: HandLandmarkerResult,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int,

        )


    interface GestureRecognizerListener {
        fun onError(error: String, errorCode: Int = OTHER_ERROR)
        fun onResults(resultBundle: ResultBundle)
    }

    companion object {
        const val TAG = "GestureRecognizer"
        const val MP_HAND_LANDMARKER_TASK = "hand_landmarker.task"
        val GESTURES = arrayOf(
          "1","2","3","a","b","c"
        )
        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1
        const val DEFAULT_HAND_DETECTION_CONFIDENCE = 0.5F
        const val DEFAULT_HAND_TRACKING_CONFIDENCE = 0.5F
        const val DEFAULT_HAND_PRESENCE_CONFIDENCE = 0.5F
        const val OTHER_ERROR = 0
        const val GPU_ERROR = 1
    }


}


