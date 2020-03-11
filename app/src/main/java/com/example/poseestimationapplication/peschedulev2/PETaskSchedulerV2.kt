package com.example.poseestimationapplication.peschedulev2

import android.app.Activity
import android.graphics.Bitmap
import android.util.Log
import com.example.poseestimationapplication.peschedule.PETaskScheduler
import com.example.poseestimationapplication.peschedule.PETaskSchedulerInterface
import com.example.poseestimationapplication.tflite.ImageClassifierFloatInception
import java.lang.Thread.sleep
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Suppress("NAME_SHADOWING")
class PETaskSchedulerV2(private val activity: Activity) : PETaskSchedulerInterface {

    companion object {
        private const val DEVICE_ID_CPU = 0
        private const val DEVICE_ID_GPU = 1

        private const val DEVICE_STATUS_BUSY = 0
        private const val DEVICE_STATUS_FREE = 1

        private const val LOCK_ID_DEVICE_STATUS = 0
        private const val LOCK_ID_AVAILABLE_THREAD_NUM = 1
        private const val LOCK_ID_AVAILABLE_CONTROL_THREAD_NUM = 2
        private const val LOCK_ID_MODELS = 3

        private const val RESULT_OK = 0
        private const val RESULT_FAILED = -1
    }

    private val TAG = "PETaskSchedulerV2"

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

    private val tasksQueue = PETasksQueue()
    private val ticketsQueue = ArrayList<Long>()

    private val maxCpuControlThreadsNum = 4
    private val maxCpuInferenceThreadsNum = 4
    private var curCpuControlThreadsNum = 0
    private var availableCPUThreadNum = maxCpuInferenceThreadsNum

    private val cpuModelsBusyStatusArray = Array(4) { BooleanArray(4) }
    private var hourGlassCPUModels: ArrayList<ArrayList<ImageClassifierFloatInception>> ?= null
    private var hourGlassGPUModels: ImageClassifierFloatInception ?= null

    // CPU Control Threads Number + CPU Inference Threads Number - 1 + 1 GPU Control/Inference Thread
    private var threadPool: ExecutorService = Executors.newFixedThreadPool(
            maxCpuControlThreadsNum + maxCpuInferenceThreadsNum)

    private val deviceStatus = IntArray(2)

    private val locks = ByteArray(8)

    private var taskStartTime: Long = -1L
    private var taskUsedTime: Long = 0

    private fun initDeviceStatus() {
        setDeviceStatus(DEVICE_ID_CPU, DEVICE_STATUS_FREE)
        setDeviceStatus(DEVICE_ID_GPU, DEVICE_STATUS_FREE)
    }

