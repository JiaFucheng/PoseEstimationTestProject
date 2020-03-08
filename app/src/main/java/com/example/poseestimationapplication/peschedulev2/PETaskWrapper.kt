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

    fun getBitmap(index: Int): Bitmap? {
        return if (index < bitmaps.size) {
            bitmaps[index]
        } else {
            null
        }
    }

    @Synchronized fun getAvailableTaskCount(): Int {
        return (bitmaps.size - gotTaskCount)
    }

    @Synchronized fun getAvailableTaskIndex(): Int {
        return if (gotTaskCount < bitmaps.size) {
            val i = gotTaskCount
            gotTaskCount ++
            i
        } else {
            -1
        }
    }

    @Synchronized fun setPointArray(index: Int, array: Array<FloatArray>): Int {
        return if (index < pointArrays.size) {
            pointArrays[index] = array
            finishedTaskCount ++

            if (finishedTaskCount == bitmaps.size) {
                Log.i(TAG, "All bitmaps in PE task wrapper have finished (size=${bitmaps.size})")
                RESULT_FINISHED
            } else if (finishedTaskCount < bitmaps.size) {
                RESULT_NOT_FINISHED
            } else {
                RESULT_FAILED
            }
        } else {
            RESULT_FAILED
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
}
