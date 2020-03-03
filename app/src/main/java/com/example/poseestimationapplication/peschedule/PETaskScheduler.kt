package com.example.poseestimationapplication.peschedule

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.example.poseestimationapplication.tflite.ImageClassifierFloatInception
import com.example.poseestimationapplication.tool.BitmapLoader
import com.example.poseestimationapplication.tool.MathTool
import java.lang.Thread.sleep
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.collections.ArrayList

class PETaskScheduler(private val activity: Activity) {

    companion object {
        const val MODE_CPU = 0
        const val MODE_GPU = 1
        const val MODE_CPUGPU = 2
        const val MODE_CPUGPU_WMA = 3

        private const val FLAG_CPU_TASK_FINISH = 0
        private const val FLAG_GPU_TASK_FINISH = 1
    }

    private val TAG = "PETaskScheduler"

    private val onDeviceEvaluator: OnDeviceEvaluator = OnDeviceEvaluator()

    private val cpuTasksQueue = PETasksQueue()
    private val gpuTasksQueue = PETasksQueue()

    private val cpuPointArrayQueue = PointArraysQueue()
    private val gpuPointArrayQueue = PointArraysQueue()
    private val outputPointArraysQueue = PointArraysQueue()
    private val outputTicketQueue = OutputTicketQueue()

    private val taskFinishedFlags = ArrayList<Int>()

    private var mCPUHourGlass: ImageClassifierFloatInception ?= null
    private var mGPUHourGlass: ImageClassifierFloatInception ?= null
    private var mCPUHourGlassT2 = ArrayList<ImageClassifierFloatInception>()
    private var mCPUHourGlassMT = ArrayList<ArrayList<ImageClassifierFloatInception>>()

    private var cpuHandlerThread: HandlerThread ?= null
    private var cpuThreadHandler: Handler ?= null
    private var gpuHandlerThread: HandlerThread ?= null
    private var gpuThreadHandler: Handler ?= null
    private var heatMapsHandlerThread: HandlerThread ?= null
    private var heatMapsThreadHandler: Handler ?= null

    private var threadPool: ExecutorService = Executors.newFixedThreadPool(3)

    @Volatile private var isCPUTaskRunning = false
    @Volatile private var isGPUTaskRunning = false

    //private val peTaskCallback = PETaskCallback()

    private var picWidth = 192
    private var picHeight = 192

    private var curScheduleMode: Int = MODE_CPU
    private var taskId: Int = 0
    private var taskStartTime: Long = System.currentTimeMillis()
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

    fun setTaskStartTime(time: Long) {
        taskStartTime = time
    }

    fun getTaskCostTime(): Long {
        return taskCostTime
    }

