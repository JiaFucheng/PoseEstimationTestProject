package com.example.poseestimationapplication

import android.app.Activity
import com.example.poseestimationapplication.peschedule.PETaskSchedulerMode
import com.example.poseestimationapplication.peschedulev2.ModelParameters

class PETestTaskInBackground(private val activity: Activity) {
    private fun test() {
        val rounds = 10
        val frames = 20
        val frameInterval = 25
        val picNum = 3

        val numThreads = 4
        val cpuFp = ModelParameters.CPU_INT_8
        val useGpuModelFp16 = false
        val useGpuFp16 = true

        // Test 0: CPU
        var mode = PETaskSchedulerMode.MODE_CPU
        PETestTask(activity).testSyncMode(
                rounds, frames, frameInterval, picNum,
                mode, numThreads, cpuFp, useGpuModelFp16, useGpuFp16)

        // Test 1: GPU
        mode = PETaskSchedulerMode.MODE_GPU
        PETestTask(activity).testSyncMode(
                rounds, frames, frameInterval, picNum,
                mode, numThreads, cpuFp, useGpuModelFp16, useGpuFp16)

        // Test 2: CPU-GPU
        mode = PETaskSchedulerMode.MODE_CPUGPU
        PETestTask(activity).testSyncMode(
                rounds, frames, frameInterval, picNum,
                mode, numThreads, cpuFp, useGpuModelFp16, useGpuFp16)

        // Test 3: CPU-GPU-GREEDY
        mode = PETaskSchedulerMode.MODE_GREEDY
        PETestTask(activity).testSyncMode(
                rounds, frames, frameInterval, picNum,
                mode, numThreads, cpuFp, useGpuModelFp16, useGpuFp16)
    }

    fun startTest() {
        Thread {
            test()
        }.start()
    }
}
