package com.example.poseestimationapplication.peschedulev2

import android.graphics.Bitmap

class PEExecutableTaskItem(
        private val task: PETaskWrapper,
        private val itemIndex: Int) {

    fun getBitmap(): Bitmap? {
        return task.getBitmap(itemIndex)
    }

    fun setPointArray(array: Array<FloatArray>): Int {
        return task.setPointArray(itemIndex, array)
    }
}