    fun isAllTasksFinished() : Boolean {
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

    fun getOutputPointArrays(ticket: Long): ArrayList<Array<FloatArray>>? {
        return if (ticket == outputTicketQueue.getFirstTicket()) {
            val output = outputPointArraysQueue.dequeue()
            if (output != null)
                outputTicketQueue.dequeueFirstTicket()
            output
        } else {
            null
        }
    }

    private fun initHandlerThreads() {
        cpuHandlerThread = HandlerThread("cpuHandlerThread")
        cpuHandlerThread?.start()
        cpuThreadHandler = Handler(cpuHandlerThread?.looper)

        gpuHandlerThread = HandlerThread("gpuHandlerThread")
        gpuHandlerThread?.start()
        gpuThreadHandler = Handler(gpuHandlerThread?.looper)

        heatMapsHandlerThread = HandlerThread("heatMapsHandlerThread")
        heatMapsHandlerThread?.start()
        heatMapsThreadHandler = Handler(heatMapsHandlerThread?.looper)
    }

    private fun closeHandlerThreads() {
        threadPool.shutdown()
        cpuHandlerThread?.quit()
        gpuHandlerThread?.quit()
        heatMapsHandlerThread?.quit()
    }

    private fun initTFLite(numThreads: Int, useCpuFp8: Boolean, useCpuFp16: Boolean,
                           useGpuModelFp16: Boolean, useGpuFp16: Boolean) {
        val cpuModelName = when {
            useCpuFp8  -> "hourglass_model_fp8.tflite"
            useCpuFp16 -> "hourglass_model_fp16.tflite"
            else       -> "hourglass_model.tflite"
        }
        // GPU may use these model
        // "hourglass_model.tflite"
        // "hourglass_model_fp16.tflite"
        // "hourglass_model_fp16_02.tflite"
        val gpuModelName = if (useGpuModelFp16) "hourglass_model_fp16_02.tflite"
                           else                 "hourglass_model.tflite"

        val numBytesPerChannel = if (useCpuFp8) 1
                                 else           4

        mCPUHourGlass = ImageClassifierFloatInception.create(
                activity, imageSizeX = picWidth, imageSizeY = picHeight,
                modelPath = cpuModelName, numBytesPerChannel = numBytesPerChannel)
        mGPUHourGlass = ImageClassifierFloatInception.create(
                activity, imageSizeX = picWidth, imageSizeY = picHeight, modelPath = gpuModelName)
        mCPUHourGlass?.initTFLite(numThreads, useGPU = false, useGpuFp16 = false)
        mGPUHourGlass?.initTFLite(-1, true, useGpuFp16)

        // Init mCPUHourGlassMT
        // [0]: 0 models
        // [1]: 2 models, 2 threads per model
        // [2]: 0 models
        // [3]: 4 models, 1 thread per model
        for (i in 0 until 4)
            mCPUHourGlassMT.add(ArrayList())

        for (i in 0 until 2) {
            mCPUHourGlassMT[1].add(ImageClassifierFloatInception.create(
                    activity, imageSizeX = picWidth, imageSizeY = picHeight,
                    modelPath = cpuModelName, numBytesPerChannel = numBytesPerChannel))
            mCPUHourGlassMT[1][i].initTFLite(2, useGPU = false, useGpuFp16 = false)
        }

        for (i in 0 until 4) {
            mCPUHourGlassMT[3].add(ImageClassifierFloatInception.create(
                    activity, imageSizeX = picWidth, imageSizeY = picHeight,
                    modelPath = cpuModelName, numBytesPerChannel = numBytesPerChannel))
            mCPUHourGlassMT[3][i].initTFLite(1, useGPU = false, useGpuFp16 = false)
        }

        Log.i(TAG, "Init TFLite OK" +
                "(numThreads=$numThreads, useCpuFp8=$useCpuFp8, useCpuFp16=$useCpuFp16," +
                " useGpuModelFp16=$useGpuModelFp16, useGpuFp16=$useGpuFp16)")
    }

    private fun closeTFLite() {
        mCPUHourGlass?.close()
        mGPUHourGlass?.close()
        for (model in mCPUHourGlassT2) {
            model.close()
        }
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
            //onDeviceEvaluator.updateDeviceExecTime(OnDeviceEvaluator.DEVICE_ID_GPU, costTime.toFloat())
        }

        //val startTime = System.currentTimeMillis()
        mCPUHourGlass?.classifyFrame(bmpArray[0])
        //val costTime = System.currentTimeMillis() - startTime
        //onDeviceEvaluator.updateDeviceExecTime(OnDeviceEvaluator.DEVICE_ID_CPU, costTime.toFloat())

        while (!gpuWarmUpFinished) {
            waitTask()
        }

        Log.i(TAG, "PETaskScheduler warm up run finished")
    }

    fun init(inputSize: Int, numThreads: Int,
                    useCpuFp8: Boolean, useCpuFp16: Boolean, useGpuModelFp16: Boolean, useGpuFp16: Boolean) {
        this.picWidth = inputSize
        this.picHeight = inputSize
        initTFLite(numThreads, useCpuFp8, useCpuFp16, useGpuModelFp16, useGpuFp16)
        //warmUpRun()
    }

