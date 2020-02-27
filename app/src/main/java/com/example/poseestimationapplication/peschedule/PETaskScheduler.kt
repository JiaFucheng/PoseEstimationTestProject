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

class PETaskScheduler(private val activity: Activity) {

    companion object {
        public val MODE_CPU = 0
        public val MODE_GPU = 1
        public val MODE_CPUGPU = 2
        public val MODE_CPUGPU_WMA = 3

        private val FLAG_CPU_TASK_FINISH = 0
        private val FLAG_GPU_TASK_FINISH = 1
    }

    private val TAG = "PETaskScheduler"

    private val onDeviceEvaluator: OnDeviceEvaluator = OnDeviceEvaluator()

    private val cpuTasksQueue = PETasksQueue()
    private val gpuTasksQueue = PETasksQueue()

    private val cpuHeatMapsQueue = ArrayList<ArrayList<Array<Array<Array<FloatArray>>>>>()
    private val gpuHeatMapsQueue = ArrayList<ArrayList<Array<Array<Array<FloatArray>>>>>()

    private val taskFinishedFlags = ArrayList<Int>()

    private var mCPUHourGlass: ImageClassifierFloatInception ?= null
    private var mGPUHourGlass: ImageClassifierFloatInception ?= null

    private var gpuHandlerThread: HandlerThread ?= null
    private var gpuThreadHandler: Handler ?= null
    private var heatMapsHandlerThread: HandlerThread ?= null
    private var heatMapsThreadHandler: Handler ?= null

    @Volatile private var isCPUTaskRunning = false
    @Volatile private var isGPUTaskRunning = false

    private val peTaskCallback = PETaskCallback()

    private val picWidth = 192
    private val picHeight = 192

    private var curScheduleMode: Int = MODE_CPU
    private var taskId: Int = 0
    private var taskStartTime: Long = 0
    private var taskCostTime: Long = 0

    init {
        initHandlerThreads()
    }

    private fun getScheduleModeName(mode: Int) : String {
        when (mode) {
            MODE_CPU -> {
                return "CPU"
            }
            MODE_GPU -> {
                return "GPU"
            }
            MODE_CPUGPU -> {
                return "CPU-GPU"
            }
            MODE_CPUGPU_WMA -> {
                return "CPU-GPU (WMA)"
            }
        }

        return ""
    }

    public fun setTaskStartTime(time: Long) {
        taskStartTime = time
    }

    public fun getTaskCostTime(): Long {
        return taskCostTime
    }

    public fun isAllTasksFinished() : Boolean {
        return (!getIsCPURunning() && !getIsGPURunning()) &&
                (cpuTasksQueue.getQueueSize() == 0 &&
                        gpuTasksQueue.getQueueSize() == 0)
    }

    private fun setIsCPURunning(flag: Boolean) {
        isCPUTaskRunning = flag
    }

    private fun getIsCPURunning(): Boolean {
        return isCPUTaskRunning
    }

    private fun setIsGPURunning(flag: Boolean) {
        isGPUTaskRunning = flag
    }

    private fun getIsGPURunning(): Boolean {
        return isGPUTaskRunning
    }

    private fun initHandlerThreads() {
        gpuHandlerThread = HandlerThread("gpuHandlerThread")
        gpuHandlerThread?.start()
        gpuThreadHandler = Handler(gpuHandlerThread?.looper)

        heatMapsHandlerThread = HandlerThread("heatMapsHandlerThread")
        heatMapsHandlerThread?.start()
        heatMapsThreadHandler = Handler(heatMapsHandlerThread?.looper)
    }

    private fun closeHandlerThreads() {
        gpuHandlerThread?.quit()
        heatMapsHandlerThread?.quit()
    }

    private fun initTFLite(numThreads: Int, useGpuFp16: Boolean) {
        mCPUHourGlass = ImageClassifierFloatInception.create(activity, modelPath = "hourglass_model.tflite")
        mGPUHourGlass = ImageClassifierFloatInception.create(activity, modelPath = "hourglass_model.tflite")
        mCPUHourGlass?.initTFLite(numThreads, false, false)
        mGPUHourGlass?.initTFLite(-1, true, useGpuFp16)

        Log.i(TAG, "Init TFLite OK (numThreads=$numThreads, useGpuFp16=$useGpuFp16)")
    }

    private fun closeTFLite() {
        mCPUHourGlass?.close()
        mGPUHourGlass?.close()
    }

