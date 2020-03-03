package com.example.poseestimationapplication.peschedule

import android.util.Log
import kotlin.collections.ArrayList

class OnDeviceEvaluator {

    private val TAG = "OnDeviceEvaluator"

    companion object {
        public val DEVICE_ID_CPU = 0
        public val DEVICE_ID_GPU = 1
        public val DEVICE_ID_CPU_MT = 2

        private val deviceTimeWndWeights = ArrayList<Float>()
    }

    private val NUM_DEVICE = 2
    private val SIZE_AVG_WINDOW = 3

    private val avgDeviceTime = floatArrayOf(0.0f, 0.0f)
    private val deviceTimeWindow = ArrayList<ArrayList<Float>>(2)
    private val deviceTimeWindowMT = ArrayList<ArrayList<Float>>(4)
    private val deviceTimeUpdatedTime = longArrayOf(1L, 1L)
    private val deviceTimeUpdatedTimeMT = longArrayOf(1L, 1L, 1L, 1L)
    private val deviceTimeGetTime = longArrayOf(0L, 0L)
    private val deviceTimeGetTimeMT = longArrayOf(0L, 0L, 0L, 0L)

    private val rwLock: Byte = 0

    // Benchmark: (18.9f, 10.02f)
    // Honor9: (36.5f, 47.8f)
    private val deviceStaticExecuteTime = floatArrayOf(18.9f, 10.02f)
    private val deviceStaticExecuteTimeMT = floatArrayOf(36.5f, 67.5f, 116.5f, 137f)

    init {
        for (i in 0 until 2)
            deviceTimeWindow.add(ArrayList())

        for (i in 0 until 4)
            deviceTimeWindowMT.add(ArrayList())

        // 初始化权重值
        val weights = listOf(1.0f/6.0f, 2.0f/6.0f, 3.0f/6.0f)
        deviceTimeWndWeights.addAll(weights)
    }

    // 显示当前的执行时间窜口
    private fun showDeviceTimeWindow() {
        Log.i(TAG, "CPU " + deviceTimeWindow[DEVICE_ID_CPU].toString()
                + " GPU " + deviceTimeWindow[DEVICE_ID_GPU].toString())
    }

    // 计算加权平均时间
    private fun computeDeviceAvgTime(deviceId: Int, numThreads: Int) {
        val timeCount = deviceTimeWindow[deviceId].size
        var sum = 0.0f
        for (i in 0 until timeCount) {
            // 根据加权平均方法计算加权平均时间
            if (deviceId == DEVICE_ID_CPU_MT)
                sum += (deviceTimeWindowMT[numThreads][i] * deviceTimeWndWeights[i])
            else
                sum += (deviceTimeWindow[deviceId][i] * deviceTimeWndWeights[i])
        }

        avgDeviceTime[deviceId] = sum
    }

    // 更新执行时间
    public fun updateDeviceExecTime(deviceId: Int, numThreads: Int, execTime: Float) {
        synchronized(rwLock) {
            if (deviceId == DEVICE_ID_CPU_MT) {
                deviceTimeWindowMT[numThreads].add(execTime)
                if (deviceTimeWindowMT[numThreads].size > SIZE_AVG_WINDOW) {
                    deviceTimeWindowMT[numThreads].removeAt(0)
                }
                deviceTimeUpdatedTimeMT[numThreads] = System.currentTimeMillis()
            } else {
                deviceTimeWindow[deviceId].add(execTime)
                if (deviceTimeWindow[deviceId].size > SIZE_AVG_WINDOW) {
                    deviceTimeWindow[deviceId].removeAt(0)
                }
                deviceTimeUpdatedTime[deviceId] = System.currentTimeMillis()
            }
        }
    }

    public fun updateDeviceExecTime(deviceId: Int, execTime: Float) {
        return updateDeviceExecTime(deviceId, 1, execTime)
    }

    // 获取预估执行时间
    public fun getDeviceEstimatedExecTime(deviceId: Int, numThreads: Int) : Float {
        return if (deviceTimeWindow[deviceId].size < SIZE_AVG_WINDOW) {
            getStaticDeviceEstimatedExecTime(deviceId, numThreads)
        } else {
            synchronized(rwLock) {
                if (deviceId == DEVICE_ID_CPU_MT) {
                    if (deviceTimeGetTimeMT[numThreads] < deviceTimeUpdatedTimeMT[numThreads]) {
                        computeDeviceAvgTime(deviceId, numThreads)
                        deviceTimeGetTimeMT[numThreads] = deviceTimeUpdatedTimeMT[numThreads]
                    }
                } else {
                    if (deviceTimeGetTime[deviceId] < deviceTimeUpdatedTime[deviceId]) {
                        //showDeviceTimeWindow()
                        computeDeviceAvgTime(deviceId, numThreads)
                        deviceTimeGetTime[deviceId] = deviceTimeUpdatedTime[deviceId]
                    }
                }

            }

            avgDeviceTime[deviceId]
        }
    }

    public fun getDeviceEstimatedExecTime(deviceId: Int): Float {
        return getDeviceEstimatedExecTime(deviceId, 1)
    }

    // 获取预测的执行时间
    public fun getStaticDeviceEstimatedExecTime(deviceId: Int, numThreads: Int) : Float {
        if (deviceId == DEVICE_ID_CPU_MT) {
            return deviceStaticExecuteTimeMT[numThreads]
        } else {
            return deviceStaticExecuteTime[deviceId]
        }
    }

    public fun getStaticDeviceEstimatedExecTime(deviceId: Int) : Float {
        return getStaticDeviceEstimatedExecTime(deviceId, 1)
    }
}