package com.example.poseestimationapplication.peschedulev2

import android.util.Log

class PETasksQueue {

    private val TAG = "PETasksQueue"

    private val queue = ArrayList<PETaskWrapper>()
    private val lock: Byte = 0

    fun enqueue(item: PETaskWrapper) {
        synchronized(lock) {
            queue.add(item)
        }
    }

    fun getAvailableTaskCount(): Int {
        synchronized(lock) {
            var count = 0

            for (task in queue) {
                count += task.getAvailableTaskCount()
            }

            Log.i(TAG, "$count tasks are available")

            return count
        }
    }

    fun getExecutableTaskItems(num: Int): ArrayList<PEExecutableTaskItem> {
        synchronized(lock) {
            val items = ArrayList<PEExecutableTaskItem>()

            for (task in queue) {
                var i = 0
                while (i != -1) {
                    i = task.getAvailableTaskIndex()
                    if (i != -1) {
                        items.add(PEExecutableTaskItem(task, i))
                        if (items.size == num) {
                            break
                        }
                    }
                }

                if (items.size == num) {
                    break
                }
            }

            return items
        }
    }

    fun dequeue(): PETaskWrapper? {
        synchronized(lock) {
            return if (queue.size > 0) {
                val task = queue[0]
                queue.removeAt(0)
                task
            } else {
                null
            }
        }
    }

    fun getFirstItem(): PETaskWrapper? {
        synchronized(lock) {
            return if (queue.size > 0) {
                queue[0]
            } else {
                null
            }
        }
    }
}