    private fun warmUpRun() {
        Log.i(TAG, "PETaskScheduler warm up run start")

        val bmpArray = BitmapLoader.loadRandomDataPictures(2, picWidth, picHeight)

        var gpuWarmUpFinished = false
        gpuThreadHandler?.post {
            //val startTime = System.currentTimeMillis()
            mGPUHourGlass?.classifyFrame(bmpArray[1])
            gpuWarmUpFinished = true
            //val costTime = System.currentTimeMillis() - startTime
            //onDeviceEvaluator.updateDeviceExecutionTime(OnDeviceEvaluator.DEVICE_ID_GPU, costTime.toFloat())
        }

        //val startTime = System.currentTimeMillis()
        mCPUHourGlass?.classifyFrame(bmpArray[0])
        //val costTime = System.currentTimeMillis() - startTime
        //onDeviceEvaluator.updateDeviceExecutionTime(OnDeviceEvaluator.DEVICE_ID_CPU, costTime.toFloat())

        while (!gpuWarmUpFinished) {
            waitTask()
        }

        Log.i(TAG, "PETaskScheduler warm up run finished")
    }

    public fun init(numThreads: Int, useGpuFp16: Boolean) {
        initTFLite(numThreads, useGpuFp16)
        warmUpRun()
    }

    public fun close() {
        closeTFLite()
        closeHandlerThreads()
        Log.i(TAG, "PETaskScheduler closed")
    }

    @Synchronized private fun processResult() {
        val heatMaps = ArrayList<Array<Array<Array<FloatArray>>>>()

        if (cpuHeatMapsQueue.size > 0) {
            heatMaps.addAll(cpuHeatMapsQueue[0])
            cpuHeatMapsQueue.removeAt(0)
        }
        if (gpuHeatMapsQueue.size > 0) {
            heatMaps.addAll(gpuHeatMapsQueue[0])
            gpuHeatMapsQueue.removeAt(0)
        }

        // Start a new thread for PETaskCallback
        heatMapsThreadHandler?.post {
            peTaskCallback.call(heatMaps)
        }

        taskCostTime = System.currentTimeMillis() - taskStartTime
        val modeName = getScheduleModeName(curScheduleMode)
        Log.i(TAG, "Task $taskId finished at $taskCostTime ms Schedule Mode $modeName")
        taskId ++
    }

    @Synchronized private fun addOrRemoveTaskFinishedFlag(flag: Int) {
        //val tag = "FlagDebug"
        var isProcessResult = false
        //synchronized(syncLockForTaskFinishedFlags) {
        //Log.d(tag, "Flag $flag come")

        if (taskFinishedFlags.size == 0) {
            //Log.d(tag, "Add flag $flag")
            taskFinishedFlags.add(flag)
            //Log.d(tag, "Flags: $taskFinishedFlags")
            return
        }

        val firstFlag = taskFinishedFlags[0]

        if (flag == FLAG_CPU_TASK_FINISH) {
            if (firstFlag == FLAG_GPU_TASK_FINISH) {
                taskFinishedFlags.removeAt(0)
                //Log.d(tag, "Remove flag $firstFlag")
                isProcessResult = true
            } else if (flag == FLAG_CPU_TASK_FINISH) {
                taskFinishedFlags.add(flag)
                //Log.d(tag, "Add flag $flag")
            }
        } else if (flag == FLAG_GPU_TASK_FINISH) {
            if (firstFlag == FLAG_CPU_TASK_FINISH) {
                taskFinishedFlags.removeAt(0)
                //Log.d(tag, "Remove flag $firstFlag")
                isProcessResult = true
            } else if (flag == FLAG_GPU_TASK_FINISH) {
                taskFinishedFlags.add(flag)
                //Log.d(tag, "Add flag $flag")
            }
        }

        //Log.d(tag, "Flags: $taskFinishedFlags")
        //}
        if (isProcessResult)
            processResult()
    }

    private fun runTaskIfNot() {
        if (!getIsGPURunning() && gpuTasksQueue.getQueueSize() > 0) {
            // Run GPU thread
            gpuThreadHandler?.post {
                //Log.d(TAG, "Run GPU thread")

                setIsGPURunning(true)

                while (gpuTasksQueue.getQueueSize() > 0) {
                    val gpuBitmaps = gpuTasksQueue.getFirstTasks()

                    val gpuHeatMaps = ArrayList<Array<Array<Array<FloatArray>>>>()

                    while (gpuBitmaps.size > 0) {
                        //Log.i(TAG, "GPU classify frame $i")
                        val startTime = System.currentTimeMillis()
                        mGPUHourGlass?.classifyFrame(gpuBitmaps[0], gpuHeatMaps)
                        val costTime = System.currentTimeMillis() - startTime
                        onDeviceEvaluator.updateDeviceExecutionTime(
                                OnDeviceEvaluator.DEVICE_ID_GPU, costTime!!.toFloat())

                        gpuBitmaps.removeAt(0)
                    }

                    gpuHeatMapsQueue.add(gpuHeatMaps)

                    gpuTasksQueue.dequeueFirstTasks()

                    if (curScheduleMode >= MODE_CPUGPU)
                        addOrRemoveTaskFinishedFlag(FLAG_GPU_TASK_FINISH)
                    else
                        processResult()
                }

                setIsGPURunning(false)
            }
        }

        if (!getIsCPURunning() && cpuTasksQueue.getQueueSize() > 0) {
            //Log.d(TAG, "Run CPU thread")

            setIsCPURunning(true)

            while (cpuTasksQueue.getQueueSize() > 0) {
                // Run CPU thread
                val cpuHeatMaps = ArrayList<Array<Array<Array<FloatArray>>>>()

                val cpuBitmaps = cpuTasksQueue.getFirstTasks()

                while (cpuBitmaps.size > 0) {
                    //Log.i(TAG, "CPU classify frame $i")
                    val startTime = System.currentTimeMillis()
                    mCPUHourGlass?.classifyFrame(cpuBitmaps[0], cpuHeatMaps)
                    val costTime = System.currentTimeMillis() - startTime
                    onDeviceEvaluator.updateDeviceExecutionTime(
                            OnDeviceEvaluator.DEVICE_ID_CPU, costTime!!.toFloat())
                    cpuBitmaps.removeAt(0)
                }

                cpuHeatMapsQueue.add(cpuHeatMaps)

                cpuTasksQueue.dequeueFirstTasks()

                if (curScheduleMode >= MODE_CPUGPU)
                    addOrRemoveTaskFinishedFlag(FLAG_CPU_TASK_FINISH)
                else
                    processResult()
            }

            setIsCPURunning(false)
        }
    }

