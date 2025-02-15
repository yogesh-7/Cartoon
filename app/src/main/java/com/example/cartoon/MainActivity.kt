package com.example.cartoon

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.cartoon.utils.ToastUtils.getStringFromResource
import com.example.cartoon.utils.ToastUtils.showToast
import com.example.cartoon.databinding.ActivityMainBinding
import com.example.cartoon.ml.WhiteboxCartoonGanInt8
import com.example.cartoon.utils.Helpers
import org.tensorflow.lite.support.image.TensorImage
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.lang.UnsupportedOperationException


class MainActivity : ComponentActivity() {

    companion object {

        // Directory name for saving cartoon images
        private const val DIRECTORY_NAME = "CartoonImages"
    }

    private lateinit var notification: Notification
    private lateinit var binding : ActivityMainBinding
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemBars()

        window.decorView.setOnTouchListener { _, event ->
            when (event.action) {
                // Detect user touch and hide system bars
                MotionEvent.ACTION_DOWN -> {
                    hideSystemBars()
                    true
                }
                else -> false
            }
        }

        notification = Notification(this)
        requestPermissions.launch(Helpers.requiredPermissions())

    }

   fun onClick(v:View) {
       when(v.id) {
           R.id.selectImagebtn -> fetchImageFromStorage.launch("image/*")
           R.id.Convert-> convertImageToCartoon()
           R.id.Save -> {
               // Get the drawable from the ImageView
               binding.imageView.drawable?.let {
                   saveCartoonedImage()
               }?:run {
                   showToast(R.string.msg_no_image_selected)
               }
           }
           else -> { throw UnsupportedOperationException("Unable to perform onClick for ${v.id}")}
       }

   }

    private val fetchImageFromStorage: ActivityResultLauncher<String> = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { imageUri ->
            // Load the selected image into the ImageView using Glide
                Glide.with(this@MainActivity.applicationContext)
                    .load(imageUri)
                    .into(binding.imageView)

            // Remove the background by setting it to null
            binding.imageView.background = null
        }?:run {
            showToast( R.string.msg_no_image_selected, Toast.LENGTH_SHORT)
        }
    }

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        //Currently we are not doing any action based on user permissions so nothing needed here.
    }

    private fun convertImageToCartoon() {
        binding.imageView.drawable?.let {
            // Load the image drawable into a TensorImage for inference
            val sourceImage = it.toTensorImage()

            // Perform inference to obtain the cartoonized image
            val cartoonizedImage = inferenceWithInt8Model(sourceImage)

            // Set the cartoonized image in the ImageView using Glide
            Glide.with(this@MainActivity.applicationContext)
                .load(cartoonizedImage.bitmap)
                .into(binding.imageView)

        }?:run {
            showToast(R.string.msg_no_image_selected)
        }
    }


    private fun saveCartoonedImage() {
        binding.imageView.drawable?.let {
            // Convert the drawable to a TensorImage for saving
            val sourceImage = it.toTensorImage()

            // Check if external storage is available for read and write
            if (isExternalStorageWritable()) {
                // Define the directory to save the image
                val directory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    DIRECTORY_NAME
                )

                // Create the directory if it doesn't exist
                if (!directory.exists()) {
                    directory.mkdirs()
                }

                // Generate a unique filename for the saved image
                val fileName = "cartoon_${System.currentTimeMillis()}.jpg"
                val file = File(directory, fileName)

                // Save the image to the file using Glide
                Glide.with(this@MainActivity.applicationContext)
                    .asBitmap()
                    .load(sourceImage.bitmap)
                    .into(object : CustomTarget<Bitmap>() {
                        override fun onResourceReady(
                            resource: Bitmap,
                            transition: Transition<in Bitmap>?,
                        ) {
                            var outputStream: OutputStream? = null
                            try {
                                // Save the bitmap to the file
                                outputStream = FileOutputStream(file)
                                resource.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                                outputStream.flush()

                                // Scan the saved image file to make it visible in the gallery
                                MediaScannerConnection.scanFile(
                                    this@MainActivity,
                                    arrayOf(file.toString()),
                                    null,
                                    null
                                )


                                showToast( R.string.msg_image_saved)

                                //sending notification
                                notification.sendNotification(
                                    getStringFromResource(R.string.notification_img_saved_title),
                                    getStringFromResource(R.string.notification_img_saved_message),
                                    R.drawable.baseline_notifications_24,
                                    0
                                )


                            } catch (e: Exception) {
                                e.printStackTrace()
                                showToast(R.string.err_msg_failed_to_save)
                            } finally {
                                outputStream?.close()
                            }
                        }

                        override fun onLoadCleared(placeholder: Drawable?) {
                            // Not used
                        }
                    })
            } else {
                showToast(R.string.err_msg_external_storage_unavailable)
            }
        }?:run {
            showToast( R.string.msg_no_image_selected)
        }

    }


    private fun isExternalStorageWritable(): Boolean {
        // Get the current state of the external storage
        val state = Environment.getExternalStorageState()

        // Check if the external storage is mounted and writable
        return Environment.MEDIA_MOUNTED == state
    }



    private fun inferenceWithInt8Model(sourceImage: TensorImage): TensorImage {
        // Create an instance of the WhiteboxCartoonGanInt8 model
        val model = WhiteboxCartoonGanInt8.newInstance(this)

        // Run model inference and get the result
        val outputs = model.process(sourceImage)
        val cartoonizedImage = outputs.cartoonizedImageAsTensorImage

        // Release model resources if no longer used
        model.close()

        return cartoonizedImage
    }


    /**
     * Extension function to convert a Drawable to a TensorImage.
     */
    private fun Drawable.toTensorImage(): TensorImage {
        // Convert the Drawable to a Bitmap
        val bitmap = (this as? BitmapDrawable)?.bitmap
            ?: Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888)

        // Create a TensorImage from the Bitmap
        return TensorImage.fromBitmap(bitmap)
    }

   /* private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
*/

    private fun hideSystemBars() {
        window.decorView.apply {
          /*  systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
*/
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        it.systemBarsBehavior =
                            WindowInsetsController.BEHAVIOR_DEFAULT
                    }
                    it.hide(WindowInsets.Type.systemBars())
                }
            } else
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    @Suppress("DEPRECATION")
                    systemUiVisibility = View.SYSTEM_UI_FLAG_LOW_PROFILE or
                            View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                }
        }
    }
}


/** commenting out this section of code because it is not currently in use, if in future anyone wants to
 * to use it please comment out this section.
 */
/**
private fun inferenceWithFp16Model(sourceImage: TensorImage): TensorImage {
val model = WhiteboxCartoonGanFp16.newInstance(this)

// Runs model inference and gets result.
val outputs = model.process(sourceImage)
val cartoonizedImage = outputs.cartoonizedImageAsTensorImage

// Releases model resources if no longer used.
model.close()

return cartoonizedImage
}

private fun inferenceWithDrModel(sourceImage: TensorImage): TensorImage {
val model = WhiteboxCartoonGanDr.newInstance(this)

// Runs model inference and gets result.
val outputs = model.process(sourceImage)
val cartoonizedImage = outputs.cartoonizedImageAsTensorImage

// Releases model resources if no longer used.
model.close()

return cartoonizedImage
}
 */

