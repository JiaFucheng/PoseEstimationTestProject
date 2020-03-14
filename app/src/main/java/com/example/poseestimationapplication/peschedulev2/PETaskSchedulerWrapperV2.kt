package com.example.poseestimationapplication.peschedulev2

import android.app.Activity
import android.graphics.Bitmap
import android.util.Log
import com.example.poseestimationapplication.peschedule.PETaskSchedulerMode
import java.util.*

class PETaskSchedulerWrapperV2(mActivity: Activity) {

    private val TAG = "PETaskSchedulerWrapper"

    private val mPETaskScheduler = PETaskSchedulerV2(mActivity)
    private val inputSize = 192
    private val cpuFp = ModelParameters.CPU_INT_8
    private val useGpuModelFp16 = false
    private val useGpuFp16 = true

    init {
        mPETaskScheduler.init(inputSize, cpuFp, useGpuModelFp16, useGpuFp16)
    }

    fun close() {
        mPETaskScheduler.close()
    }

    fun run(bitmaps: ArrayList<Bitmap>): ArrayList<Array<FloatArray>>? {
        val ticket = mPETaskScheduler.scheduleAndRun(bitmaps, PETaskSchedulerMode.MODE_GREEDY)
        var outputPointArrays: ArrayList<Array<FloatArray>>?= null
        var tryCount = 0
        do {
            outputPointArrays = mPETaskScheduler.getOutputPointArrays(ticket)
            tryCount ++
            if (tryCount > 1000) {
                Log.w(TAG, "Try count > 1000, something wrong")
                break
            }
            Thread.sleep(1)
        } while (outputPointArrays == null)

        return outputPointArrays
    }
}
