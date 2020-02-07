package com.example.poseestimationapplication.peschedule

class OnDeviceEvaluator {

    companion object {
        public val DEVICE_ID_CPU = 0
        public val DEVICE_ID_GPU = 1
    }

    private val NUM_DEVICE = 2
    private val SIZE_AVG_WINDOW = 8

    private val avgDeviceTime = ArrayList<Float>()
    private val deviceTimeWindow = ArrayList<ArrayList<Float>>()
    private val deviceTimeUpdatedTime = ArrayList<Long>()
    private val deviceTimeGetTime = ArrayList<Long>()

    init {
        deviceTimeWindow.add(ArrayList<Float>())
        deviceTimeWindow.add(ArrayList<Float>())

        avgDeviceTime.add(0.0f)
        avgDeviceTime.add(0.0f)

        deviceTimeUpdatedTime.add(0)
        deviceTimeUpdatedTime.add(0)

        deviceTimeGetTime.add(0)
        deviceTimeGetTime.add(0)
    }

    private fun computeDeviceAvgTime(deviceId: Int) {
        val timeCount = deviceTimeWindow[deviceId].size
        var sum = 0.0f
        for (i in 0 until (timeCount - 1)) {
            sum += deviceTimeWindow[deviceId][i]
        }

        avgDeviceTime[deviceId] = sum / timeCount
    }

    public fun updateDeviceTime(deviceId : Int, execTime : Float) {
        deviceTimeWindow[deviceId].add(execTime)
        if (deviceTimeWindow[deviceId].size > SIZE_AVG_WINDOW) {
            deviceTimeWindow[deviceId].removeAt(0)
        }
        deviceTimeUpdatedTime[deviceId] = System.currentTimeMillis()
    }

    public fun getAvgDeviceTime(deviceId: Int) : Float {
        if (deviceTimeGetTime[deviceId] < deviceTimeUpdatedTime[deviceId]) {
            computeDeviceAvgTime(deviceId)
            deviceTimeGetTime[deviceId] = deviceTimeUpdatedTime[deviceId]
        }
        return avgDeviceTime[deviceId]
    }

    public fun getDeviceTime(deviceId: Int) : Float {
        if (deviceId == DEVICE_ID_CPU) {
            return 18.9f
        } else if (deviceId == DEVICE_ID_GPU) {
            return 10.02f
        } else {
            return 0.0f
        }
    }

}