    fun close() {
        closeTFLite()
        closeHandlerThreads()
        Log.i(TAG, "PETaskScheduler closed")
    }

    @Synchronized private fun processResult() {
        val pointArrays = ArrayList<Array<FloatArray>>()

        /*****
        if (cpuPointArrayQueue.size > 0) {
            pointArrays.addAll(cpuPointArrayQueue[0])
            cpuPointArrayQueue.removeAt(0)
        }
        if (gpuPointArrayQueue.size > 0) {
            pointArrays.addAll(gpuPointArrayQueue[0])
            gpuPointArrayQueue.removeAt(0)
        }
        *****/

        val cpuPointArrays = cpuPointArrayQueue.dequeue()
        if (cpuPointArrays != null) {
            for (array in cpuPointArrays) {
                pointArrays.add(array)
            }
        }

        val gpuPointArrays = gpuPointArrayQueue.dequeue()
        if (gpuPointArrays != null) {
            for (array in gpuPointArrays) {
                pointArrays.add(array)
            }
        }

        // Start a new thread for PETaskCallback
        //heatMapsThreadHandler?.post {
        //    peTaskCallback.call(pointArrays)
        //}

        // Put output point arrays
        outputPointArraysQueue.enqueue(pointArrays)

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

    private fun parallelClassifyFrameT2(b0: Bitmap, b1: Bitmap): ArrayList<Array<FloatArray>> {
        val tempOutArray = Array(2) { Array(2) { FloatArray(14) } }
        val lock: Byte = 0
        var countDown = 2

        threadPool.submit {
            mCPUHourGlassT2[1].classifyFrame(b1)
            val pa = mCPUHourGlassT2[1].getPointArray()
            if (pa != null)
                tempOutArray[1] = pa.clone()

            synchronized(lock) {
                countDown --
            }
        }

        mCPUHourGlassT2[0].classifyFrame(b0)
        val pa = mCPUHourGlassT2[0].getPointArray()
        if (pa != null)
            tempOutArray[0] = pa.clone()

        synchronized(lock) {
            countDown --
        }

        while (countDown > 0) {
            sleep(1)
        }

        val output = ArrayList<Array<FloatArray>>()
        output.addAll(tempOutArray)

        return output
    }

    private fun parallelClassifyFrame(numThreads: Int, bitmaps: ArrayList<Bitmap>):
                    ArrayList<Array<FloatArray>> {
        val tempOutArray = Array(numThreads) { Array(2) { FloatArray(14) } }
        val lock: Byte = 0
        var countDown = numThreads

        for (i in 1 until numThreads) {
            threadPool.submit {
                mCPUHourGlassMT[numThreads - 1][i].classifyFrame(bitmaps[i])
                val pa = mCPUHourGlassMT[numThreads - 1][i].getPointArray()
                if (pa != null)
                    tempOutArray[i] = pa.clone()

                synchronized(lock) {
                    countDown --
                }
            }
        }

        mCPUHourGlassMT[numThreads - 1][0].classifyFrame(bitmaps[0])
        val pa = mCPUHourGlassMT[numThreads - 1][0].getPointArray()
        if (pa != null)
            tempOutArray[0] = pa.clone()

        synchronized(lock) {
            countDown --
        }

        while (countDown > 0) {
            sleep(1)
        }

        val output = ArrayList<Array<FloatArray>>()
        output.addAll(tempOutArray)

        return output
    }

    private fun cpuProcessBitmapT1(bitmaps: ArrayList<Bitmap>,
                                   pointArrays: ArrayList<Array<FloatArray>>) {
        val bitmap = bitmaps[0]
        mCPUHourGlass?.classifyFrame(bitmap)
        bitmaps.remove(bitmap)

        val pointArray = mCPUHourGlass?.getPointArray()?.clone()
        pointArrays.add(pointArray!!)
    }

    private fun cpuProcessBitmapT2(bitmaps: ArrayList<Bitmap>,
                                   pointArrays: ArrayList<Array<FloatArray>>) {
        val outPointArrays = parallelClassifyFrame(2, bitmaps)
        for (i in 0 until 2) bitmaps.removeAt(0)
        pointArrays.addAll(outPointArrays)
    }

    private fun runTaskIfNot() {
        if (!getIsGPURunning() && gpuTasksQueue.getQueueSize() > 0) {
            // Run GPU thread
            gpuThreadHandler?.post {
                //Log.d(TAG, "Run GPU thread")

                setIsGPURunning(true)

                while (gpuTasksQueue.getQueueSize() > 0) {
                    val gpuBitmaps = gpuTasksQueue.getFirstTasks()

                    val gpuPointArrays = ArrayList<Array<FloatArray>>()

                    while (gpuBitmaps.size > 0) {
                        //Log.i(TAG, "GPU classify frame $i")
                        val startTime = System.currentTimeMillis()
                        mGPUHourGlass?.classifyFrame(gpuBitmaps[0])
                        val costTime = System.currentTimeMillis() - startTime
                        onDeviceEvaluator.updateDeviceExecTime(
                                OnDeviceEvaluator.DEVICE_ID_GPU, costTime.toFloat())

                        gpuBitmaps.removeAt(0)

                        val pointArray = mGPUHourGlass?.getPointArray()?.clone()
                        //if (pointArray != null)
                        gpuPointArrays.add(pointArray!!)
                    }

                    gpuPointArrayQueue.enqueue(gpuPointArrays)

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

            cpuThreadHandler?.post {
                while (cpuTasksQueue.getQueueSize() > 0) {
                    // Run CPU thread
                    val cpuPointArrays = ArrayList<Array<FloatArray>>()

                    val cpuBitmaps = cpuTasksQueue.getFirstTasks()

                    while (cpuBitmaps.size > 0) {
                        //Log.i(TAG, "CPU classify frame $i")
                        //Log.i(TAG, "CPU left ${cpuBitmaps.size} bitmaps")

                        val useMultiThreadParallel = false

                        if (useMultiThreadParallel && cpuBitmaps.size >= 4) {
                            //Log.i(TAG, "CPU take 4 bitmaps")
                            val startTime = System.currentTimeMillis()
                            val outPointArrays = parallelClassifyFrame(4, cpuBitmaps)
                            val costTime = System.currentTimeMillis() - startTime

                            for (i in 0 until 4) cpuBitmaps.removeAt(0)

                            cpuPointArrays.addAll(outPointArrays)

                            onDeviceEvaluator.updateDeviceExecTime(
                                    OnDeviceEvaluator.DEVICE_ID_CPU_MT, 4, costTime.toFloat())
                        } else if (useMultiThreadParallel && cpuBitmaps.size >= 3) {
                            //Log.i(TAG, "CPU take 3 bitmaps")
                            val startTime = System.currentTimeMillis()
                            cpuProcessBitmapT2(cpuBitmaps, cpuPointArrays)
                            cpuProcessBitmapT1(cpuBitmaps, cpuPointArrays)
                            val costTime = System.currentTimeMillis() - startTime

                            onDeviceEvaluator.updateDeviceExecTime(
                                    OnDeviceEvaluator.DEVICE_ID_CPU_MT, 3, costTime.toFloat())
                        } else if (useMultiThreadParallel && cpuBitmaps.size >= 2) {
                            //Log.i(TAG, "CPU take 2 bitmaps")
                            val startTime = System.currentTimeMillis()
                            cpuProcessBitmapT2(cpuBitmaps, cpuPointArrays)
                            val costTime = System.currentTimeMillis() - startTime

                            onDeviceEvaluator.updateDeviceExecTime(
                                    OnDeviceEvaluator.DEVICE_ID_CPU_MT, 2, costTime.toFloat())
                        } else {
                            //Log.i(TAG, "CPU take 1 bitmaps")
                            val startTime = System.currentTimeMillis()
                            cpuProcessBitmapT1(cpuBitmaps, cpuPointArrays)
                            val costTime = System.currentTimeMillis() - startTime

                            onDeviceEvaluator.updateDeviceExecTime(
                                    OnDeviceEvaluator.DEVICE_ID_CPU, costTime.toFloat())
                        }
                    }

                    cpuPointArrayQueue.enqueue(cpuPointArrays)

                    cpuTasksQueue.dequeueFirstTasks()

                    if (curScheduleMode >= MODE_CPUGPU)
                        addOrRemoveTaskFinishedFlag(FLAG_CPU_TASK_FINISH)
                    else
                        processResult()
                }

                setIsCPURunning(false)
            }
        }
    }

    private fun waitTask() {
        sleep(10)
    }

    fun scheduleAndRun(pictures: ArrayList<Bitmap>, mode: Int): Long {

        //Log.d(TAG, "PE tasks come")

        curScheduleMode = mode
        var ticket = -1L

        when (curScheduleMode) {
            MODE_CPU -> {
                scheduleOnCPU(pictures)
            }
            MODE_GPU -> {
                scheduleOnGPU(pictures)
            }
            MODE_CPUGPU -> {
                ticket = scheduleOnCPUGPU(pictures, false)
            }
            MODE_CPUGPU_WMA -> {
                ticket = scheduleOnCPUGPU(pictures, true)
            }
        }

        runTaskIfNot()

        return ticket
    }

    private fun scheduleOnCPUGPU(pictures: ArrayList<Bitmap>, useWMA: Boolean): Long {
        //val peTaskEndTime = getPETaskTimeEnd()

        val cpuTaskSize = cpuTasksQueue.getTotalTasksSize()
        val gpuTaskSize = gpuTasksQueue.getTotalTasksSize()

        val cpuTaskAvgTime: Float
        val gpuTaskAvgTime: Float
        if (useWMA) {
            cpuTaskAvgTime = onDeviceEvaluator.getDeviceEstimatedExecTime(
                                OnDeviceEvaluator.DEVICE_ID_CPU)
            gpuTaskAvgTime = onDeviceEvaluator.getDeviceEstimatedExecTime(
                                OnDeviceEvaluator.DEVICE_ID_GPU)
        } else {
            cpuTaskAvgTime = onDeviceEvaluator.getStaticDeviceEstimatedExecTime(
                                OnDeviceEvaluator.DEVICE_ID_CPU)
            gpuTaskAvgTime = onDeviceEvaluator.getStaticDeviceEstimatedExecTime(
                                OnDeviceEvaluator.DEVICE_ID_GPU)
        }

        //Log.d(TAG, "Avg time CPU $cpuTaskAvgTime GPU $gpuTaskAvgTime")

        var cpuTaskEndTime = cpuTaskSize * cpuTaskAvgTime
        var gpuTaskEndTime = gpuTaskSize * gpuTaskAvgTime

        val cpuQueue = ArrayList<Bitmap>()
        val gpuQueue = ArrayList<Bitmap>()

        var cpuTaskCount = 0
        var gpuTaskCount = 0

        // Schedule tasks
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

        // Enqueue tasks
        for (i in 0 until cpuTaskCount) {
            cpuQueue.add(pictures[i])
        }
        for (i in cpuTaskCount until pictures.size) {
            gpuQueue.add(pictures[i])
        }

        cpuTasksQueue.enqueueTasks(cpuQueue)
        gpuTasksQueue.enqueueTasks(gpuQueue)

        val ticket = System.currentTimeMillis()
        outputTicketQueue.enqueue(ticket)
        return ticket
    }

    private fun evaluateCPUTaskEndTime(numCPU: Int, useWMA: Boolean): Float {

        var endTime = 0.0f

        // Estimate task end time in CPU tasks queue
        for (i in 0 until cpuTasksQueue.getQueueSize()) {
            val tasksItem = cpuTasksQueue.getTasksItem(i)
            val t4Count = tasksItem.size / 4
            if (useWMA) {
                endTime += t4Count * onDeviceEvaluator.getDeviceEstimatedExecTime(
                        OnDeviceEvaluator.DEVICE_ID_CPU_MT, 4)
                endTime += onDeviceEvaluator.getDeviceEstimatedExecTime(
                        OnDeviceEvaluator.DEVICE_ID_CPU_MT, tasksItem.size % 4)
            } else {
                endTime += t4Count * onDeviceEvaluator.getStaticDeviceEstimatedExecTime(
                        OnDeviceEvaluator.DEVICE_ID_CPU_MT, 4)
                endTime += onDeviceEvaluator.getStaticDeviceEstimatedExecTime(
                        OnDeviceEvaluator.DEVICE_ID_CPU_MT, tasksItem.size % 4)
            }
        }

        // Estimate task end time after put new tasks in CPU tasks queue
        val t4Count = numCPU / 4
        if (useWMA) {
            endTime += t4Count * onDeviceEvaluator.getDeviceEstimatedExecTime(
                    OnDeviceEvaluator.DEVICE_ID_CPU_MT, 4)
            endTime += onDeviceEvaluator.getDeviceEstimatedExecTime(
                    OnDeviceEvaluator.DEVICE_ID_CPU_MT, numCPU % 4)
        } else {
            endTime += t4Count * onDeviceEvaluator.getStaticDeviceEstimatedExecTime(
                    OnDeviceEvaluator.DEVICE_ID_CPU_MT, 4)
            endTime += onDeviceEvaluator.getStaticDeviceEstimatedExecTime(
                    OnDeviceEvaluator.DEVICE_ID_CPU_MT, numCPU % 4)
        }

        return endTime
    }

    private fun evaluateGPUTaskEndTime(numGPU: Int, useWMA: Boolean): Float {
        val gpuTaskSize = gpuTasksQueue.getTotalTasksSize()

        val gpuTaskAvgTime = if (useWMA) {
            onDeviceEvaluator.getDeviceEstimatedExecTime(
                    OnDeviceEvaluator.DEVICE_ID_GPU)
        } else {
            onDeviceEvaluator.getStaticDeviceEstimatedExecTime(
                    OnDeviceEvaluator.DEVICE_ID_GPU)
        }

        return (gpuTaskSize + numGPU) * gpuTaskAvgTime
    }

    private fun evaluateCompletionTime(numCPU: Int, numGPU: Int, useWMA: Boolean): Float {
        val cpuTaskEndTime = evaluateCPUTaskEndTime(numCPU, useWMA)
        val gpuTaskEndTime = evaluateGPUTaskEndTime(numGPU, useWMA)
        return cpuTaskEndTime.coerceAtLeast(gpuTaskEndTime)
    }

    private fun scheduleOnCPUGPULOP(pictures: ArrayList<Bitmap>, useWMA: Boolean): Long {

        var minCompletionTime = Float.MAX_VALUE
        var bestNumCPU = pictures.size
        //var bestNumGPU = 0

        // Enumerate all possible value of CPU tasks
        for (numCPU in 0 until pictures.size) {
            val numGPU = pictures.size - numCPU
            val completionTime = evaluateCompletionTime(numCPU, numGPU, useWMA)
            if (completionTime < minCompletionTime) {
                minCompletionTime = completionTime
                bestNumCPU = numCPU
                //bestNumGPU = numGPU
            }
        }

        // Enqueue tasks
        val cpuQueue = ArrayList<Bitmap>()
        val gpuQueue = ArrayList<Bitmap>()

        for (i in 0 until bestNumCPU) {
            cpuQueue.add(pictures[i])
        }
        for (i in bestNumCPU until pictures.size) {
            gpuQueue.add(pictures[i])
        }

        cpuTasksQueue.enqueueTasks(cpuQueue)
        gpuTasksQueue.enqueueTasks(gpuQueue)

        val ticket = System.currentTimeMillis()
        outputTicketQueue.enqueue(ticket)
        return ticket
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
