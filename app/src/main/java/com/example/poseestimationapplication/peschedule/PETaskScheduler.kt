package com.example.poseestimationapplication.peschedule

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.example.poseestimationapplication.tflite.ImageClassifierFloatInception
import com.example.poseestimationapplication.tool.BitmapLoader
import com.example.poseestimationapplication.tool.MathTool
import java.util.*
import kotlin.collections.ArrayList

class PETaskScheduler(activity: Activity) {

    private val TAG = "PETaskScheduler"

    private val activity: Activity = activity

    private val onDeviceEvaluator : OnDeviceEvaluator = OnDeviceEvaluator()

    private val cpuTaskQueues : ArrayList<ArrayList<Bitmap>> = ArrayList()
    private val gpuTaskQueues : ArrayList<ArrayList<Bitmap>> = ArrayList()

    private val cpuHeatMaps = ArrayList<Array<Array<Array<FloatArray>>>>()
    private val gpuHeatMaps = ArrayList<Array<Array<Array<FloatArray>>>>()

    private var mCPUHourGlass : ImageClassifierFloatInception ?= null
    private var mGPUHourGlass : ImageClassifierFloatInception ?= null

    private var gpuThreadHandler : Handler ?= null

    private var isTaskRunning = false
    private var countDown = 0
    private var isInitFinished = false
    private var isProcessFinished = true

    private val peTaskCallback : PETaskCallback = PETaskCallback()

    private var useGpuFp16 = false

    private val picWidth = 192
    private val picHeight = 192

    init {
        val gpuHandlerThread = HandlerThread("gpuHandlerThread")
        gpuHandlerThread.start()

        gpuThreadHandler = Handler(gpuHandlerThread.looper)
    }

    private fun initTFLite() {
        mCPUHourGlass = ImageClassifierFloatInception.create(activity, modelPath = "hourglass_model.tflite")
        mGPUHourGlass = ImageClassifierFloatInception.create(activity, modelPath = "hourglass_model.tflite")
        mCPUHourGlass?.initTFLite(false, useGpuFp16)
        mGPUHourGlass?.initTFLite(true, useGpuFp16)

        Log.i(TAG, "Init TFLite OK")
    }

    private fun closeTFLite() {
        mCPUHourGlass?.close()
        mGPUHourGlass?.close()
    }

    private fun warmUpRun() {
        Log.i(TAG, "Warm up run start")

        val bmpArray = BitmapLoader.loadRandomDataPictures(2, picWidth, picHeight)

        var gpuWarmUpFinished = false
        gpuThreadHandler?.post {
            val startTime = System.currentTimeMillis()
            mGPUHourGlass?.classifyFrame(bmpArray[1])
            gpuWarmUpFinished = true
            val costTime = System.currentTimeMillis() - startTime
            //onDeviceEvaluator.updateDeviceTime(OnDeviceEvaluator.DEVICE_ID_GPU, costTime.toFloat())
        }

        val startTime = System.currentTimeMillis()
        mCPUHourGlass?.classifyFrame(bmpArray[0])
        val costTime = System.currentTimeMillis() - startTime
        //onDeviceEvaluator.updateDeviceTime(OnDeviceEvaluator.DEVICE_ID_CPU, costTime.toFloat())

        while (!gpuWarmUpFinished) {
            waitTask()
        }

        Log.i(TAG, "Warm up run finished")
    }

    private fun init() {
        initTFLite()
        //warmUpRun()

        isInitFinished = true
    }

    public fun close() {
        closeTFLite()
    }

    private fun getCPUDeviceEndTime() : Float {
        var cpuTaskSize = 0

        for (queue in cpuTaskQueues) {
            cpuTaskSize += queue.size
        }

        return cpuTaskSize * onDeviceEvaluator.getDeviceTime(OnDeviceEvaluator.DEVICE_ID_CPU)
    }

    private fun getGPUDeviceEndTime() : Float {
        var taskSize = 0

        for (queue in cpuTaskQueues) {
            taskSize += queue.size
        }

        return taskSize * onDeviceEvaluator.getDeviceTime(OnDeviceEvaluator.DEVICE_ID_GPU)
    }

    private fun resetCountDown() {
        countDown = 2
    }

