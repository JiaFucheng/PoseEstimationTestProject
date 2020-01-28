package com.example.poseestimationapplication

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText

class MainActivity : AppCompatActivity() {

    private var mPicNumEditText: EditText ?= null
    private var mCPUTaskRatioEditText: EditText ?= null
    private var mTestRunButton: Button ?= null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initUI()
    }

    private fun initUI() {
        mPicNumEditText = findViewById(R.id.pic_num_edit_text)
        mCPUTaskRatioEditText = findViewById(R.id.cpu_task_num_edit_text)
        mTestRunButton = findViewById(R.id.test_run_button)

        mTestRunButton?.setOnClickListener {
            val picNum = mPicNumEditText?.editableText.toString().toInt()
            val cpuTaskNum = mCPUTaskRatioEditText?.editableText.toString().toInt()

            cpuGPUTaskRun(picNum, cpuTaskNum)
        }
    }

    private fun cpuGPUTaskRun(picNum: Int, cpuTaskNum: Int) {
        val task: CPUGPUTask ?= CPUGPUTask(this)
        task?.setPictureNumber(picNum)
        task?.setCPUPictureNumber(cpuTaskNum)
        task?.start()
    }
}
