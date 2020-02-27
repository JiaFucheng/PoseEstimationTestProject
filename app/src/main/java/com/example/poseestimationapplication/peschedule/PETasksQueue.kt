package com.example.poseestimationapplication.peschedule

import android.graphics.Bitmap

class PETasksQueue {
    private val tasksQueue = ArrayList<ArrayList<Bitmap>>()
    private val queueLock: Byte = 0

    public fun getFirstTasks(): ArrayList<Bitmap> {
        synchronized(queueLock) {
            return tasksQueue[0]
        }
    }

    public fun getTasksItem(index: Int): ArrayList<Bitmap> {
        synchronized(queueLock) {
            return tasksQueue[index]
        }
    }

    public fun getQueueSize(): Int {
        synchronized(queueLock) {
            return tasksQueue.size
        }
    }

    public fun getTotalTasksSize(): Int {
        var tasksSize = 0

        synchronized(queueLock) {
            for (queue in tasksQueue) {
                tasksSize += queue.size
            }
        }

        return tasksSize
    }

    public fun enqueueTasks(tasks: ArrayList<Bitmap>) {
        synchronized(queueLock) {
            tasksQueue.add(tasks)
        }
    }

    public fun dequeueFirstTasks() {
        synchronized(queueLock) {
            tasksQueue.removeAt(0)
        }
    }
}