    private fun initTFLite(inputSize: Int, cpuFp: Int, useGpuModelFp16: Boolean, useGpuFp16: Boolean) {
        val cpuModelName = when {
            (cpuFp == PETaskScheduler.CPU_INT_8) -> tfliteModelNames[0]
            (cpuFp == PETaskScheduler.CPU_FP_16) -> tfliteModelNames[1]
            else                                 -> tfliteModelNames[2]
        }

        val gpuModelName = if (useGpuModelFp16)
                                tfliteModelNames[1]
                           else
                                tfliteModelNames[2]

        val numBytesPerChannel =
                if (cpuFp == PETaskScheduler.CPU_INT_8)
                    1
                else
                    4

        // CPU Models
        hourGlassCPUModels = ArrayList()
        for (i in 0 until 4)
            hourGlassCPUModels?.add(ArrayList())

        val numModelsArr = intArrayOf(4, 2, 1, 1)

        // numThreads=1, numModels=4
        var numThreads = 1
        for (i in 0 until numModelsArr[numThreads-1]) {
            hourGlassCPUModels!![numThreads-1].add(ImageClassifierFloatInception.create(
                    activity, imageSizeX = inputSize, imageSizeY = inputSize,
                    modelPath = cpuModelName, numBytesPerChannel = numBytesPerChannel))
            hourGlassCPUModels!![numThreads-1][i].initTFLite(numThreads, useGPU = false, useGpuFp16 = false)
        }

        // numThreads=2, numModels=2
        numThreads = 2
        for (i in 0 until numModelsArr[numThreads-1]) {
            hourGlassCPUModels!![numThreads-1].add(ImageClassifierFloatInception.create(
                    activity, imageSizeX = inputSize, imageSizeY = inputSize,
                    modelPath = cpuModelName, numBytesPerChannel = numBytesPerChannel))
            hourGlassCPUModels!![numThreads-1][i].initTFLite(numThreads, useGPU = false, useGpuFp16 = false)
        }

        // numThreads=3, numModels=1
        numThreads = 3
        for (i in 0 until numModelsArr[numThreads-1]) {
            hourGlassCPUModels!![numThreads-1].add(ImageClassifierFloatInception.create(
                    activity, imageSizeX = inputSize, imageSizeY = inputSize,
                    modelPath = cpuModelName, numBytesPerChannel = numBytesPerChannel))
            hourGlassCPUModels!![numThreads-1][i].initTFLite(numThreads, useGPU = false, useGpuFp16 = false)
        }

        // numThreads=4, numModels=1
        numThreads = 4
        for (i in 0 until numModelsArr[numThreads-1]) {
            hourGlassCPUModels!![numThreads-1].add(ImageClassifierFloatInception.create(
                    activity, imageSizeX = inputSize, imageSizeY = inputSize,
                    modelPath = cpuModelName, numBytesPerChannel = numBytesPerChannel))
            hourGlassCPUModels!![numThreads-1][i].initTFLite(numThreads, useGPU = false, useGpuFp16 = false)
        }

        // CPU Model Busy Array
        for (busyArr in cpuModelsBusyStatusArray) {
            for (i in busyArr.indices) {
                busyArr[i] = false
            }
        }

        // GPU Model
        hourGlassGPUModels = ImageClassifierFloatInception.create(
                activity, imageSizeX = inputSize, imageSizeY = inputSize, modelPath = gpuModelName)
        hourGlassGPUModels?.initTFLite(-1, true, useGpuFp16)

        Log.i(TAG, "Init TFLite OK")
    }

    fun init(inputSize: Int, cpuFp: Int, useGpuModelFp16: Boolean, useGpuFp16: Boolean) {
        initDeviceStatus()
        initTFLite(inputSize, cpuFp, useGpuModelFp16, useGpuFp16)
    }

    override fun init(inputSize: Int, numThreads: Int, cpuFp: Int, useGpuModelFp16: Boolean, useGpuFp16: Boolean) {
        init(inputSize, cpuFp, useGpuModelFp16, useGpuFp16)
    }

    private fun closeTFLite() {
        for (models in hourGlassCPUModels!!) {
            for (model in models) {
                model.close()
            }
        }
    }

    private fun closeThreadPool() {
        threadPool.shutdown()
    }

    private fun isDeviceBusy(): Boolean {
        Log.i(TAG, "CPU ${getDeviceStatus(DEVICE_ID_CPU)}" +
                " GPU ${getDeviceStatus(DEVICE_ID_GPU)}" +
                " CpuControlThreadNum=${getCurCPUControlThreadNum()}" +
                " CpuAvailableThreadNum=${getCPUThreadsResource()}")

        return (getDeviceStatus(DEVICE_ID_CPU) == DEVICE_STATUS_BUSY
                || getDeviceStatus(DEVICE_ID_GPU) == DEVICE_STATUS_BUSY
                || getCurCPUControlThreadNum() > 0
                || getCPUThreadsResource() < maxCpuInferenceThreadsNum)
    }

    private fun waitIfDeviceIsBusy() {
        while (isDeviceBusy()) {
            sleep(1)
        }
    }

    override fun close() {
        // Wait until all devices are free
        //waitIfDeviceIsBusy()

        closeThreadPool()
        closeTFLite()
    }

