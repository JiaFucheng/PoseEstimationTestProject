package com.example.poseestimationapplication

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup

class MainActivity : AppCompatActivity() {

    private var mPicNumEditText: EditText ?= null
    private var mCPUTaskRatioEditText: EditText ?= null
    private var mTestRunButton: Button ?= null
    private var mGPUFPRadioGroup: RadioGroup ?= null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initUI()
    }

    private fun initUI() {
        mPicNumEditText = findViewById(R.id.pic_num_edit_text)
        mCPUTaskRatioEditText = findViewById(R.id.cpu_task_num_edit_text)
        mTestRunButton = findViewById(R.id.test_run_button)
        mGPUFPRadioGroup = findViewById(R.id.gpu_fp_radio_group)

        mTestRunButton?.setOnClickListener {
            // 获得输入参数
            val picNum = mPicNumEditText?.editableText.toString().toInt()
            val cpuTaskNum = mCPUTaskRatioEditText?.editableText.toString().toInt()
            var checkedId = 0
            for (i in 0 until mGPUFPRadioGroup!!.childCount) {
                val rb : RadioButton = mGPUFPRadioGroup?.getChildAt(i) as RadioButton
                if (rb.isChecked) {
                    checkedId = i
                }
            }

            var useGpuFp16 = false
            if (checkedId == 1)
                useGpuFp16 = true

            // 运行测试任务
            cpuGPUTaskRun(picNum, cpuTaskNum, useGpuFp16)
        }
    }

    private fun cpuGPUTaskRun(picNum: Int, cpuTaskNum: Int, useGpuFp16: Boolean) {
        // 开始任务线程
        val task: CPUGPUTask ?= CPUGPUTask(this)
        task?.setPictureNumber(picNum)
        task?.setCPUPictureNumber(cpuTaskNum)
        task?.setUseGpuFp16(useGpuFp16)
        task?.start()
    }
}
