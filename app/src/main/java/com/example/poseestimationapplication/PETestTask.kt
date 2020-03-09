package com.example.poseestimationapplication

import android.app.Activity
import android.util.Log
import com.example.poseestimationapplication.peschedule.PETaskScheduler
import com.example.poseestimationapplication.peschedule.PETaskSchedulerMode
import com.example.poseestimationapplication.peschedulev2.PETaskSchedulerV2
import com.example.poseestimationapplication.tool.BitmapLoader

class PETestTask(activity: Activity) : Thread() {
    private val TAG = "PETestTask"
    private val mActivity: Activity = activity
    private var numPic = -1
    private var mode = PETaskSchedulerMode.MODE_CPU
    private var numThreads = -1
    private var cpuFp = PETaskScheduler.CPU_FP_32
    private var useGpuModelFp16 = false
    private var useGpuFp16 = false
    // 测试轮数
    private var testRound = 1
    // 测试的帧数
    private var testFrameCount = 20
    // 两帧之间的间隔时间 (ms)
    private var frameIntervalTime = 25

    override fun run() {
        super.run()

        for (i in 0 until testRound) {
            val costTime = testRound()
            Log.i(TAG, "Round $i CostTime $costTime ms")
        }
    }

    private fun testRound(): Long {
        // 创建PETaskScheduler
        val peTaskScheduler = if (mode == PETaskSchedulerMode.MODE_GREEDY)
                                  PETaskSchedulerV2(mActivity)
                              else
                                  PETaskScheduler(mActivity)
        // 初始化
        peTaskScheduler.init(192, numThreads, cpuFp, useGpuModelFp16, useGpuFp16)
        // 设置开始时间
        peTaskScheduler.setTaskStartTime(System.currentTimeMillis())

        for (i in 0 until testFrameCount) {
            // 确定human数量
            val numPicInternal: Int = if (numPic > 0) numPic
            else (Math.random() * 5).toInt() + 1
            Log.i(TAG, "Create $numPicInternal tasks")

            // 读取human图片
            val bitmaps = BitmapLoader.loadAssetsPictures(mActivity, numPicInternal)

            // Schedule and run
            Thread {
                peTaskScheduler.scheduleAndRun(bitmaps, mode)
            }.start()

            // 为下一帧而等待
            sleep(frameIntervalTime.toLong())
        }

        // 等待所有任务完成
        var waitCount = 0
        while (!peTaskScheduler.isAllTasksFinished()) {
            sleep(1000)
            waitCount ++
            if (waitCount % 10 == 0) {
                Log.i(TAG, "Wait 10 times for PE tasks")
            }
        }
        // 结束时关闭释放PETaskScheduler
        peTaskScheduler.close()

        // 返回总任务耗时
        return peTaskScheduler.getTaskCostTime()
    }

    fun test(round: Int, frames: Int, frameInterval: Int, numPic: Int,
             mode: Int, numThreads: Int,
             cpuFp: Int, useGpuModelFp16: Boolean, useGpuFp16: Boolean) {
        this.testRound = round
        this.testFrameCount = frames
        this.frameIntervalTime = frameInterval
        this.numPic = numPic
        this.mode = mode
        this.numThreads = numThreads
        this.cpuFp = cpuFp
        this.useGpuModelFp16 = useGpuModelFp16
        this.useGpuFp16 = useGpuFp16
        start()
    }
}
