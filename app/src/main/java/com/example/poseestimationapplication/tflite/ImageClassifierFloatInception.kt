package com.example.poseestimationapplication.tflite

import android.app.Activity
import android.util.Log
import java.util.*

/**
 * Pose Estimator
 */
class ImageClassifierFloatInception private constructor(
        activity: Activity,
        imageSizeX: Int,
        imageSizeY: Int,
        private val outputW: Int,
        private val outputH: Int,
        modelPath: String,
        numBytesPerChannel: Int = 4 // a 32bit float value requires 4 bytes
) : ImageClassifier(activity, imageSizeX, imageSizeY, modelPath, numBytesPerChannel) {

    /**
     * An array to hold inference results, to be feed into Tensorflow Lite as outputs.
     * This isn't part of the super class, because we need a primitive array here.
     */
    private var heatMapArray: Array<Array<Array<FloatArray>>> =
            Array(1) { Array(outputW) { Array(outputH) { FloatArray(14) } } }
    private var heatMapArrayByte: Array<Array<Array<ByteArray>>> =
            Array(1) { Array(outputW) { Array(outputH) { ByteArray(14) } } }
    //private var mMat: Mat? = null

    private var mPrintPointArray: Array<FloatArray>? = null

    fun getPointArray(): Array<FloatArray>? {
        return mPrintPointArray
    }

    fun getCopyPointArray(): Array<FloatArray>? {
        val outPointArray = Array (2) { FloatArray(14) }
        if (mPrintPointArray != null) {
            outPointArray[0] = mPrintPointArray!![0].copyOf()
            outPointArray[1] = mPrintPointArray!![1].copyOf()
        }
        return outPointArray
    }

    override fun addPixelValue(pixelValue: Int) {
        if (numBytesPerChannel == 4) {
            // bgr, float
            imgData!!.putFloat((pixelValue and 0xFF).toFloat())
            imgData!!.putFloat((pixelValue shr 8  and 0xFF).toFloat())
            imgData!!.putFloat((pixelValue shr 16 and 0xFF).toFloat())
        } else if (numBytesPerChannel == 1) {
            // bgr, byte
            imgData!!.put((pixelValue and 0xFF).toByte())
            imgData!!.put((pixelValue shr 8  and 0xFF).toByte())
            imgData!!.put((pixelValue shr 16 and 0xFF).toByte())
        }
    }

    override fun getProbability(labelIndex: Int): Float {
        //return heatMapArray[0][labelIndex];
        return 0f
    }

    override fun setProbability(
            labelIndex: Int,
            value: Number
    ) {
        //heatMapArray[0][labelIndex] = value.floatValue();
    }

    override fun getNormalizedProbability(labelIndex: Int): Float {
        return getProbability(labelIndex)
    }

    // heatMaps: Array<Array<Array<FloatArray>>>?
    // pointArray: Array
    override fun runInference() {
        if (numBytesPerChannel == 4) {
            tflite?.run(imgData!!, heatMapArray)
        } else if (numBytesPerChannel == 1) {
            tflite?.run(imgData!!, heatMapArrayByte)
        }

        //Log.i("TestOutPut", "start")

        //return heatMapArray.clone()

        if (mPrintPointArray == null)
            mPrintPointArray = Array(2) { FloatArray(14) }

        //if (!DetectorActivity().isOpenCVInit){
        //    return
        //}
        //Log.i("TestOutPut", "result null")

        // Gaussian Filter 5*5
        //if (mMat == null)
        //    mMat = Mat(outputW, outputH, CvType.CV_32F)

        val tempArray = FloatArray(outputW * outputH)
        //val outTempArray = FloatArray(outputW * outputH)
        for (i in 0..13) {
            var index = 0
            for (x in 0 until outputW) {
                for (y in 0 until outputH) {
                    tempArray[index] = heatMapArray[0][y][x][i]
                    index++
                }
            }

            //mMat!!.put(0, 0, tempArray)
            //Imgproc.GaussianBlur(mMat!!, mMat!!, Size(5.0, 5.0), 0.0, 0.0)
            //mMat!!.get(0, 0, outTempArray)

            var maxX = 0f
            var maxY = 0f
            var max = Float.MIN_VALUE

            // Find keypoint coordinate through maximum values
            for (x in 0 until outputW) {
                for (y in 0 until outputH) {
                    val center = get(x, y, tempArray)
                    if (center > max) {
                        max = center
                        maxX = x.toFloat()
                        maxY = y.toFloat()
                    }
                }
            }

            if (max != Float.MIN_VALUE) {
                mPrintPointArray!![0][i] = maxX
                mPrintPointArray!![1][i] = maxY
            } else {
                //mPrintPointArray = Array(2) { FloatArray(14) }
                //return
                mPrintPointArray!![0][i] = 0.0f
                mPrintPointArray!![1][i] = 0.0f
            }

            //Log.i("TestOutPut", "pic[$i] ($maxX,$maxY) $max")
        }

        //val pointArrayX = mPrintPointArray!![0]
        //val pointArrayY = mPrintPointArray!![1]
        //Log.d("PointArray", "[${Arrays.toString(pointArrayX)},[${Arrays.toString(pointArrayY)}]")
    }

    private operator fun get(
            x: Int,
            y: Int,
            arr: FloatArray
    ): Float {
        return if (x < 0 || y < 0 || x >= outputW || y >= outputH) -1f else arr[x * outputW + y]
    }

    companion object {

        /**
         * Create ImageClassifierFloatInception instance
         *
         * @param imageSizeX Get the image size along the x axis.
         * @param imageSizeY Get the image size along the y axis.
         * @param outputW The output width of model
         * @param outputH The output height of model
         * @param modelPath Get the name of the model file stored in Assets.
         * @param numBytesPerChannel Get the number of bytes that is used to store a single
         * color channel value.
         */
        fun create(
                activity: Activity,
                imageSizeX: Int = 192,
                imageSizeY: Int = 192,
                outputW: Int = 48,
                outputH: Int = 48,
                modelPath: String = "model.tflite",
                numBytesPerChannel: Int = 4
        ): ImageClassifierFloatInception =
                ImageClassifierFloatInception(
                        activity,
                        imageSizeX,
                        imageSizeY,
                        outputW,
                        outputH,
                        modelPath,
                        numBytesPerChannel)
    }
}
