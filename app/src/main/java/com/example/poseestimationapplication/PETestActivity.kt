package com.example.poseestimationapplication

import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.v7.app.AppCompatActivity
import android.widget.Button

import kotlinx.android.synthetic.main.activity_petest.*

class PETestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_petest)
        //setSupportActionBar(toolbar)

        initUI()
    }

    private fun initUI() {
        val testButton = findViewById<Button>(R.id.test_button)
        testButton.setOnClickListener {
            PETestTask(this).start()
        }
    }

}
