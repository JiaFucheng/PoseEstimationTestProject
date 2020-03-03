package com.example.poseestimationapplication;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;

import com.example.poseestimationapplication.peschedule.PETaskScheduler;
import com.example.poseestimationapplication.tool.BitmapLoader;

import java.util.ArrayList;
import java.util.Arrays;

public class PETestActivity2 extends AppCompatActivity {

    private String TAG = "PETestActivity2";

    private PETaskScheduler mPETaskScheduler = new PETaskScheduler(this);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_petest2);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
            }
        });

        initTaskPEScheduler();

        for (int i = 0; i < 5; i ++) {
            new Thread() {
                @Override
                public void run() {
                    super.run();
                    callTaskPEScheduler();
                }
            }.start();

            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    // 初始化Scheduler
    private void initTaskPEScheduler() {
        int inputSize = 192;
        int numThreads = 4;
        boolean useCpuFp8 = false;
        boolean useCpuFp16 = false;
        boolean useGpuModelFp16 = false;
        boolean useGpuFp16 = false;
        mPETaskScheduler.init(inputSize, numThreads, useCpuFp8, useCpuFp16, useGpuModelFp16, useGpuFp16);
    }

    // 调用Scheduler
    private void callTaskPEScheduler() {
        // 假设bitmaps是框出的human的图片
        // 假设图片数量是5，图片大小是192x192
        ArrayList<Bitmap> bitmaps = BitmapLoader.Companion.loadRandomDataPictures(5, 192, 192);

        // 调用Scheduler进行调度和执行Human Bitmaps
       long ticket = mPETaskScheduler.scheduleAndRun(bitmaps, PETaskScheduler.MODE_CPUGPU_WMA);

        ArrayList<float[][]> pointArrays = null;

        // 当获取pointArrays为null时，说明scheduler中的任务还没有完成
        // 因此需要while循环等待直到pointArrays不为null
        while ((pointArrays = mPETaskScheduler.getOutputPointArrays(ticket)) == null);

        Log.i(TAG, "Get point arrays size " + pointArrays.size()
                + " ticket " + ticket);

        // 对获取得到的pointArrays进行处理
        // pointArrays[图片下标,2个x/y坐标,14个点]
        // TODO(xiaohui): Process point arrays here
        for (int picIndex = 0; picIndex < pointArrays.size(); picIndex ++) {
            if (pointArrays.get(picIndex) != null) {
                for (int i = 0; i < 2; i++) {
                    for (int j = 0; j < 14; j++) {
                        float value = pointArrays.get(picIndex)[i][j];
                    }
                }
                Log.i(TAG, "Array " + picIndex + ": " + Arrays.deepToString(pointArrays.get(picIndex)));
            } else {
                Log.i(TAG, "Arrays " + picIndex + " is null");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // 关闭Scheduler
        mPETaskScheduler.close();
    }
}
