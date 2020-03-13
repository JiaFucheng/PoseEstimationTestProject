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

        fun getScheduleModeName(mode: Int) : String {
            when (mode) {
                MODE_CPU -> { return "CPU" }
                MODE_GPU -> { return "GPU" }
                MODE_CPUGPU -> { return "CPU-GPU" }
                MODE_CPUGPU_WMA -> { return "CPU-GPU-WMA" }
                MODE_CPUGPU_MT -> { return "CPU-GPU-MT" }
                MODE_CPUGPU_MT_WMA -> { return "CPU-GPU-MT-WMA" }
                MODE_GREEDY -> { return "CPU-GPU-GREEDY" }
            }

            return "UNKNOWN"
        }
    }
}
