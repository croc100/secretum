package net.crode.secretum.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 안경 없이 기능 검증용: 폰 후면 카메라 (프리뷰 없이 ImageCapture만 바인딩). */
class PhoneCameraSource(
    private val context: Context,
    private val owner: LifecycleOwner,
) : FrameSource {
    override val name = "phone"
    private var imageCapture: ImageCapture? = null

    private suspend fun ensureBound(): ImageCapture = withContext(Dispatchers.Main) {
        imageCapture ?: run {
            val provider = ProcessCameraProvider.getInstance(context).await(context)
            val ic = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, ic)
            imageCapture = ic
            ic
        }
    }

    override suspend fun capture(): ByteArray {
        val ic = ensureBound()
        val (jpeg, rotation) = suspendCancellableCoroutine<Pair<ByteArray, Int>> { cont ->
            ic.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                    val rot = image.imageInfo.rotationDegrees
                    image.close()
                    cont.resume(bytes to rot)
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            })
        }
        return withContext(Dispatchers.Default) { JpegUtil.normalize(jpeg, rotation) }
    }
}

private suspend fun <T> ListenableFuture<T>.await(context: Context): T =
    suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
