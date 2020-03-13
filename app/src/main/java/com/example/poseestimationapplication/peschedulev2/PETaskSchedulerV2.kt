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

        private const val DEVICE_STATUS_OFF = 0
        private const val DEVICE_STATUS_ON = 1
        private const val DEVICE_STATUS_RUN = 2

        private const val LOCK_ID_DEVICE_STATUS = 0
        private const val LOCK_ID_AVAILABLE_THREAD_NUM = 1
        private const val LOCK_ID_AVAILABLE_CONTROL_THREAD_NUM = 2
        private const val LOCK_ID_MODELS = 3
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
    private val controlThreadLock = PETaskLock()
    private val inferenceThreadLock = PETaskLock()

    private var taskStartTime: Long = -1L
    private var taskUsedTime: Long = 0

    private fun initDeviceStatus() {
        setDeviceStatus(DEVICE_ID_CPU, DEVICE_STATUS_OFF)
        setDeviceStatus(DEVICE_ID_GPU, DEVICE_STATUS_OFF)
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

        return (getDeviceStatus(DEVICE_ID_CPU) == DEVICE_STATUS_ON
                || getDeviceStatus(DEVICE_ID_GPU) == DEVICE_STATUS_ON
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
                ResultValue.OK
            } else {
                Log.e(TAG, "Allocate cpu threads failed" +
                        " (cur=${getAvailableThreadNum()}, alloc=$num)")
                ResultValue.FAILED
            }
        }
    }

    private fun freeCPUThreadsResource(num: Int): Int {
        synchronized(locks[LOCK_ID_AVAILABLE_THREAD_NUM]) {
            return if (getAvailableThreadNum() + num <= maxCpuInferenceThreadsNum) {
                setAvailableThreadNum(getAvailableThreadNum() + num)
                ResultValue.OK
            } else {
                //setAvailableThreadNum(maxCpuInferenceThreadsNum)
                Log.e(TAG, "Free cpu threads failed" +
                        " (cur=${getAvailableThreadNum()}, free=$num)")
                ResultValue.FAILED
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

    private fun subCPUControlThreadNum() {
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

    private fun checkCPUHourGlassModelRes(numThreads: Int, needCount: Int): Boolean {
        synchronized(locks[LOCK_ID_MODELS]) {
            var freeResCount = 0
            for (i in hourGlassCPUModels!![numThreads - 1].indices) {
                if (!cpuModelsBusyStatusArray[numThreads-1][i]) {
                    freeResCount ++
                }
            }

            return (freeResCount >= needCount)
        }
    }

    /** GPU Thread Task. */
    private fun gpuThreadTask() {
        if (tasksQueue.tryLock() != ResultValue.OK)
            return

        val taskCount = tasksQueue.getAvailableTaskCount()
        if (taskCount == 0) {
            tasksQueue.unlock()
            return
        }

        var getItemCount = 1
        if (taskCount >= 4) {
            getItemCount = 2
        }

        // Get executable task items
        val items = tasksQueue.getExecutableTaskItems(getItemCount)
        tasksQueue.unlock()
        if (items.size > 0) {
            setDeviceStatus(DEVICE_ID_GPU, DEVICE_STATUS_RUN)
            Log.i(TAG, "GPU: Take $getItemCount task got ${items.size} task")
            for (i in items.indices) {
                val item = items[i]
                val bitmap = item.getBitmap()
                hourGlassGPUModels?.classifyFrame(bitmap)
                val pointArray = hourGlassGPUModels?.getCopyPointArray()
                if (pointArray != null) {
                    val result = item.setPointArray(pointArray)
                    //Log.i(TAG, "GPU: Set point array")
                    // Print current used time
                    if (result == PETaskWrapper.RESULT_FINISHED) {
                        taskUsedTime = System.currentTimeMillis() - taskStartTime
                        Log.i(TAG, "Frame finished at $taskUsedTime ms")
                    }
                } else {
                    Log.e(TAG, "GPU: Get null point array")
                }
            }
        }

        setDeviceStatus(DEVICE_ID_GPU, DEVICE_STATUS_ON)
    }

    private fun runGPUThreadsIfNot() {
        if (getDeviceStatus(DEVICE_ID_GPU) == DEVICE_STATUS_ON)
            return

        if (tasksQueue.getAvailableTaskCount() == 0)
            return

        Log.i(TAG, "GPU: Start a new GPU thread")

        threadPool.submit {
            while (tasksQueue.getAvailableTaskCount() > 0) {
                gpuThreadTask()
            }
            setDeviceStatus(DEVICE_ID_GPU, DEVICE_STATUS_OFF)

            Log.i(TAG, "GPU: Find no tasks, exit")
        }
    }

    private fun classifyFrameCpuThreadTask(
            item: PEExecutableTaskItem,
            model: ImageClassifierFloatInception,
            numThreads: Int) {
        val bitmap = item.getBitmap()
        //val model = allocCPUHourGlassModelRes(numThreads)
        //if (model != null) {
        model.classifyFrame(bitmap)
        val pointArray = model.getCopyPointArray()
        //Log.i(TAG, "classifyFrameHNTN: Thread $i get copy point array OK")
        //Log.i(TAG, "CPU: Set point array")
        val result = item.setPointArray(pointArray)
        // Print current used time
        if (result == PETaskWrapper.RESULT_FINISHED) {
            taskUsedTime = System.currentTimeMillis() - taskStartTime
            Log.i(TAG, "Frame finished at $taskUsedTime ms")
        }
        freeCPUHourGlassModelRes(numThreads, model)
        //} else {
        //    Log.e(TAG, "CPU: No model resource (numThreads=$numThreads)")
        //}

        freeCPUThreadsResource(numThreads)

        // May be too aggressive?
        //runCPUControlThread()
    }

    private fun classifyFrameHNTN(
            items: ArrayList<PEExecutableTaskItem>,
            models: ArrayList<ImageClassifierFloatInception>,
            numThreads: Int) {
        val numHuman = items.size
        //Log.i(TAG, "classifyFrameHNTN: Human $numHuman Thread $numThreads")

        for (i in 1 until numHuman) {
            //Log.i(TAG, "classifyFrameHNTN: Start a inference thread")
            threadPool.submit {
                classifyFrameCpuThreadTask(items[i], models[i], numThreads)
            }
        }

        classifyFrameCpuThreadTask(items[0], models[0], numThreads)

        //Log.i(TAG, "classifyFrameHNTN: Finished")
    }

    private fun classifyFrameH1TN(
            items: ArrayList<PEExecutableTaskItem>,
            models: ArrayList<ImageClassifierFloatInception>,
            numThreads: Int) {
        //if (items.size != 1) {
        //    freeCPUThreadsResource(numThreads)
        //    return
        //}
        classifyFrameHNTN(items, models, numThreads)
    }

    private fun classifyFrameHNT1(
            items: ArrayList<PEExecutableTaskItem>,
            models: ArrayList<ImageClassifierFloatInception>) {
        //if (items.size <= 0) {
        //    freeCPUThreadsResource(1)
        //    return
        //}
        classifyFrameHNTN(items, models, 1)
    }

    private fun classifyFrameH2T4(
            items: ArrayList<PEExecutableTaskItem>,
            models: ArrayList<ImageClassifierFloatInception>) {
        //if (items.size != 2) {
        //    freeCPUThreadsResource(4)
        //    return
        //}
        classifyFrameHNTN(items, models, 2)
    }

    private fun classifyFrameH2T3(
            items: ArrayList<PEExecutableTaskItem>,
            models: ArrayList<ImageClassifierFloatInception>) {
        //if (items.size != 2) {
        //    freeCPUThreadsResource(3)
        //    return
        //}

        val numHuman = items.size

        //Log.i(TAG, "classifyFrameHNTN: Human $numHuman Thread 3")

        for (i in 1 until numHuman) {
            threadPool.submit {
                classifyFrameCpuThreadTask(items[i], models[i], 1)
            }
        }

        classifyFrameCpuThreadTask(items[0], models[0],2)
    }

    private fun classifyFrameH1T4(
            items: ArrayList<PEExecutableTaskItem>,
            models: ArrayList<ImageClassifierFloatInception>) {
        //if (items.size != 1)
        //    return
        classifyFrameHNTN(items, models, 4)
    }

    /** CPU Thread Task */
    private fun cpuThreadTask(): Int {
        // Wait if gpu is free, let gpu take task first
        if (getDeviceStatus(DEVICE_ID_GPU) == DEVICE_STATUS_ON) {
            //Log.i(TAG, "CPU: GPU now is free, I can not take tasks")
            return ResultValue.OK
        }

        //Log.i(TAG, "CPU: GPU now is busy, I can take tasks")

        // Lock task queue and inference thread allocation
        if (tasksQueue.tryLock() != ResultValue.OK)
            return ResultValue.FAILED
        if (inferenceThreadLock.tryLock() != ResultValue.OK) {
            tasksQueue.unlock()
            return ResultValue.FAILED
        }

        // Check available task number
        val availableTaskCount = tasksQueue.getAvailableTaskCount()
        if (availableTaskCount == 0) {
            inferenceThreadLock.unlock()
            tasksQueue.unlock()
            return ResultValue.FAILED
        }

        // Check available cpu thread resource
        val availableCPUThreadNum = getCPUThreadsResource()
        if (availableCPUThreadNum == 0) {
            inferenceThreadLock.unlock()
            tasksQueue.unlock()
            return ResultValue.FAILED
        }

        Log.i(TAG, "CPU: Find $availableTaskCount task available," +
                " free thread num $availableCPUThreadNum")

        // Get and run task items
        if (availableTaskCount >= availableCPUThreadNum) {
            val usedThreadNum = availableCPUThreadNum
            val result = allocCPUThreadsResource(usedThreadNum)
            if (result == ResultValue.FAILED) {
                inferenceThreadLock.unlock()
                tasksQueue.unlock()
                return ResultValue.FAILED
            }

            val modelResChecked = checkCPUHourGlassModelRes(1, usedThreadNum)
            if (!modelResChecked) {
                inferenceThreadLock.unlock()
                tasksQueue.unlock()
                return ResultValue.FAILED
            }

            val models = ArrayList<ImageClassifierFloatInception>()
            for (i in 0 until usedThreadNum) {
                val model = allocCPUHourGlassModelRes(1)
                if (model != null)
                    models.add(model)
            }

            val items = tasksQueue.getExecutableTaskItems(availableCPUThreadNum)
            if (items.size != availableCPUThreadNum) {
                Log.e(TAG, "Wrong items count (wanted $availableCPUThreadNum but got ${items.size})")
            }
            inferenceThreadLock.unlock()
            tasksQueue.unlock()
            // numHumanThreads=@availableCPUThreadNum, numTFLiteThreads=1
            classifyFrameHNT1(items, models)
        } else {
            if (availableTaskCount == 1) {
                val usedThreadNum = availableCPUThreadNum
                val result = allocCPUThreadsResource(usedThreadNum)
                if (result == ResultValue.FAILED) {
                    inferenceThreadLock.unlock()
                    tasksQueue.unlock()
                    return ResultValue.FAILED
                }

                val modelResChecked = checkCPUHourGlassModelRes(usedThreadNum, 1)
                if (!modelResChecked) {
                    inferenceThreadLock.unlock()
                    tasksQueue.unlock()
                    return ResultValue.FAILED
                }

                val models = ArrayList<ImageClassifierFloatInception>()
                val model = allocCPUHourGlassModelRes(usedThreadNum)
                if (model != null)
                    models.add(model)

                // numHumanThreads=1, numTFLiteThreads=@availableCPUThreadNum
                val items = tasksQueue.getExecutableTaskItems(1)
                if (items.size != 1) {
                    Log.e(TAG, "Wrong items count (wanted 1 but got ${items.size})")
                }
                inferenceThreadLock.unlock()
                tasksQueue.unlock()
                classifyFrameH1TN(items, models, usedThreadNum)
            } else if (availableTaskCount >= 2) {
                if (availableCPUThreadNum == 4) {
                    val usedThreadNum = 4
                    val result = allocCPUThreadsResource(usedThreadNum)
                    if (result == ResultValue.FAILED) {
                        inferenceThreadLock.unlock()
                        tasksQueue.unlock()
                        return ResultValue.FAILED
                    }

                    val modelResChecked = checkCPUHourGlassModelRes(2, 2)
                    if (!modelResChecked) {
                        inferenceThreadLock.unlock()
                        tasksQueue.unlock()
                        return ResultValue.FAILED
                    }

                    val models = ArrayList<ImageClassifierFloatInception>()
                    for (i in 0 until 2) {
                        val model = allocCPUHourGlassModelRes(2)
                        if (model != null)
                            models.add(model)
                    }

                    val items = tasksQueue.getExecutableTaskItems(2)
                    if (items.size != 2) {
                        Log.e(TAG, "Wrong items count (wanted 2 but got ${items.size})")
                    }
                    inferenceThreadLock.unlock()
                    tasksQueue.unlock()

                    // numHumanThreads=2, numTFLiteThreads=2
                    classifyFrameH2T4(items, models)
                } else if (availableCPUThreadNum == 3) {
                    val usedThreadNum = 3
                    val result = allocCPUThreadsResource(usedThreadNum)
                    if (result == ResultValue.FAILED) {
                        inferenceThreadLock.unlock()
                        tasksQueue.unlock()
                        return ResultValue.FAILED
                    }

                    val modelResChecked0 = checkCPUHourGlassModelRes(2, 1)
                    val modelResChecked1 = checkCPUHourGlassModelRes(1, 1)
                    if (!(modelResChecked0 && modelResChecked1)) {
                        inferenceThreadLock.unlock()
                        tasksQueue.unlock()
                        return ResultValue.FAILED
                    }

                    val models = ArrayList<ImageClassifierFloatInception>()
                    var model = allocCPUHourGlassModelRes(2)
                    if (model != null)
                        models.add(model)
                    model = allocCPUHourGlassModelRes(1)
                    if (model != null)
                        models.add(model)

                    val items = tasksQueue.getExecutableTaskItems(2)
                    if (items.size != 2) {
                        Log.e(TAG, "Wrong items count (wanted 2 but got ${items.size})")
                    }
                    inferenceThreadLock.unlock()
                    tasksQueue.unlock()

                    // One is numHumanThreads=1, numTFLiteThreads=2
                    // Another is Then numHumanThreads=1, numTFLiteThreads=1
                    classifyFrameH2T3(items, models)
                }
            }
        }

        return ResultValue.OK
    }

    private fun runCPUControlThread() {
        // Return when no available tasks
        if (tasksQueue.getAvailableTaskCount() == 0)
            return

        // Try to lock
        if (controlThreadLock.tryLock() != ResultValue.OK)
            return

        // Limit CPU control threads number
        val curCpuControlThreadsNum = getCurCPUControlThreadNum()
        if (curCpuControlThreadsNum >= maxCpuControlThreadsNum) {
            Log.i(TAG, "Can not create CPU control thread any more")
            controlThreadLock.unlock()
            return
        }

        Log.i(TAG, "CPU: Start a new control thread " +
                "(id=$curCpuControlThreadsNum, res=${getCPUThreadsResource()})")

        // Add one
        addCPUControlThreadNum()

        // Start a CPU thread using thread pool
        threadPool.submit {
            while (tasksQueue.getAvailableTaskCount() > 0) {
                Log.i(TAG, "CPU: Start a thread task")
                setDeviceStatus(DEVICE_ID_CPU, DEVICE_STATUS_ON)
                if (cpuThreadTask() != ResultValue.OK)
                    break

                // Exit when more than 1 control thread
                //if (getCurCPUControlThreadNum() > 1) {
                    //Log.i(TAG, "CPU: Since there are other CPU control threads, exit")
                    //break
                //}
            }

            // Sub one
            subCPUControlThreadNum()

            val curCpuControlThreadsNum = getCurCPUControlThreadNum()
            Log.i(TAG, "CPU: Find no tasks, control thread exit. (curCPUCtrlThreadNum=$curCpuControlThreadsNum)")
            if (curCpuControlThreadsNum == 0) {
                setDeviceStatus(DEVICE_ID_CPU, DEVICE_STATUS_OFF)
            }
        }

        // Unlock
        controlThreadLock.unlock()
    }

    private fun runCPUThreadsIfNot() {
        // Return when CPU device is busy and no available thread resource
        if (getDeviceStatus(DEVICE_ID_CPU) == DEVICE_STATUS_ON
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
        val isDeviceBusy = isDeviceBusy()
        val isTaskInQueue = (tasksQueue.getAvailableTaskCount() > 0)
        //val allTasksFinished = tasksQueue.checkAllTasksFinished()
        val allTasksFinished = true

        return (allTasksFinished && !isTaskInQueue && !isDeviceBusy)
    }
}
