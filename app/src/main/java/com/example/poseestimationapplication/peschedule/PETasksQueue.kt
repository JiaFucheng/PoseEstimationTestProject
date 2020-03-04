package com.example.poseestimationapplication.peschedule

import android.graphics.Bitmap

class PETasksQueue {
    private val tasksQueue = ArrayList<ArrayList<Bitmap>>()
    private val queueLock: Byte = 0

    fun getFirstTasksItem(): ArrayList<Bitmap>? {
        synchronized(queueLock) {
            if (tasksQueue.size > 0)
                return tasksQueue[0]
            else
                return null
        }
    }

    fun getTasksItem(index: Int): ArrayList<Bitmap>? {
        synchronized(queueLock) {
            if (index < tasksQueue.size)
                return tasksQueue[index]
            else
                return null
        }
    }

    fun getQueueSize(): Int {
        synchronized(queueLock) {
            return tasksQueue.size
        }
    }

    fun getTotalTasksSize(): Int {
        var tasksSize = 0

        synchronized(queueLock) {
            for (queue in tasksQueue) {
                tasksSize += queue.size
            }
        }

        return tasksSize
    }

    fun enqueue(tasks: ArrayList<Bitmap>) {
        synchronized(queueLock) {
            tasksQueue.add(tasks)
        }
    }

    fun dequeue() {
        synchronized(queueLock) {
            if (tasksQueue.size > 0)
                tasksQueue.removeAt(0)
        }
    }
}