package com.google.mediapipe.examples.gesturerecognizer.fragment

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.mediapipe.examples.gesturerecognizer.GestureRecognizerHelper
import com.google.mediapipe.examples.gesturerecognizer.MainViewModel
import com.google.mediapipe.examples.gesturerecognizer.databinding.FragmentCameraBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraFragment : Fragment(), GestureRecognizerHelper.GestureRecognizerListener {

    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private lateinit var gestureRecognizerHelper: GestureRecognizerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_BACK

    private lateinit var backgroundExecutor: ExecutorService

    private val recognizedGestures = mutableListOf<String>()
    private val maxDisplayedGestures = 5

    override fun onResume() {
        super.onResume()
        backgroundExecutor.execute {
            if (gestureRecognizerHelper.isClosed()) {
                gestureRecognizerHelper.setupGestureRecognizer()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        backgroundExecutor.execute { gestureRecognizerHelper.clearGestureRecognizer() }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        backgroundExecutor = Executors.newSingleThreadExecutor()

        gestureRecognizerHelper = GestureRecognizerHelper(
            minHandDetectionConfidence = viewModel.currentMinHandDetectionConfidence,
            minHandTrackingConfidence = viewModel.currentMinHandTrackingConfidence,
            minHandPresenceConfidence = viewModel.currentMinHandPresenceConfidence,
            currentDelegate = viewModel.currentDelegate,
            context = requireContext(),
            gestureRecognizerListener = this
        )

        fragmentCameraBinding.viewFinder.post {
            setUpCamera()
        }

        setupButtons()
    }

    private fun setupButtons() {
        fragmentCameraBinding.cameraSwitchButton.setOnClickListener {
            cameraFacing = if (cameraFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            bindCameraUseCases()
        }

        fragmentCameraBinding.deleteButton.setOnClickListener {
            recognizedGestures.clear()
            fragmentCameraBinding.subtitleText.text = ""
        }

        fragmentCameraBinding.backButton.setOnClickListener {
            if (recognizedGestures.isNotEmpty()) {
                recognizedGestures.removeAt(recognizedGestures.size - 1)
                val subtitleText = recognizedGestures.joinToString(" ")
                fragmentCameraBinding.subtitleText.text = subtitleText
            }
        }

        fragmentCameraBinding.checkButton.setOnClickListener {
            val intent = Intent(requireContext(), ResultActivity::class.java)
            val txt = recognizedGestures.joinToString(" ")
            intent.putExtra("RECOGNIZED_GESTURES", txt)
            startActivityForResult(intent, 1)
        }
    }

    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector = CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also {
                it.setAnalyzer(backgroundExecutor) { image ->
                    gestureRecognizerHelper.recognizeLiveStream(
                        image,
                        cameraFacing == CameraSelector.LENS_FACING_FRONT
                    )
                }
            }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer
            )
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation = fragmentCameraBinding.viewFinder.display.rotation
    }

    override fun onResults(resultBundle: GestureRecognizerHelper.ResultBundle) {

        activity?.runOnUiThread {
            if (resultBundle.results != "none") {
                updateRecognizedGestures(resultBundle.results)

                val gestureText = "${resultBundle.results} (${String.format("%.2f", resultBundle.confidence * 100)}%)"
                fragmentCameraBinding.gestureTextView.text = gestureText
            } else {
                // Clear the gesture TextView if confidence is below threshold
                fragmentCameraBinding.gestureTextView.text = ""
            }

                // Update overlay with hand landmarks
                fragmentCameraBinding.overlay.setResults(
                    resultBundle.handLandmarkerResult,
                    "${resultBundle.results} (${String.format("%.2f", resultBundle.confidence)})",
                    resultBundle.inputImageHeight,
                    resultBundle.inputImageWidth
                )


                fragmentCameraBinding.overlay.invalidate()
        }
    }

    private fun updateRecognizedGestures(newGesture: String) {
        if (newGesture == recognizedGestures.lastOrNull() || newGesture.lowercase() == "none") return

        if (recognizedGestures.size == maxDisplayedGestures) {
            recognizedGestures.removeAt(0)
        }
        recognizedGestures.add(newGesture)

        val subtitleText = recognizedGestures.joinToString(" ")
        fragmentCameraBinding.subtitleText.text = subtitleText
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == android.app.Activity.RESULT_OK) {
            val editedText = data?.getStringExtra("EDITED_GESTURES")
            editedText?.let {
                fragmentCameraBinding.subtitleText.text = it
                recognizedGestures.clear()
                recognizedGestures.addAll(it.split(" "))
            }
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "GestureRecognizer"
    }
}