    /* Device Status. **/
    private fun setDeviceStatus(deviceId: Int, status: Int) {
        synchronized(locks[LOCK_ID_DEVICE_STATUS]) {
            deviceStatus[deviceId] = status
        }
    }

    private fun getDeviceStatus(deviceId: Int): Int {
        synchronized(locks[LOCK_ID_DEVICE_STATUS]) {
            return deviceStatus[deviceId]
        }
    }

    /* CPU (inference) threads resource. **/
    @Synchronized private fun getAvailableThreadNum(): Int {
        //synchronized(locks[LOCK_ID_AVAILABLE_THREAD_NUM]) {
            return availableCPUThreadNum
        //}
    }

    @Synchronized private fun setAvailableThreadNum(num: Int) {
        //synchronized(locks[LOCK_ID_AVAILABLE_THREAD_NUM]) {
            availableCPUThreadNum = num
        //}
    }

    private fun getCPUThreadsResource(): Int {
        synchronized(locks[LOCK_ID_AVAILABLE_THREAD_NUM]) {
            return getAvailableThreadNum()
        }
    }

    private fun allocCPUThreadsResource(num: Int): Int {
        synchronized(locks[LOCK_ID_AVAILABLE_THREAD_NUM]) {
            return if (num <= getAvailableThreadNum()) {
                setAvailableThreadNum(getAvailableThreadNum() - num)
                RESULT_OK
            } else {
                Log.e(TAG, "Allocate cpu threads failed" +
                        " (cur=${getAvailableThreadNum()}, alloc=$num)")
                RESULT_FAILED
            }
        }
    }

    private fun freeCPUThreadsResource(num: Int): Int {
        synchronized(locks[LOCK_ID_AVAILABLE_THREAD_NUM]) {
            return if (getAvailableThreadNum() + num <= maxCpuInferenceThreadsNum) {
                setAvailableThreadNum(getAvailableThreadNum() + num)
                RESULT_OK
            } else {
                setAvailableThreadNum(maxCpuInferenceThreadsNum)
                Log.e(TAG, "Free cpu threads failed" +
                        " (cur=${getAvailableThreadNum()}, free=$num)")
                RESULT_FAILED
            }
        }
    }

    /* For constrain CPU threads number. **/
    private fun getCurCPUControlThreadNum(): Int {
        synchronized(locks[LOCK_ID_AVAILABLE_CONTROL_THREAD_NUM]) {
            return curCpuControlThreadsNum
        }
    }

    private fun addCPUControlThreadNum() {
        synchronized(locks[LOCK_ID_AVAILABLE_CONTROL_THREAD_NUM]) {
            curCpuControlThreadsNum++
        }
    }

    private fun subCPUControlThreawdNum() {
        synchronized(locks[LOCK_ID_AVAILABLE_CONTROL_THREAD_NUM]) {
            curCpuControlThreadsNum --
        }
    }

    /* Alloc CPU models resource. **/
    private fun allocCPUHourGlassModelRes(numThreads: Int)
            : ImageClassifierFloatInception? {
        synchronized(locks[LOCK_ID_MODELS]) {
            for (i in hourGlassCPUModels!![numThreads-1].indices) {
                if (!cpuModelsBusyStatusArray[numThreads-1][i]) {
                    cpuModelsBusyStatusArray[numThreads-1][i] = true
                    return hourGlassCPUModels!![numThreads-1][i]
                }
            }
            return null
        }

    }

    /* Free CPU models resource. **/
    private fun freeCPUHourGlassModelRes(
            numThreads: Int,
            model: ImageClassifierFloatInception) {
        synchronized(locks[LOCK_ID_MODELS]) {
            for (i in hourGlassCPUModels!![numThreads - 1].indices) {
                if (model == hourGlassCPUModels!![numThreads - 1][i]) {
                    cpuModelsBusyStatusArray[numThreads - 1][i] = false
                }
            }
        }
    }

