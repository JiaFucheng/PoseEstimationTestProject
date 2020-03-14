package com.example.poseestimationapplication.peschedule

import android.app.Activity
import android.graphics.Bitmap
import java.util.*

class PETaskSchedulerWrapper(mActivity: Activity) {

    private val mPETaskScheduler = PETaskScheduler(mActivity)
    private val inputSize = 192
    private val numThreads = 4
    private val cpuFp = PETaskScheduler.CPU_INT_8
    private val useGpuModelFp16 = false
    private val useGpuFp16 = true
    private val scheduleMode = PETaskSchedulerMode.MODE_CPUGPU_MT_WMA

    init {
        mPETaskScheduler.init(inputSize, numThreads, cpuFp, useGpuModelFp16, useGpuFp16)
    }

    fun close() {
        mPETaskScheduler.close()
    }

    fun run(bitmaps: ArrayList<Bitmap>): ArrayList<Array<FloatArray>> {
        val ticket = mPETaskScheduler.scheduleAndRun(bitmaps, scheduleMode)
        var outputPointArrays: ArrayList<Array<FloatArray>> ?= null
        do {
            outputPointArrays = mPETaskScheduler.getOutputPointArrays(ticket)
        } while (outputPointArrays == null)

        return outputPointArrays
    }
}
