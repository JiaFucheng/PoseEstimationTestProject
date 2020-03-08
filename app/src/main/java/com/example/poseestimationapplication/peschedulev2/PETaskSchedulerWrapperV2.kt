package com.example.poseestimationapplication.peschedulev2

import android.app.Activity
import android.graphics.Bitmap
import com.example.poseestimationapplication.peschedule.PETaskSchedulerMode

class PETaskSchedulerWrapperV2(mActivity: Activity) {

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
        var outputPointArrays: ArrayList<Array<FloatArray>> ?= null
        do {
            outputPointArrays = mPETaskScheduler.getOutputPointArrays(ticket)
        } while (outputPointArrays == null)

        return outputPointArrays
    }
}
