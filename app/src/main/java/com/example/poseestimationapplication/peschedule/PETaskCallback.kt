package com.example.poseestimationapplication.peschedule

import android.util.Log

class PETaskCallback {

    private val TAG = "PETaskCallback"

    public fun call(heatMaps : ArrayList<Array<Array<Array<FloatArray>>>>) {
        Log.i(TAG, "Process heat maps in PE task callback")

        for (heatMap in heatMaps) {
            // TODO: Process heat maps here
        }
    }

}