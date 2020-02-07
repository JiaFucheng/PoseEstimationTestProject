package com.example.poseestimationapplication

import android.app.Activity
import android.util.Log
import com.example.poseestimationapplication.peschedule.PETaskScheduler
import com.example.poseestimationapplication.tool.BitmapLoader

class PETestTask(activity: Activity) : Thread() {

    private val TAG = "PETestTask"

    private val mActivity: Activity = activity

    override fun run() {
        super.run()

        // 测试轮数
        val testRound = 10
        // 创建PETaskScheduler
        val peTaskScheduler = PETaskScheduler(mActivity)

        for (i in 0 until testRound) {
            // 随机确定1~5张图片数量
            val picNum = (Math.random() * 5).toInt() + 1
            Log.i(TAG, "Create $picNum tasks")
            // 读取图片
            val bitmaps = BitmapLoader.loadAssetsPictures(mActivity, picNum)
            // 调用PETaskScheduler进行调度和执行
            peTaskScheduler.scheduleAndRun(bitmaps)
        }

        // 结束时关闭释放PETaskScheduler
        peTaskScheduler.close()
    }
}