    private fun processResult() {
        val heatMaps = ArrayList<Array<Array<Array<FloatArray>>>>()
        heatMaps.addAll(cpuHeatMaps)
        heatMaps.addAll(gpuHeatMaps)

        // Start a new thread for PETaskCallback
        Thread {
            peTaskCallback.call(heatMaps)
        }.start()

        cpuTaskQueues.removeAt(0)
        gpuTaskQueues.removeAt(0)

        isProcessFinished = true
    }

    private fun countDownAndProcessResult() {
        if ((-- countDown) == 0) {
            processResult()
        }
    }

    private fun runTask() {

        isTaskRunning = true

        while (cpuTaskQueues.size > 0 || gpuTaskQueues.size > 0) {
            resetCountDown()

            isProcessFinished = false

            // Run GPU thread
            gpuThreadHandler?.post {
                val gpuBitmaps = gpuTaskQueues[0]


                while (gpuBitmaps.size > 0) {
                    //Log.i(TAG, "GPU classify frame $i")
                    val startTime = System.currentTimeMillis()
                    mGPUHourGlass?.classifyFrame(gpuBitmaps[0], gpuHeatMaps)
                    val costTime = System.currentTimeMillis() - startTime
                    //onDeviceEvaluator.updateDeviceTime(OnDeviceEvaluator.DEVICE_ID_GPU,
                    //                                   costTime.toFloat())

                    gpuBitmaps.removeAt(0)
                }

                countDownAndProcessResult()
            }

            // Run CPU thread
            val cpuBitmaps = cpuTaskQueues[0]

            while (cpuBitmaps.size > 0) {
                //Log.i(TAG, "CPU classify frame $i")
                val startTime = System.currentTimeMillis()
                mCPUHourGlass?.classifyFrame(cpuBitmaps[0], cpuHeatMaps)
                val costTime = System.currentTimeMillis() - startTime
                //onDeviceEvaluator.updateDeviceTime(OnDeviceEvaluator.DEVICE_ID_CPU,
                //                                   costTime.toFloat())
                cpuBitmaps.removeAt(0)
            }

            countDownAndProcessResult()

            while (!isProcessFinished)
                waitTask()
        }

        isTaskRunning = false
    }

    private fun waitTask() {
        Thread.sleep(1)
    }

    public fun scheduleAndRun(pictures : ArrayList<Bitmap>) {
        if (!isInitFinished)
            init()

        //val peTaskEndTime = getPETaskTimeEnd()

        var cpuTaskEndTime = getCPUDeviceEndTime()
        var gpuTaskEndTime = getGPUDeviceEndTime()

        val cpuTaskAvgTime = onDeviceEvaluator.getDeviceTime(OnDeviceEvaluator.DEVICE_ID_CPU)
        val gpuTaskAvgTime = onDeviceEvaluator.getDeviceTime(OnDeviceEvaluator.DEVICE_ID_GPU)

        Log.d(TAG, "Avg time CPU $cpuTaskAvgTime GPU $gpuTaskAvgTime")

        val cpuQueue = ArrayList<Bitmap>()
        val gpuQueue = ArrayList<Bitmap>()

        var cpuTaskCount = 0
        var gpuTaskCount = 0

        for (i in 0 until pictures.size) {
            val putOnCPUEndTime = MathTool.max(cpuTaskEndTime + cpuTaskAvgTime, gpuTaskEndTime)
            val putOnGPUEndTime = MathTool.max(gpuTaskEndTime + gpuTaskAvgTime, cpuTaskEndTime)

            if (putOnCPUEndTime < putOnGPUEndTime) {
                cpuTaskCount ++
                cpuTaskEndTime += cpuTaskAvgTime
            } else {
                gpuTaskCount ++
                gpuTaskEndTime += gpuTaskAvgTime
            }
        }

        Log.d(TAG, String.format(
                Locale.CHINA, "Schedule CPU %d GPU %d tasks",
                cpuTaskCount, gpuTaskCount))

        for (i in 0 until cpuTaskCount) {
            cpuQueue.add(pictures[i])
        }
        for (i in cpuTaskCount until pictures.size) {
            gpuQueue.add(pictures[i])
        }

        cpuTaskQueues.add(cpuQueue)
        gpuTaskQueues.add(gpuQueue)

        if (!isTaskRunning) {
            runTask()
        }
    }

}
