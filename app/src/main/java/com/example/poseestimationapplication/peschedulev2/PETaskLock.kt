package com.example.poseestimationapplication.peschedulev2

class PETaskLock {

    private var mLockValue: Boolean = false
    private val mLock: Byte = 0

    fun tryLock(): Int {
        synchronized(mLock) {
            return if (!mLockValue) {
                mLockValue = true
                ResultValue.OK
            } else {
                ResultValue.FAILED
            }
        }
    }

    fun unlock() {
        synchronized(mLock) {
            if (mLockValue)
                mLockValue = false
        }
    }

}