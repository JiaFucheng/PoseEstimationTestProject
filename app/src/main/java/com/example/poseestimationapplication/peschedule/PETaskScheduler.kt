package com.example.poseestimationapplication.peschedule

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.example.poseestimationapplication.peschedule.PETaskSchedulerMode
import com.example.poseestimationapplication.tflite.ImageClassifierFloatInception
import com.example.poseestimationapplication.tool.BitmapLoader
import com.example.poseestimationapplication.tool.MathTool
import java.lang.Thread.sleep
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

class PETaskScheduler(private val activity: Activity) : PETaskSchedulerInterface {

    companion object {
        const val CPU_INT_8 = 8
        const val CPU_FP_16 = 16
        const val CPU_FP_32 = 32

        private const val FLAG_CPU_TASK_FINISH = 0
        private const val FLAG_GPU_TASK_FINISH = 1
    }

    private val TAG = "PETaskScheduler"

    /**
     * tfliteModelName
     * [0]: int8
     * [1]: fp16
     * [2]: fp32
     */
    private val tfliteModelNames = arrayListOf(
            "hourglass_model_fp8.tflite",
            "hourglass_model_fp16.tflite",
            "hourglass_model.tflite")

    private val onDeviceEvaluator: OnDeviceEvaluator = OnDeviceEvaluator()

    private val cpuTasksQueue = PETasksQueue()
    private val gpuTasksQueue = PETasksQueue()

    private val cpuPointArraysQueue = PointArraysQueue()
    private val gpuPointArraysQueue = PointArraysQueue()
    private val outputPointArraysQueue = PointArraysQueue()
    private val outputTicketQueue = OutputTicketQueue()

    private val taskFinishedFlags = ArrayList<Int>()

    private var mCPUHourGlass: ImageClassifierFloatInception ?= null
    private var mGPUHourGlass: ImageClassifierFloatInception ?= null
    //private var mCPUHourGlassT2 = ArrayList<ImageClassifierFloatInception>()
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

    private var picWidth: Int = 192
    private var picHeight: Int = 192

    private var curScheduleMode: Int = PETaskSchedulerMode.MODE_CPU
    private var taskId: Int = 0
    private var taskStartTime: Long = System.currentTimeMillis()
    private var taskCostTime: Long = 0

    private var useMultiThreadModel: Boolean = true

    init {
        initHandlerThreads()
    }

    override fun setTaskStartTime(time: Long) {
        taskStartTime = time
    }

    override fun getTaskCostTime(): Long {
        return taskCostTime
    }

    override fun isAllTasksFinished() : Boolean {
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
                outputTicketQueue.dequeue()
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
        if (!threadPool.awaitTermination(10, TimeUnit.SECONDS)) {
            Log.i(TAG, "ThreadPool: Shutdown over 10 sec")
        }

        cpuHandlerThread?.quit()
        gpuHandlerThread?.quit()
        heatMapsHandlerThread?.quit()
    }

