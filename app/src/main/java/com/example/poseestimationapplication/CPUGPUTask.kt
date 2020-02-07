package com.example.poseestimationapplication

import android.app.Activity
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.example.poseestimationapplication.tflite.ImageClassifierFloatInception
import com.example.poseestimationapplication.tool.BitmapLoader

class CPUGPUTask(activity: Activity) : Thread() {

    private val TAG: String = "CPUGPUTask"

    private val mActivity: Activity = activity

    private var mCPUHourGlass: ImageClassifierFloatInception ?= null
    private var mGPUHourGlass: ImageClassifierFloatInception ?= null

    private val gpuThreadHandler: Handler

    private var countDown = 2

    private var taskStartTime = 0L
    private var taskCostTime = 0L

    private val picWidth = 192
    private val picHeight = 192

    private var testPicNum = 5
    private var testCPUPicNum = 2
    private val testRound = 10
    private var testRoundFinished = false

    private var useGpuFp16 = false

    private val testDelayResult = arrayOfNulls<Long>(3)

    init {
        // 初始化GPU线程
        val gpuHandlerThread = HandlerThread("gpuHandlerThread")
        gpuHandlerThread.start()

        gpuThreadHandler = Handler(gpuHandlerThread.looper)
    }

    public fun setPictureNumber(num: Int) {
        testPicNum = num
    }

    public fun setCPUPictureNumber(num: Int) {
        testCPUPicNum = num
    }

    public fun setUseGpuFp16(useGpuFp16: Boolean) {
        this.useGpuFp16 = useGpuFp16
    }

    private fun initTFLite() {
        mCPUHourGlass = ImageClassifierFloatInception.create(mActivity, modelPath = "hourglass_model.tflite")
        mGPUHourGlass = ImageClassifierFloatInception.create(mActivity, modelPath = "hourglass_model.tflite")
        mCPUHourGlass?.initTFLite(false, useGpuFp16)
        mGPUHourGlass?.initTFLite(true, useGpuFp16)
    }

    private fun closeTFLite() {
        mCPUHourGlass?.close()
        mGPUHourGlass?.close()
    }

    // 热身，让TFLite模型先运行一次，完成一些初始化工作
    private fun warmUpRun() {
        Log.i(TAG, "Warm up run start")

        val bmpArray = BitmapLoader.loadRandomDataPictures(2, picWidth, picHeight)

        var gpuWarmUpFinished = false
        gpuThreadHandler.post {
            mGPUHourGlass?.classifyFrame(bmpArray[1])
            gpuWarmUpFinished = true
        }

        mCPUHourGlass?.classifyFrame(bmpArray[0])
        while (!gpuWarmUpFinished) {
            taskWait(100)
        }

        Log.i(TAG, "Warm up run finished")
    }

    // 串行测试
    private fun serialRun() {
        Log.i(TAG, "Serial run start")

        val bmpArray = BitmapLoader.loadAssetsPictures(mActivity, testPicNum)
        val cpuTaskCount = testCPUPicNum

        taskStartTime = System.currentTimeMillis()

        var startTime = System.currentTimeMillis()
        for (i in 0 until cpuTaskCount)
            mCPUHourGlass?.classifyFrame(bmpArray[i])
        var costTime = System.currentTimeMillis() - startTime
        Log.i(TAG, "CPU task time $costTime ms")

        startTime = System.currentTimeMillis()
        for (i in cpuTaskCount until testPicNum)
            mGPUHourGlass?.classifyFrame(bmpArray[i])
        costTime = System.currentTimeMillis() - startTime
        Log.i(TAG, "GPU task time $costTime ms")

        taskCostTime = System.currentTimeMillis() - taskStartTime
        Log.i(TAG, "Serial total task time $taskCostTime ms")
    }

    // 并行测试
    private fun parallelRun() {
        Log.i(TAG, "Parallel run start")

        val bmpArray = BitmapLoader.loadAssetsPictures(mActivity, testPicNum)
        val cpuTaskCount = testCPUPicNum

        // 重置CPU/GPU计数为2
        resetCountDown()

        taskStartTime = System.currentTimeMillis()

        gpuThreadHandler.post {
            // GPU跑模型
            val startTime = System.currentTimeMillis()
            for (i in cpuTaskCount until testPicNum) {
                //Log.i(TAG, "GPU classify frame $i")
                mGPUHourGlass?.classifyFrame(bmpArray[i])
            }
            val costTime = System.currentTimeMillis() - startTime
            Log.i(TAG, "GPU task time $costTime ms")
            testDelayResult[1] = costTime

            // GPU计数减1
            countDownAndProcessResult()
        }

        // CPU跑模型
        val startTime = System.currentTimeMillis()
        for (i in 0 until cpuTaskCount) {
            //Log.i(TAG, "CPU classify frame $i")
            mCPUHourGlass?.classifyFrame(bmpArray[i])
        }
        val costTime = System.currentTimeMillis() - startTime
        Log.i(TAG, "CPU task time $costTime ms")

        testDelayResult[0] = costTime

        // CPU计数减1
        countDownAndProcessResult()
    }

    private fun processResult() {
        taskCostTime = System.currentTimeMillis() - taskStartTime
        Log.i(TAG, "Parallel total task time $taskCostTime ms")

        testDelayResult[2] = taskCostTime
        /**
         * testDelayResult
         * [0]: CPU Delay
         * [1]: GPU Delay
         * [2]: Total Delay (Parallel)
         */
        Log.i(TAG, String.format("CPU %d ms GPU %d ms CPUGPU %d ms",
                testDelayResult[0], testDelayResult[1], testDelayResult[2]))

        Log.i(TAG, "Process result OK")

        //closeTFLite()
        //Log.i(TAG, "Close TFLite OK")

        // 本轮测试结束置为true
        testRoundFinished = true
    }

    override fun run() {
        super.run()

        // 初始化TFLite模型
        if (mCPUHourGlass == null || mGPUHourGlass == null) {
            initTFLite()
            Log.i(TAG, "Init TFLite OK")
        }

        //taskWait(5000)
        warmUpRun()  // 预热运行

        // 测试多次
        for (r in 0 until testRound) {
            testRoundFinished = false

            //taskWait(1000)
            //serialRun()  // 串行测试

            taskWait(1000)  // 每轮开始前等待1秒
            parallelRun()  // 并行测试

            // 等待测试结束
            while (!testRoundFinished)
                taskWait(1000)
        }

        // 关闭TFLite模型
        closeTFLite()
        Log.i(TAG, "Close TFLite OK")
    }

    private fun resetCountDown() {
        countDown = 2
    }

    private fun countDownAndProcessResult() {
        if ((-- countDown) == 0) {
            processResult()
        }
    }

    private fun waitCountDown() {
        if (countDown > 0) {
            taskWait(100)
        }
    }

    private fun taskWait(mills: Long) {
        try {
            sleep(mills)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }
}
