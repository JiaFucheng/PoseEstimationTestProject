package com.example.poseestimationapplication.peschedulev2

import android.util.Log

class PETasksQueue {

    private val TAG = "PETasksQueue"

    private val queue = ArrayList<PETaskWrapper>()
    private val syncLock: Byte = 0
    private var locked: Boolean = false

    fun isLocked(): Boolean {
        synchronized(syncLock) {
            return locked
        }
    }

    fun tryLock(): Int {
        synchronized(syncLock) {
            return if (!locked) {
                locked = true
                ResultValue.OK
            } else {
                ResultValue.FAILED
            }
        }
    }

    fun unlock() {
        synchronized(syncLock) {
            if (locked)
                locked = false
        }
    }

    fun enqueue(item: PETaskWrapper) {
        synchronized(syncLock) {
            queue.add(item)
        }
    }

    fun getAvailableTaskCount(): Int {
        synchronized(syncLock) {
            var count = 0

            for (task in queue) {
                count += task.getAvailableTaskCount()
            }

            Log.i(TAG, "$count tasks are available")

            return count
        }
    }

    fun getExecutableTaskItems(num: Int): ArrayList<PEExecutableTaskItem> {
        synchronized(syncLock) {
            val items = ArrayList<PEExecutableTaskItem>()

            for (q in queue.indices) {
                val task = queue[q]
                var i = 0
                while (i != -1) {
                    i = task.getAvailableTaskIndex()
                    if (i != -1) {
                        items.add(PEExecutableTaskItem(task, i))
                        Log.i(TAG, "Get executable task [$q,$i]")
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
        synchronized(syncLock) {
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
        synchronized(syncLock) {
            return if (queue.size > 0) {
                queue[0]
            } else {
                null
            }
        }
    }

    fun checkAllTasksFinished(): Boolean {
        var result = true
        for (i in queue.indices) {
            val finished = queue[i].checkFinished()
            if (!finished) {
                Log.i(TAG, "Task $i in queue is unfinished")
                result = false
            }
        }
        return result
    }
}
