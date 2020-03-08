package com.example.poseestimationapplication.peschedule

import android.graphics.Bitmap

interface PETaskSchedulerInterface {

    fun init(inputSize: Int, numThreads: Int,
             cpuFp: Int, useGpuModelFp16: Boolean, useGpuFp16: Boolean)

    fun scheduleAndRun(pictures: ArrayList<Bitmap>, mode: Int): Long

    fun close()

    fun isAllTasksFinished(): Boolean
    fun setTaskStartTime(time: Long)
    fun getTaskCostTime(): Long
}
