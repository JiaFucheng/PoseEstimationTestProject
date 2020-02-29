package com.example.poseestimationapplication.peschedule

import android.util.Log

class PETaskCallback {

    private val TAG = "PETaskCallback"

    public fun call(pointArrays : ArrayList<Array<FloatArray>>) {
        Log.i(TAG, "Process point array in PE task callback")

        for (pointArray in pointArrays) {
            // TODO: Process point array here
        }
    }

}