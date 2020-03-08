package com.example.poseestimationapplication.peschedule

class PETaskSchedulerMode {
    companion object {
        const val MODE_CPU = 0
        const val MODE_GPU = 1
        const val MODE_CPUGPU = 2
        const val MODE_CPUGPU_WMA = 3
        const val MODE_CPUGPU_MT = 4
        const val MODE_CPUGPU_MT_WMA = 5
        const val MODE_GREEDY = 6
    }
}