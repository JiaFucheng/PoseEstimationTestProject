package com.example.poseestimationapplication.peschedule

import android.util.Log
import java.util.*
import kotlin.collections.ArrayList

class OnDeviceEvaluator {

    private val TAG = "OnDeviceEvaluator"

    companion object {
        const val DEVICE_ID_CPU = 0
        const val DEVICE_ID_GPU = 1
        const val DEVICE_ID_CPU_MT = 2

        private val deviceTimeWndWeights = ArrayList<Float>()
    }

    //private val NUM_DEVICE = 2
    private val SIZE_AVG_WINDOW = 3

    private val avgDeviceTime = floatArrayOf(0.0f, 0.0f)
    private val avgDeviceTimeMT = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)
    private val deviceTimeWindow = ArrayList<ArrayList<Float>>()
    private val deviceTimeWindowMT = ArrayList<ArrayList<Float>>()
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

    private fun showDeviceTimeWindowMT() {
        Log.i(TAG, "CPU $deviceTimeWindowMT")
        Log.i(TAG, "AVG CPU " + Arrays.toString(avgDeviceTimeMT))
    }

    private fun computeDeviceAvgTimeMT(numThreads: Int) {
        val timeCount = deviceTimeWindowMT[numThreads-1].size
        var sum = 0.0f
        for (i in 0 until timeCount) {
            sum += (deviceTimeWindowMT[numThreads-1][i] * deviceTimeWndWeights[i])
        }
        avgDeviceTimeMT[numThreads-1] = sum
    }

    // 计算加权平均时间
    private fun computeDeviceAvgTime(deviceId: Int, numThreads: Int) {
        if (deviceId == DEVICE_ID_CPU_MT) {
            computeDeviceAvgTimeMT(numThreads)
            return
        }

        val timeCount = deviceTimeWindow[deviceId].size
        var sum = 0.0f
        for (i in 0 until timeCount) {
            // 根据加权平均方法计算加权平均时间
            sum += (deviceTimeWindow[deviceId][i] * deviceTimeWndWeights[i])
        }

        avgDeviceTime[deviceId] = sum
    }

    // 更新执行时间
    fun updateDeviceExecTime(deviceId: Int, numThreads: Int, execTime: Float) {
        synchronized(rwLock) {
            if (deviceId == DEVICE_ID_CPU_MT) {
                deviceTimeWindowMT[numThreads-1].add(execTime)
                if (deviceTimeWindowMT[numThreads-1].size > SIZE_AVG_WINDOW) {
                    deviceTimeWindowMT[numThreads-1].removeAt(0)
                }
                deviceTimeUpdatedTimeMT[numThreads-1] = System.currentTimeMillis()
            } else {
                deviceTimeWindow[deviceId].add(execTime)
                if (deviceTimeWindow[deviceId].size > SIZE_AVG_WINDOW) {
                    deviceTimeWindow[deviceId].removeAt(0)
                }
                deviceTimeUpdatedTime[deviceId] = System.currentTimeMillis()
            }
        }
    }

    fun updateDeviceExecTime(deviceId: Int, execTime: Float) {
        return updateDeviceExecTime(deviceId, 1, execTime)
    }

    // 获取预估执行时间
    fun getDeviceEstimatedExecTime(deviceId: Int, numThreads: Int) : Float {
        if (deviceId == DEVICE_ID_CPU_MT) {
            return if (deviceTimeWindowMT[numThreads-1].size < SIZE_AVG_WINDOW) {
                getStaticDeviceEstimatedExecTime(deviceId, numThreads)
            } else {
                synchronized(rwLock) {
                    if (deviceTimeGetTimeMT[numThreads-1] < deviceTimeUpdatedTimeMT[numThreads-1]) {
                        //showDeviceTimeWindowMT()
                        computeDeviceAvgTime(deviceId, numThreads)
                        deviceTimeGetTimeMT[numThreads-1] = deviceTimeUpdatedTimeMT[numThreads-1]
                    }
                }

                avgDeviceTimeMT[numThreads-1]
            }
        } else {
            return if (deviceTimeWindow[deviceId].size < SIZE_AVG_WINDOW) {
                getStaticDeviceEstimatedExecTime(deviceId, numThreads)
            } else {
                synchronized(rwLock) {
                    if (deviceTimeGetTime[deviceId] < deviceTimeUpdatedTime[deviceId]) {
                        //showDeviceTimeWindow()
                        computeDeviceAvgTime(deviceId, numThreads)
                        deviceTimeGetTime[deviceId] = deviceTimeUpdatedTime[deviceId]
                    }
                }

                avgDeviceTime[deviceId]
            }
        }
    }

    fun getDeviceEstimatedExecTime(deviceId: Int): Float {
        return getDeviceEstimatedExecTime(deviceId, 1)
    }

    // 获取预测的执行时间
    fun getStaticDeviceEstimatedExecTime(deviceId: Int, numThreads: Int) : Float {
        return if (deviceId == DEVICE_ID_CPU_MT) {
            deviceStaticExecuteTimeMT[numThreads-1]
        } else {
            deviceStaticExecuteTime[deviceId]
        }
    }

    fun getStaticDeviceEstimatedExecTime(deviceId: Int) : Float {
        return getStaticDeviceEstimatedExecTime(deviceId, 1)
    }
}