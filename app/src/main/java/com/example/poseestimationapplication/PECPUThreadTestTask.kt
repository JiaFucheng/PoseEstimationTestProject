package com.example.poseestimationapplication

import android.app.Activity
import android.graphics.Bitmap
import android.util.Log
import com.example.poseestimationapplication.tflite.ImageClassifierFloatInception
import com.example.poseestimationapplication.tool.BitmapLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PECPUThreadTestTask(private val activity: Activity) {

    private val TAG = "PECPUThreadTestTask"

    private var threadPool: ExecutorService ?= null
    private var taskCountPerTFLite = 0
    private var numParallelThreads = 0

    private var cpuPEModels = ArrayList<ImageClassifierFloatInception>()

    public fun startTask1() {
        Thread {
            task1()
        }.start()
    }

    public fun startTask2() {
        Thread {
            task2()
        }.start()
    }

    private fun task1() {
        val testParams = Array(8){IntArray(3)}
        var i = 0
        testParams[i] = intArrayOf(1,1,1); i ++
        testParams[i] = intArrayOf(1,2,1); i ++
        testParams[i] = intArrayOf(1,3,1); i ++
        testParams[i] = intArrayOf(1,4,1); i ++
        testParams[i] = intArrayOf(1,5,1); i ++
        testParams[i] = intArrayOf(1,6,1); i ++
        testParams[i] = intArrayOf(1,7,1); i ++
        testParams[i] = intArrayOf(1,8,1)

        for (param in testParams) {
            test(param[0], param[1], param[2])
        }
    }

    private fun task2() {
        val testParams = Array(11){IntArray(3)}
        var i = 0
        testParams[i] = intArrayOf(2,4,1); i ++
        testParams[i] = intArrayOf(2,2,2); i ++
        testParams[i] = intArrayOf(2,1,2); i ++
        testParams[i] = intArrayOf(2,4,2); i ++
        testParams[i] = intArrayOf(3,4,1); i ++
        testParams[i] = intArrayOf(3,1,3); i ++
        testParams[i] = intArrayOf(4,4,1); i ++
        testParams[i] = intArrayOf(4,2,2); i ++
        testParams[i] = intArrayOf(4,1,4); i ++
        testParams[i] = intArrayOf(5,4,1); i ++
        testParams[i] = intArrayOf(5,1,5);

        for (param in testParams) {
            test(param[0], param[1], param[2])
        }
    }

    private fun test(taskCount: Int, tfliteNumThreads: Int, tfliteCount: Int) {
        if (taskCount % tfliteCount != 0) {
            Log.e(TAG, "Illegal tflite count")
            return
        }

        taskCountPerTFLite = taskCount / tfliteCount
        numParallelThreads = tfliteCount

        Log.d(TAG, "TaskCount $taskCount")
        Log.d(TAG, "TFLiteNumThreads $tfliteNumThreads")
        Log.d(TAG, "TFLiteCount $tfliteCount")
        Log.d(TAG, "TaskCountPerTFLite $taskCountPerTFLite")
        Log.d(TAG, "NumParallelThreads $numParallelThreads")

        if (numParallelThreads > 1)
            threadPool = Executors.newFixedThreadPool(numParallelThreads - 1)

        // Init TFLite
        initPEModels(tfliteCount, tfliteNumThreads)

        // Warm up
        runTFLite(BitmapLoader.loadAssetsPictures(activity, taskCount))

        // Run
        var timeSum = 0L
        val costTimeArray = ArrayList<Long>()
        val round = 10
        for (i in 0 until round) {
            val startTime = System.currentTimeMillis()
            runTFLite(BitmapLoader.loadAssetsPictures(activity, taskCount))
            val costTime = System.currentTimeMillis() - startTime
            //Log.d(TAG, "Total time $costTime")
            timeSum += costTime
            costTimeArray.add(costTime)
        }
        val avgTime = timeSum * 1.0f / round
        Log.d(TAG, "Cost time $costTimeArray")
        Log.d(TAG, "Avg time $avgTime")

        if (threadPool != null)
            threadPool?.shutdown()
        closePEModels()
    }

    private fun initPEModels(tfliteCount: Int, numThreads: Int) {
        for (i in 0 until tfliteCount) {
            val model = ImageClassifierFloatInception.create(
                            activity, modelPath = "hourglass_model.tflite")
            model.initTFLite(numThreads, false, false)
            cpuPEModels.add(model)
        }
    }

    private fun closePEModels() {
        for (model in cpuPEModels) {
            model.close()
        }
        cpuPEModels.clear()
    }

    private fun runTFLite(bitmaps: ArrayList<Bitmap>) {
        var countDown = numParallelThreads

        if (numParallelThreads > 1) {
            for (i in 1 until numParallelThreads) {
                // Run in thread pool
                threadPool?.submit {
                    for (j in 0 until taskCountPerTFLite) {
                        cpuPEModels[i].classifyFrame(bitmaps[i * taskCountPerTFLite + j])
                    }

                    synchronized(this) {
                        countDown --
                    }
                    //Log.d(TAG, "CountDown $countDown")
                }
            }
        }

        for (i in 0 until taskCountPerTFLite) {
            cpuPEModels[0].classifyFrame(bitmaps[i])
        }

        synchronized(this) {
            countDown--
        }
        //Log.d(TAG, "CountDown $countDown")

        while (countDown > 0) {
            Thread.sleep(1)
        }
    }
}
