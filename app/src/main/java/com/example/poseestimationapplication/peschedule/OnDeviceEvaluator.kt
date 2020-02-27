package com.example.poseestimationapplication.peschedule

import android.util.Log
import kotlin.collections.ArrayList

class OnDeviceEvaluator {

    private val TAG = "OnDeviceEvaluator"

    companion object {
        public val DEVICE_ID_CPU = 0
        public val DEVICE_ID_GPU = 1

        private val deviceTimeWndWeights = ArrayList<Float>()
    }

    private val NUM_DEVICE = 2
    private val SIZE_AVG_WINDOW = 3

    private val avgDeviceTime = ArrayList<Float>()
    private val deviceTimeWindow = ArrayList<ArrayList<Float>>()
    private val deviceTimeUpdatedTime = ArrayList<Long>()
    private val deviceTimeGetTime = ArrayList<Long>()

    private val rwLock: Byte = 0

    // Benchmark: (18.9f, 10.02f)
    // Honor9: (36.5f, 47.8f)
    private val deviceStaticExecuteTime = floatArrayOf(18.9f, 10.02f)

    init {
        deviceTimeWindow.add(ArrayList<Float>())
        deviceTimeWindow.add(ArrayList<Float>())

        // 初始化权重值
        val weights = listOf(1.0f/6.0f, 2.0f/6.0f, 3.0f/6.0f)
        deviceTimeWndWeights.addAll(weights)

        avgDeviceTime.add(0.0f)
        avgDeviceTime.add(0.0f)

        deviceTimeUpdatedTime.add(1)
        deviceTimeUpdatedTime.add(1)

        deviceTimeGetTime.add(0)
        deviceTimeGetTime.add(0)
    }

    // 显示当前的执行时间窜口
    private fun showDeviceTimeWindow() {
        Log.i(TAG, "CPU " + deviceTimeWindow[DEVICE_ID_CPU].toString()
                + " GPU " + deviceTimeWindow[DEVICE_ID_GPU].toString())
    }

    // 计算加权平均时间
    private fun computeDeviceAvgTime(deviceId: Int) {
        val timeCount = deviceTimeWindow[deviceId].size
        var sum = 0.0f
        for (i in 0 until timeCount) {
            // 根据加权平均方法计算加权平均时间
            sum += (deviceTimeWindow[deviceId][i] * deviceTimeWndWeights[i])
        }

        avgDeviceTime[deviceId] = sum
    }

    // 更新执行时间
    public fun updateDeviceExecutionTime(deviceId : Int, execTime : Float) {
        synchronized(rwLock) {
            deviceTimeWindow[deviceId].add(execTime)
            if (deviceTimeWindow[deviceId].size > SIZE_AVG_WINDOW) {
                deviceTimeWindow[deviceId].removeAt(0)
            }
            deviceTimeUpdatedTime[deviceId] = System.currentTimeMillis()
        }
    }

    // 获取预估执行时间
    public fun getDeviceEstimatedExecutionTime(deviceId: Int) : Float {
        return if (deviceTimeWindow[deviceId].size < SIZE_AVG_WINDOW) {
            getStaticDeviceEstimatedExecutionTime(deviceId)
        } else {
            synchronized(rwLock) {
                if (deviceTimeGetTime[deviceId] < deviceTimeUpdatedTime[deviceId]) {
                    //showDeviceTimeWindow()
                    computeDeviceAvgTime(deviceId)
                    deviceTimeGetTime[deviceId] = deviceTimeUpdatedTime[deviceId]
                }
            }

            avgDeviceTime[deviceId]
        }
    }

    // 获取预测的执行时间
    public fun getStaticDeviceEstimatedExecutionTime(deviceId: Int) : Float {
        return deviceStaticExecuteTime[deviceId]
    }

}