    private fun gpuThreadTask() {
        val taskCount = tasksQueue.getAvailableTaskCount()
        var getItemCount = 1
        if (taskCount >= 4) {
            getItemCount = 2
        }

        // Get executable task items
        val items = tasksQueue.getExecutableTaskItems(getItemCount)
        if (items.size > 0) {
            setDeviceStatus(DEVICE_ID_GPU, DEVICE_STATUS_BUSY)
            Log.i(TAG, "GPU: Take $getItemCount task")
            for (item in items) {
                val bitmap = item.getBitmap()
                hourGlassGPUModels?.classifyFrame(bitmap!!)
                val pointArray = hourGlassGPUModels?.getCopyPointArray()
                val result = item.setPointArray(pointArray!!)
                // Print current used time
                if (result == PETaskWrapper.RESULT_FINISHED) {
                    taskUsedTime = System.currentTimeMillis() - taskStartTime
                    Log.i(TAG, "Frame finished at $taskUsedTime ms")
                }
            }
        }

        setDeviceStatus(DEVICE_ID_GPU, DEVICE_STATUS_FREE)
    }

    private fun runGPUThreadsIfNot() {
        if (getDeviceStatus(DEVICE_ID_GPU) == DEVICE_STATUS_BUSY)
            return

        if (tasksQueue.getAvailableTaskCount() == 0)
            return

        Log.i(TAG, "GPU: Start a new GPU thread")

        threadPool.submit {
            while (tasksQueue.getAvailableTaskCount() > 0) {
                gpuThreadTask()
            }
            setDeviceStatus(DEVICE_ID_GPU, DEVICE_STATUS_FREE)

            Log.i(TAG, "GPU: Find no tasks, exit")
        }
    }

    private fun classifyFrameCpuThreadTask(item: PEExecutableTaskItem, numThreads: Int) {
        val bitmap = item.getBitmap()
        if (bitmap != null) {
            val model = allocCPUHourGlassModelRes(numThreads)
            model?.classifyFrame(bitmap)
            val pointArray = model?.getCopyPointArray()
            //Log.i(TAG, "classifyFrameHNTN: Thread $i get copy point array OK")
            val result = item.setPointArray(pointArray!!)
            // Print current used time
            if (result == PETaskWrapper.RESULT_FINISHED) {
                taskUsedTime = System.currentTimeMillis() - taskStartTime
                Log.i(TAG, "Frame finished at $taskUsedTime ms")
            }
            freeCPUHourGlassModelRes(numThreads, model)
        }

        freeCPUThreadsResource(numThreads)

        runCPUControlThread()
    }

    private fun classifyFrameHNTN(items: ArrayList<PEExecutableTaskItem>, numThreads: Int) {
        val numHuman = items.size
        //var countDown = numHuman
        //val lock: Byte = 0

        //Log.i(TAG, "classifyFrameHNTN: Human $numHuman Thread $numThreads")

        for (i in 1 until numHuman) {
            //Log.i(TAG, "classifyFrameHNTN: Start a inference thread")
            threadPool.submit {
                classifyFrameCpuThreadTask(items[i], numThreads)
            }
        }

        classifyFrameCpuThreadTask(items[0], numThreads)

        //Log.i(TAG, "classifyFrameHNTN: Finished")
    }

    private fun classifyFrameH1TN(items: ArrayList<PEExecutableTaskItem>, numThreads: Int) {
        if (items.size != 1)
            return
        classifyFrameHNTN(items, numThreads)
    }

    private fun classifyFrameHNT1(items: ArrayList<PEExecutableTaskItem>) {
        classifyFrameHNTN(items, 1)
    }

    private fun classifyFrameH2T4(items: ArrayList<PEExecutableTaskItem>) {
        if (items.size != 2)
            return
        classifyFrameHNTN(items, 4)
    }

