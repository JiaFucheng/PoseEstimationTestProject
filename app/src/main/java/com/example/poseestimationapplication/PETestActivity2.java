package com.example.poseestimationapplication;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;

import com.example.poseestimationapplication.peschedule.PETaskSchedulerWrapper;
import com.example.poseestimationapplication.tool.BitmapLoader;

import java.util.ArrayList;
import java.util.Arrays;

public class PETestActivity2 extends AppCompatActivity {

    private String TAG = "PETestActivity2";

    private PETaskSchedulerWrapper mPETaskSchedulerWrapper;

    private int frameCount = 0;
    private long lastFrameTime = 0;

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

        // 创建Scheduler
        mPETaskSchedulerWrapper = new PETaskSchedulerWrapper(this);

        int testFrameCount = 100;
        for (int i = 0; i < testFrameCount; i ++) {
            new Thread() {
                @Override
                public void run() {
                    super.run();
                    callTaskPESchedulerExample();
                }
            }.start();

            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        lastFrameTime = System.currentTimeMillis();
    }

    // 调用Scheduler
    private void callTaskPESchedulerExample() {
        // 假设bitmaps是框出的human的图片
        // 假设图片数量是5，图片大小是192x192
        ArrayList<Bitmap> bitmaps = BitmapLoader.Companion.loadAssetsPictures(this, 5);

        // 调用Scheduler进行调度和执行任务
        ArrayList<float[][]> pointArrays = mPETaskSchedulerWrapper.run(bitmaps);

        // 打印Point Arrays的数量，应该是5个
        Log.i(TAG, "Get point arrays size " + pointArrays.size());

        // 对获取得到的Point Arrays进行处理
        // pointArrays[图片下标,2个x/y坐标,14个点]
        for (int picIndex = 0; picIndex < pointArrays.size(); picIndex ++) {
            if (pointArrays.get(picIndex) != null) {
                for (int i = 0; i < 2; i++) {
                    for (int j = 0; j < 14; j++) {
                        float value = pointArrays.get(picIndex)[i][j];
                    }
                }
                Log.i(TAG, "Array " + picIndex + ": " + Arrays.deepToString(pointArrays.get(picIndex)));
            } else {
                Log.i(TAG, "Array " + picIndex + " is null");
            }
        }

        Log.i(TAG,
                "Frame Count " + frameCount
                + " Time " + (System.currentTimeMillis() - lastFrameTime) + " ms");
        frameCount ++;
        lastFrameTime = System.currentTimeMillis();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // 关闭Scheduler
        mPETaskSchedulerWrapper.close();
    }
}
