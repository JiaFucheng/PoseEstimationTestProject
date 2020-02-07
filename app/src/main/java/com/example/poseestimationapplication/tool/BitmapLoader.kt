package com.example.poseestimationapplication.tool

import android.app.Activity
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory

class BitmapLoader {

    companion object {
        // 加载随机数据的图片
        public fun loadRandomDataPictures(num: Int, picWidth: Int, picHeight: Int): ArrayList<Bitmap> {
            val bmpArray: ArrayList<Bitmap> = ArrayList<Bitmap>()

            for (i in 0..num) {
                val bitmap: Bitmap = Bitmap.createBitmap(picWidth, picHeight, Bitmap.Config.ARGB_8888)
                bmpArray.add(bitmap)
            }

            return bmpArray
        }

        // 加载Assets目录中的图片
        public fun loadAssetsPictures(activity: Activity, num: Int): ArrayList<Bitmap> {
            val am: AssetManager = activity.assets

            val bitmapArray = ArrayList<Bitmap>()

            for (i in 0 until num) {
                val picNo = i + 1
                val fileName = "pose_$picNo.png"
                val inputStream = am.open(fileName)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                bitmapArray.add(bitmap)
            }

            return bitmapArray
        }
    }

}