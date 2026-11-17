package com.example.omrscanner

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

/**
 * Application entry point — initializes OpenCV native library once,
 * before any OpenCV call is made from the camera analyzer or the grader.
 */
class OmrApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val ok = OpenCVLoader.initLocal()
        Log.i(TAG, "OpenCV ${if (ok) "loaded" else "FAILED to load"}")
    }

    companion object {
        private const val TAG = "OmrApp"
    }
}
