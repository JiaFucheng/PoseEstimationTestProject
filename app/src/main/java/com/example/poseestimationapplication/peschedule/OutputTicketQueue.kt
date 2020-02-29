package com.example.poseestimationapplication.peschedule

class OutputTicketQueue {

    private val tickets = ArrayList<Long>()
    private val lock: Byte = 0

    public fun enqueue(t: Long) {
        synchronized(lock) {
            tickets.add(t)
        }
    }

    public fun getFirstTicket(): Long {
        synchronized(lock) {
            if (tickets.size > 0) {
                return tickets[0]
            } else {
                return -1
            }
        }
    }

    public fun dequeueFirstTicket() {
        synchronized(lock) {
            if (tickets.size > 0) {
                tickets.removeAt(0)
            }
        }
    }

}