    private fun initTFLite(numThreads: Int, cpuFp: Int,
                           useGpuModelFp16: Boolean, useGpuFp16: Boolean) {
        val cpuModelName = when {
            (cpuFp == CPU_INT_8) -> tfliteModelNames[0]
            (cpuFp == CPU_FP_16) -> tfliteModelNames[1]
            else                 -> tfliteModelNames[2]
        }

        val gpuModelName = if (useGpuModelFp16) tfliteModelNames[1]
                           else                 tfliteModelNames[2]

        val numBytesPerChannel = if (cpuFp == CPU_INT_8) 1
                                 else                    4

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
                "(numThreads=$numThreads, cpuFp=$cpuFp, " +
                " useGpuModelFp16=$useGpuModelFp16, useGpuFp16=$useGpuFp16)")
    }

    private fun closeTFLite() {
        mCPUHourGlass?.close()
        mGPUHourGlass?.close()
        //for (model in mCPUHourGlassT2)
        //    model.close()
        for (model in mCPUHourGlassMT[1])
            model.close()
        for (model in mCPUHourGlassMT[3])
            model.close()
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

    override fun init(inputSize: Int, numThreads: Int,
             cpuFp: Int, useGpuModelFp16: Boolean, useGpuFp16: Boolean) {
        this.picWidth = inputSize
        this.picHeight = inputSize
        initTFLite(numThreads, cpuFp, useGpuModelFp16, useGpuFp16)
        //warmUpRun()
    }

    override fun close() {
        closeTFLite()
        closeHandlerThreads()
        Log.i(TAG, "PETaskScheduler closed")
    }

    @Synchronized private fun processResult() {
        val pointArrays = ArrayList<Array<FloatArray>>()

        val cpuPointArrays = cpuPointArraysQueue.dequeue()
        if (cpuPointArrays != null)
            pointArrays.addAll(cpuPointArrays)

        val gpuPointArrays = gpuPointArraysQueue.dequeue()
        if (gpuPointArrays != null)
            pointArrays.addAll(gpuPointArrays)

        // Start a new thread for PETaskCallback
        //heatMapsThreadHandler?.post {
        //    peTaskCallback.call(pointArrays)
        //}

        // Put output point arrays
        outputPointArraysQueue.enqueue(pointArrays)

        taskCostTime = System.currentTimeMillis() - taskStartTime
        val modeName = PETaskSchedulerMode.getScheduleModeName(curScheduleMode)
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

    /*****
    private fun parallelClassifyFrameT2(b0: Bitmap, b1: Bitmap): ArrayList<Array<FloatArray>> {
        val tempOutArray = Array(2) { Array(2) { FloatArray(14) } }
        val lock: Byte = 0
        var countDown = 2

        threadPool.submit {
            mCPUHourGlassT2[1].classifyFrame(b1)
            val pa = mCPUHourGlassT2[1].getCopyPointArray()
            if (pa != null)
                tempOutArray[1] = pa

            synchronized(lock) {
                countDown --
            }
        }

        mCPUHourGlassT2[0].classifyFrame(b0)
        val pa = mCPUHourGlassT2[0].getCopyPointArray()
        if (pa != null)
            tempOutArray[0] = pa

        synchronized(lock) {
            countDown --
        }

        // Wait other threads
        while (countDown > 0) { sleep(1) }

        val output = ArrayList<Array<FloatArray>>()
        output.addAll(tempOutArray)

        return output
    }
    *****/

    private fun parallelClassifyFrame(numThreads: Int, bitmaps: ArrayList<Bitmap>):
                    ArrayList<Array<FloatArray>> {
        val tempOutArray = Array(numThreads) { Array(2) { FloatArray(14) } }
        val lock: Byte = 0
        var countDown = numThreads

        for (i in 1 until numThreads) {
            threadPool.submit {
                mCPUHourGlassMT[numThreads - 1][i].classifyFrame(bitmaps[i])
                val pa = mCPUHourGlassMT[numThreads - 1][i].getCopyPointArray()
                if (pa != null)
                    tempOutArray[i] = pa

                synchronized(lock) {
                    countDown --
                }
            }
        }

        mCPUHourGlassMT[numThreads - 1][0].classifyFrame(bitmaps[0])
        val pa = mCPUHourGlassMT[numThreads - 1][0].getCopyPointArray()
        if (pa != null)
            tempOutArray[0] = pa

        synchronized(lock) {
            countDown --
        }

        // Wait other threads
        while (countDown > 0) { sleep(1) }

        val output = ArrayList<Array<FloatArray>>()
        output.addAll(tempOutArray)

        return output
    }

    private fun cpuProcessBitmapT1(bitmaps: ArrayList<Bitmap>,
                                   pointArrays: ArrayList<Array<FloatArray>>) {
        mCPUHourGlass?.classifyFrame(bitmaps[0])
        bitmaps.removeAt(0)

        val pointArray = mCPUHourGlass?.getCopyPointArray()
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
                    val gpuBitmaps = gpuTasksQueue.getFirstTasksItem()

                    val gpuPointArrays = ArrayList<Array<FloatArray>>()

                    while (gpuBitmaps != null && gpuBitmaps.size > 0) {
                        //Log.i(TAG, "GPU classify frame $i")
                        val startTime = System.currentTimeMillis()
                        mGPUHourGlass?.classifyFrame(gpuBitmaps[0])
                        val costTime = System.currentTimeMillis() - startTime
                        onDeviceEvaluator.updateDeviceExecTime(
                                OnDeviceEvaluator.DEVICE_ID_GPU, costTime.toFloat())

                        gpuBitmaps.removeAt(0)

                        val pointArray = mGPUHourGlass?.getCopyPointArray()
                        //if (pointArray != null)
                        gpuPointArrays.add(pointArray!!)
                    }

                    gpuPointArraysQueue.enqueue(gpuPointArrays)

                    gpuTasksQueue.dequeue()

                    if (curScheduleMode >= PETaskSchedulerMode.MODE_CPUGPU)
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

                    val cpuBitmaps = cpuTasksQueue.getFirstTasksItem()

                    while (cpuBitmaps != null && cpuBitmaps.size > 0) {
                        //Log.i(TAG, "CPU classify frame $i")
                        //Log.i(TAG, "CPU left ${cpuBitmaps.size} bitmaps")

                        if (useMultiThreadModel && cpuBitmaps.size >= 4) {
                            //Log.i(TAG, "CPU take 4 bitmaps")
                            val startTime = System.currentTimeMillis()
                            val outPointArrays = parallelClassifyFrame(4, cpuBitmaps)
                            val costTime = System.currentTimeMillis() - startTime

                            for (i in 0 until 4)
                                cpuBitmaps.removeAt(0)

                            cpuPointArrays.addAll(outPointArrays)

                            onDeviceEvaluator.updateDeviceExecTime(
                                    OnDeviceEvaluator.DEVICE_ID_CPU_MT, 4, costTime.toFloat())
                        } else if (useMultiThreadModel && cpuBitmaps.size >= 3) {
                            //Log.i(TAG, "CPU take 3 bitmaps")
                            val startTime = System.currentTimeMillis()
                            cpuProcessBitmapT2(cpuBitmaps, cpuPointArrays)
                            cpuProcessBitmapT1(cpuBitmaps, cpuPointArrays)
                            val costTime = System.currentTimeMillis() - startTime

                            onDeviceEvaluator.updateDeviceExecTime(
                                    OnDeviceEvaluator.DEVICE_ID_CPU_MT, 3, costTime.toFloat())
                        } else if (useMultiThreadModel && cpuBitmaps.size >= 2) {
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

                    cpuPointArraysQueue.enqueue(cpuPointArrays)

                    cpuTasksQueue.dequeue()

                    if (curScheduleMode >= PETaskSchedulerMode.MODE_CPUGPU)
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

    private fun scheduleOnCPUGPU(pictures: ArrayList<Bitmap>, useWMA: Boolean) {
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
        for (i in 0 until cpuTaskCount)
            cpuQueue.add(pictures[i])
        for (i in cpuTaskCount until pictures.size)
            gpuQueue.add(pictures[i])

        cpuTasksQueue.enqueue(cpuQueue)
        gpuTasksQueue.enqueue(gpuQueue)
    }

    private fun evaluateCPUTaskEndTime(numCPU: Int, useWMA: Boolean): Float {

        var endTime = 0.0f

        // Estimate task end time in CPU tasks queue
        for (i in 0 until cpuTasksQueue.getQueueSize()) {
            val tasksItem = cpuTasksQueue.getTasksItem(i)
            if (tasksItem != null) {
                val t4Count = tasksItem.size / 4
                val remainedCount = tasksItem.size % 4
                if (useWMA) {
                    endTime += t4Count * onDeviceEvaluator.getDeviceEstimatedExecTime(
                            OnDeviceEvaluator.DEVICE_ID_CPU_MT, 4)
                    if (remainedCount > 0) {
                        endTime += if (remainedCount == 1) {
                            onDeviceEvaluator.getDeviceEstimatedExecTime(
                                    OnDeviceEvaluator.DEVICE_ID_CPU)
                        } else {
                            onDeviceEvaluator.getDeviceEstimatedExecTime(
                                    OnDeviceEvaluator.DEVICE_ID_CPU_MT, remainedCount)
                        }
                    }
                } else {
                    endTime += t4Count * onDeviceEvaluator.getStaticDeviceEstimatedExecTime(
                            OnDeviceEvaluator.DEVICE_ID_CPU_MT, 4)
                    if (remainedCount > 0) {
                        endTime += if (remainedCount == 1) {
                            onDeviceEvaluator.getStaticDeviceEstimatedExecTime(
                                    OnDeviceEvaluator.DEVICE_ID_CPU)
                        } else {
                            onDeviceEvaluator.getStaticDeviceEstimatedExecTime(
                                    OnDeviceEvaluator.DEVICE_ID_CPU_MT, tasksItem.size % 4)
                        }
                    }
                }
            }
        }

        // Estimate task end time after put new tasks in CPU tasks queue
        val t4Count = numCPU / 4
        val remainedNumCPU = numCPU % 4
        if (useWMA) {
            endTime += t4Count * onDeviceEvaluator.getDeviceEstimatedExecTime(
                    OnDeviceEvaluator.DEVICE_ID_CPU_MT, 4)
            if (remainedNumCPU > 0) {
                endTime += if (remainedNumCPU == 1) {
                    onDeviceEvaluator.getDeviceEstimatedExecTime(
                            OnDeviceEvaluator.DEVICE_ID_CPU)
                } else {
                    onDeviceEvaluator.getDeviceEstimatedExecTime(
                            OnDeviceEvaluator.DEVICE_ID_CPU_MT, remainedNumCPU)
                }
            }
        } else {
            endTime += t4Count * onDeviceEvaluator.getStaticDeviceEstimatedExecTime(
                    OnDeviceEvaluator.DEVICE_ID_CPU_MT, 4)
            if (remainedNumCPU > 0) {
                endTime += if (remainedNumCPU == 1) {
                    onDeviceEvaluator.getStaticDeviceEstimatedExecTime(
                            OnDeviceEvaluator.DEVICE_ID_CPU)
                } else {
                    onDeviceEvaluator.getStaticDeviceEstimatedExecTime(
                            OnDeviceEvaluator.DEVICE_ID_CPU_MT, remainedNumCPU)
                }
            }
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

    private fun scheduleOnCPUGPUMT(pictures: ArrayList<Bitmap>, useWMA: Boolean) {

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

        Log.d(TAG, "Schedule CPU $bestNumCPU GPU ${pictures.size - bestNumCPU} tasks")

        // Enqueue tasks
        val cpuQueue = ArrayList<Bitmap>()
        val gpuQueue = ArrayList<Bitmap>()

        for (i in 0 until bestNumCPU)
            cpuQueue.add(pictures[i])
        for (i in bestNumCPU until pictures.size)
            gpuQueue.add(pictures[i])

        cpuTasksQueue.enqueue(cpuQueue)
        gpuTasksQueue.enqueue(gpuQueue)
    }

    private fun scheduleOnCPU(pictures: ArrayList<Bitmap>) {
        val cpuTasks = ArrayList<Bitmap>()
        for (picture in pictures)
            cpuTasks.add(picture)
        cpuTasksQueue.enqueue(cpuTasks)
    }

    private fun scheduleOnGPU(pictures: ArrayList<Bitmap>) {
        val gpuTasks = ArrayList<Bitmap>()
        for (picture in pictures)
            gpuTasks.add(picture)
        gpuTasksQueue.enqueue(gpuTasks)
    }

    override fun scheduleAndRun(pictures: ArrayList<Bitmap>, mode: Int): Long {

        //Log.d(TAG, "PE tasks come")

        curScheduleMode = mode
        useMultiThreadModel = (curScheduleMode == PETaskSchedulerMode.MODE_CPUGPU_MT ||
                               curScheduleMode == PETaskSchedulerMode.MODE_CPUGPU_MT_WMA)

        Log.i(TAG, "Task scheduled at ${System.currentTimeMillis() - taskStartTime} ms")

        when (curScheduleMode) {
            PETaskSchedulerMode.MODE_CPU -> {
                scheduleOnCPU(pictures)
            }
            PETaskSchedulerMode.MODE_GPU -> {
                scheduleOnGPU(pictures)
            }
            PETaskSchedulerMode.MODE_CPUGPU -> {
                scheduleOnCPUGPU(pictures, false)
            }
            PETaskSchedulerMode.MODE_CPUGPU_WMA -> {
                scheduleOnCPUGPU(pictures, true)
            }
            PETaskSchedulerMode.MODE_CPUGPU_MT -> {
                scheduleOnCPUGPUMT(pictures, false)
            }
            PETaskSchedulerMode.MODE_CPUGPU_MT_WMA -> {
                scheduleOnCPUGPUMT(pictures, true)
            }
        }

        runTaskIfNot()

        // Create a ticket for tasks owner
        val ticket = System.currentTimeMillis()
        outputTicketQueue.enqueue(ticket)

        return ticket
    }
}
