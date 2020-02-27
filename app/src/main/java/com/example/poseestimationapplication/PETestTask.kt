package com.example.poseestimationapplication

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.example.poseestimationapplication.peschedule.PETaskScheduler
import com.example.poseestimationapplication.tool.BitmapLoader

class PETestTask(activity: Activity) : Thread() {
    private val TAG = "PETestTask"
    private val mActivity: Activity = activity
    private var numPic = -1
    private var mode = PETaskScheduler.MODE_CPU
    private var numThreads = -1
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
        val peTaskScheduler = PETaskScheduler(mActivity)
        // 初始化
        peTaskScheduler.init(numThreads, useGpuFp16)
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
        while (!peTaskScheduler.isAllTasksFinished()) {
            sleep(1000)
        }
        // 结束时关闭释放PETaskScheduler
        peTaskScheduler.close()

        // 返回总任务耗时
        return peTaskScheduler.getTaskCostTime()
    }

    public fun test(round: Int, frames: Int, frameInterval: Int, numPic: Int,
                    mode: Int,  numThreads: Int, useGpuFp16: Boolean) {
        this.testRound = round
        this.testFrameCount = frames
        this.frameIntervalTime = frameInterval
        this.numPic = numPic
        this.mode = mode
        this.numThreads = numThreads
        this.useGpuFp16 = useGpuFp16
        start()
    }
}
