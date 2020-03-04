package com.example.poseestimationapplication.peschedule

class PointArraysQueue {
    private val queue = ArrayList<ArrayList<Array<FloatArray>>>()
    private val queueLock: Byte = 0

    fun enqueue(element: ArrayList<Array<FloatArray>>) {
        synchronized(queueLock) {
            queue.add(element)
        }
    }

    fun dequeue(): ArrayList<Array<FloatArray>>? {
        synchronized(queueLock) {
            if (queue.size > 0) {
                val element = queue[0]
                queue.removeAt(0)
                return element
            } else {
                return null
            }
        }
    }
}