    private fun classifyFrameH2T3(items: ArrayList<PEExecutableTaskItem>) {
        if (items.size != 2)
            return

        val numHuman = items.size

        //Log.i(TAG, "classifyFrameHNTN: Human $numHuman Thread 3")

        for (i in 1 until numHuman) {
            threadPool.submit {
                classifyFrameCpuThreadTask(items[i], 1)
            }
        }

        classifyFrameCpuThreadTask(items[0], 2)
    }

    private fun classifyFrameH1T4(items: ArrayList<PEExecutableTaskItem>) {
        if (items.size != 1)
            return
        classifyFrameHNTN(items, 4)
    }

    private fun cpuThreadTask() {
        // Wait if gpu is free, let gpu take task first
        if (getDeviceStatus(DEVICE_ID_GPU) == DEVICE_STATUS_FREE) {
            Log.i(TAG, "CPU: GPU now is free, I can not take tasks")
            return
        }

        Log.i(TAG, "CPU: GPU now is busy, I can take tasks")

        // Check available task number
        val availableTaskCount = tasksQueue.getAvailableTaskCount()
        if (availableTaskCount == 0)
            return
        // Check available cpu thread resource
        val availableCPUThreadNum = getCPUThreadsResource()
        if (availableCPUThreadNum == 0)
            return

        Log.i(TAG, "CPU: Find $availableTaskCount task available," +
                " free thread num $availableCPUThreadNum")

        // Get and run task items
        if (availableTaskCount >= availableCPUThreadNum) {
            val usedThreadNum = availableCPUThreadNum
            val result = allocCPUThreadsResource(usedThreadNum)
            if (result == RESULT_FAILED) {
                return
            }

            val items = tasksQueue.getExecutableTaskItems(availableCPUThreadNum)
            // numHumanThreads=@availableCPUThreadNum, numTFLiteThreads=1
            classifyFrameHNT1(items)

            //freeCPUThreadsResource(usedThreadNum)
        } else {
            if (availableTaskCount == 1) {
                val usedThreadNum = availableCPUThreadNum
                val result = allocCPUThreadsResource(usedThreadNum)
                if (result == RESULT_FAILED) {
                    return
                }

                // numHumanThreads=1, numTFLiteThreads=@availableCPUThreadNum
                val items = tasksQueue.getExecutableTaskItems(1)
                classifyFrameH1TN(items, usedThreadNum)

                //freeCPUThreadsResource(usedThreadNum)
            } else if (availableTaskCount >= 2) {
                if (availableCPUThreadNum == 4) {
                    val usedThreadNum = 4
                    val result = allocCPUThreadsResource(usedThreadNum)
                    if (result == RESULT_FAILED) {
                        return
                    }

                    val items = tasksQueue.getExecutableTaskItems(2)

                    // numHumanThreads=2, numTFLiteThreads=2
                    classifyFrameH2T4(items)

                    //freeCPUThreadsResource(usedThreadNum)
                } else if (availableCPUThreadNum == 3) {
                    val usedThreadNum = 3
                    val result = allocCPUThreadsResource(usedThreadNum)
                    if (result == RESULT_FAILED) {
                        return
                    }

                    val items = tasksQueue.getExecutableTaskItems(2)

                    // One is numHumanThreads=1, numTFLiteThreads=2
                    // Another is Then numHumanThreads=1, numTFLiteThreads=1
                    classifyFrameH2T3(items)

                    //freeCPUThreadsResource(usedThreadNum)
                }
            } /*else if (availableTaskCount == 3) {
                // First numHumanThreads=2, numTFLiteThreads=2
                val usedThreadNum = 4
                var result = allocCPUThreadsResource(usedThreadNum)
                if (result == RESULT_FAILED) {
                    return
                }

                val items0 = tasksQueue.getExecutableTaskItems(2)
                classifyFrameH2T4(items0)

                // Bug: This situation is special.

                // Then numHumanThreads=1, numTFLiteThreads=4
                result = allocCPUThreadsResource(usedThreadNum)
                if (result == RESULT_FAILED) {
                    return
                }
                val items1 = tasksQueue.getExecutableTaskItems(1)
                classifyFrameH1T4(items1)

                //freeCPUThreadsResource(usedThreadNum)
            }*/
        }
    }

