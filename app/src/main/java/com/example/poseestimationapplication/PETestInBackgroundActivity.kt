package com.example.poseestimationapplication

import android.support.v7.app.AppCompatActivity
import android.os.Bundle

class PETestInBackgroundActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_petest_in_background)

        PETestTaskInBackground(this).startTest()
    }
}