    private fun waitTask() {
        Thread.sleep(10)
    }

    public fun scheduleAndRun(pictures: ArrayList<Bitmap>, mode: Int) {

        //Log.d(TAG, "PE tasks come")

        curScheduleMode = mode

        when (curScheduleMode) {
            MODE_CPU -> {
                scheduleOnCPU(pictures)
            }
            MODE_GPU -> {
                scheduleOnGPU(pictures)
            }
            MODE_CPUGPU -> {
                scheduleOnCPUGPU(pictures, false)
            }
            MODE_CPUGPU_WMA -> {
                scheduleOnCPUGPU(pictures, true)
            }
        }

        runTaskIfNot()
    }

    private fun scheduleOnCPUGPU(pictures: ArrayList<Bitmap>, useWMA: Boolean) {
        //val peTaskEndTime = getPETaskTimeEnd()

        val cpuTaskSize = cpuTasksQueue.getTotalTasksSize()
        val gpuTaskSize = gpuTasksQueue.getTotalTasksSize()

        var cpuTaskAvgTime = 0.0f
        var gpuTaskAvgTime = 0.0f
        if (useWMA) {
            cpuTaskAvgTime = onDeviceEvaluator.getDeviceEstimatedExecutionTime(
                                OnDeviceEvaluator.DEVICE_ID_CPU)
            gpuTaskAvgTime = onDeviceEvaluator.getDeviceEstimatedExecutionTime(
                                OnDeviceEvaluator.DEVICE_ID_GPU)
        } else {
            cpuTaskAvgTime = onDeviceEvaluator.getStaticDeviceEstimatedExecutionTime(
                                OnDeviceEvaluator.DEVICE_ID_CPU)
            gpuTaskAvgTime = onDeviceEvaluator.getStaticDeviceEstimatedExecutionTime(
                                OnDeviceEvaluator.DEVICE_ID_GPU)
        }

        //Log.d(TAG, "Avg time CPU $cpuTaskAvgTime GPU $gpuTaskAvgTime")

        var cpuTaskEndTime = cpuTaskSize * cpuTaskAvgTime
        var gpuTaskEndTime = gpuTaskSize * gpuTaskAvgTime

        val cpuQueue = ArrayList<Bitmap>()
        val gpuQueue = ArrayList<Bitmap>()

        var cpuTaskCount = 0
        var gpuTaskCount = 0

        for (i in 0 until pictures.size) {
            val putOnCPUEndTime = MathTool.max(cpuTaskEndTime + cpuTaskAvgTime, gpuTaskEndTime)
            val putOnGPUEndTime = MathTool.max(gpuTaskEndTime + gpuTaskAvgTime, cpuTaskEndTime)

            //Log.d(TAG, "Cur End [$cpuTaskEndTime,$gpuTaskEndTime] Put End [$putOnCPUEndTime,$putOnGPUEndTime]")

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

        cpuTasksQueue.enqueueTasks(cpuQueue)
        gpuTasksQueue.enqueueTasks(gpuQueue)
    }

    private fun scheduleOnCPU(pictures: ArrayList<Bitmap>) {
        val cpuTasks = ArrayList<Bitmap>()
        for (picture in pictures)
            cpuTasks.add(picture)
        cpuTasksQueue.enqueueTasks(cpuTasks)
    }

    private fun scheduleOnGPU(pictures: ArrayList<Bitmap>) {
        val gpuTasks = ArrayList<Bitmap>()
        for (picture in pictures)
            gpuTasks.add(picture)
        gpuTasksQueue.enqueueTasks(gpuTasks)
    }

}
