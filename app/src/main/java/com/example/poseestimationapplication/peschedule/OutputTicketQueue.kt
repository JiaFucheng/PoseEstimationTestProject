package com.example.poseestimationapplication.peschedule

class OutputTicketQueue {
    private val tickets = ArrayList<Long>()
    private val lock: Byte = 0

    fun enqueue(t: Long) {
        synchronized(lock) {
            tickets.add(t)
        }
    }

    fun getFirstTicket(): Long {
        synchronized(lock) {
            return if (tickets.size > 0) {
                tickets[0]
            } else {
                -1
            }
        }
    }

    fun dequeue() {
        synchronized(lock) {
            if (tickets.size > 0) {
                tickets.removeAt(0)
            }
        }
    }
}