    private fun runCPUControlThread() {
        // Return when no available tasks
        if (tasksQueue.getAvailableTaskCount() == 0)
            return

        // Limit CPU control threads number
        val curCpuControlThreadsNum = getCurCPUControlThreadNum()
        if (curCpuControlThreadsNum >= maxCpuControlThreadsNum) {
            Log.i(TAG, "Can not create CPU control thread any more")
            return
        }

        Log.i(TAG, "CPU: Start a new thread " +
                "(id=$curCpuControlThreadsNum, res=${getCPUThreadsResource()})")

        // Add one
        addCPUControlThreadNum()

        // Start a CPU thread using thread pool
        threadPool.submit {
            while (tasksQueue.getAvailableTaskCount() > 0) {
                Log.i(TAG, "CPU: Start a thread task")
                setDeviceStatus(DEVICE_ID_CPU, DEVICE_STATUS_BUSY)
                cpuThreadTask()

                //if (getCurCPUControlThreadNum() > 1) {
                //    Log.i(TAG, "CPU: Since there are other CPU control threads, exit")
                //    break
                //}
            }

            // Sub one
            subCPUControlThreawdNum()

            val curCpuControlThreadsNum = getCurCPUControlThreadNum()
            Log.i(TAG, "CPU: Find no tasks, exit. (curCPUCtrlThreadNum=$curCpuControlThreadsNum)")
            if (curCpuControlThreadsNum == 0) {
                setDeviceStatus(DEVICE_ID_CPU, DEVICE_STATUS_FREE)
            }
        }
    }

    private fun runCPUThreadsIfNot() {
        // Return when CPU device is busy and no available thread resource
        if (getDeviceStatus(DEVICE_ID_CPU) == DEVICE_STATUS_BUSY
                && getCPUThreadsResource() == 0)
            return

        runCPUControlThread()
    }

    private fun runCPUGPUThreadsIfNot() {
        runGPUThreadsIfNot()
        runCPUThreadsIfNot()
    }

    private fun createTicket(): Long {
        return System.currentTimeMillis()
    }

    private fun run(bitmaps: ArrayList<Bitmap>): Long {
        // Reset task start time
        if (taskStartTime == -1L)
            setTaskStartTime(System.currentTimeMillis())

        Log.i(TAG, "Frame scheduled at ${System.currentTimeMillis() - taskStartTime} ms")

        // Wrap human pictures
        val taskWrapper = PETaskWrapper(bitmaps)
        // enqueue task wrapper
        tasksQueue.enqueue(taskWrapper)
        // Start CPU/GPU threads if not
        runCPUGPUThreadsIfNot()
        // Create and return a ticket value
        val ticket =  createTicket()
        ticketsQueue.add(ticket)

        return ticket
    }

    override fun scheduleAndRun(pictures: ArrayList<Bitmap>, mode: Int): Long {
        return run(pictures)
    }

    @Synchronized fun getOutputPointArrays(ticket: Long): ArrayList<Array<FloatArray>>? {
        // Check if ticket is right
        return if (ticket == ticketsQueue[0]) {
            // Get point arrays as result
            val taskWrapper = tasksQueue.getFirstItem()
            val pointArray = taskWrapper?.getPointArrays()
            // Dequeue task and ticket if result is not null
            if (pointArray != null) {
                tasksQueue.dequeue()
                ticketsQueue.removeAt(0)
            }

            pointArray
        } else {
            null
        }
    }

    override fun setTaskStartTime(time: Long) {
        this.taskStartTime = time
    }

    override fun getTaskCostTime(): Long {
        return this.taskUsedTime
    }

    override fun isAllTasksFinished(): Boolean {
        return !isDeviceBusy()
    }
}
