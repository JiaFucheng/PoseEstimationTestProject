package com.example.poseestimationapplication.peschedulev2

import android.graphics.Bitmap
import android.util.Log

class PETaskWrapper(private val bitmaps: ArrayList<Bitmap>) {

    companion object {
        const val RESULT_FAILED = -1
        const val RESULT_NOT_FINISHED = 0
        const val RESULT_FINISHED = 1
    }

    private val TAG = "PETaskWrapper"

    private var gotTaskCount: Int = 0
    private var finishedTaskCount: Int = 0
    private var pointArrays: Array<Array<FloatArray>>
                        = Array(bitmaps.size){ Array(2){ FloatArray(14)} }
    private val taskCountLock: Byte = 0
    private val setPointArraysLock: Byte = 0

    fun getBitmap(index: Int): Bitmap {
        return bitmaps[index]
    }

    fun getAvailableTaskCount(): Int {
        synchronized(taskCountLock) {
            return (bitmaps.size - gotTaskCount)
        }
    }

    fun getAvailableTaskIndex(): Int {
        synchronized(taskCountLock) {
            return if (gotTaskCount < bitmaps.size) {
                val i = gotTaskCount
                gotTaskCount++
                i
            } else {
                -1
            }
        }
    }

    fun setPointArray(index: Int, array: Array<FloatArray>): Int {
        synchronized(setPointArraysLock) {
            return if (index < pointArrays.size) {
                pointArrays[index] = array
                finishedTaskCount ++

                when {
                    (finishedTaskCount == bitmaps.size) -> {
                        Log.i(TAG, "All bitmaps in PE task wrapper have finished (size=${bitmaps.size})")
                        RESULT_FINISHED
                    }
                    (finishedTaskCount < bitmaps.size) -> RESULT_NOT_FINISHED
                    else -> {
                        Log.e(TAG, "Wrong bitmaps finish status (finishedCount>bitmapCount)")
                        RESULT_FAILED
                    }
                }
            } else {
                Log.e(TAG, "Wrong set point array (index>bitmapCount)")
                RESULT_FAILED
            }
        }
    }

    fun getPointArrays(): ArrayList<Array<FloatArray>>? {
        return if (finishedTaskCount == bitmaps.size) {
            // Arary to ArrayList
            val outputPointArrays = ArrayList<Array<FloatArray>>()
            outputPointArrays.addAll(pointArrays)
            outputPointArrays
        } else {
            null
        }
    }

    fun checkFinished(): Boolean {
        val result = finishedTaskCount == bitmaps.size
        if (result == false)
            Log.i(TAG, "Unfinished task (cur $finishedTaskCount total ${bitmaps.size})")
        return result
    }
}
