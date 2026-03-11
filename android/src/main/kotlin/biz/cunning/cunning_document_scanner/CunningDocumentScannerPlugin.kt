package biz.cunning.cunning_document_scanner

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.IntentSender
import androidx.core.app.ActivityCompat
import biz.cunning.cunning_document_scanner.fallback.DocumentScannerActivity
import biz.cunning.cunning_document_scanner.fallback.constants.DocumentScannerExtra
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry


/** CunningDocumentScannerPlugin */
class CunningDocumentScannerPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
    private var delegate: PluginRegistry.ActivityResultListener? = null
    private var binding: ActivityPluginBinding? = null
    private var pendingResult: Result? = null
    private lateinit var activity: Activity
    private val START_DOCUMENT_ACTIVITY: Int = 0x362738
    private val START_DOCUMENT_FB_ACTIVITY: Int = 0x362737
    private val REQUEST_CODE_SCAN_IMAGES: Int = 0x362739
    private val REQUEST_CODE_SCAN_PDF: Int = 0x362740
    private val REQUEST_CODE_SCAN_URI: Int = 0x362741


    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "cunning_document_scanner")
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "getPictures" -> {
                val noOfPages = call.argument<Int>("noOfPages") ?: 50
                val isGalleryImportAllowed = call.argument<Boolean>("isGalleryImportAllowed") ?: false
                this.pendingResult = result
                startScan(noOfPages, isGalleryImportAllowed)
            }
            "getScannedDocumentAsImages" -> {
                val noOfPages = call.argument<Int>("noOfPages") ?: 50
                val isGalleryImportAllowed = call.argument<Boolean>("isGalleryImportAllowed") ?: false
                this.pendingResult = result
                startScanImages(noOfPages, isGalleryImportAllowed)
            }
            "getScannedDocumentAsPdf" -> {
                val noOfPages = call.argument<Int>("noOfPages") ?: 50
                val isGalleryImportAllowed = call.argument<Boolean>("isGalleryImportAllowed") ?: false
                this.pendingResult = result
                startScanPdf(noOfPages, isGalleryImportAllowed)
            }
            "getScanDocumentsUri" -> {
                val noOfPages = call.argument<Int>("noOfPages") ?: 50
                val isGalleryImportAllowed = call.argument<Boolean>("isGalleryImportAllowed") ?: false
                this.pendingResult = result
                startScanUri(noOfPages, isGalleryImportAllowed)
            }
            else -> result.notImplemented()
        }
    }


    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.activity = binding.activity

        addActivityResultListener(binding)
    }

    private fun addActivityResultListener(binding: ActivityPluginBinding) {
        this.binding = binding
        if (this.delegate == null) {
            this.delegate = PluginRegistry.ActivityResultListener { requestCode, resultCode, data ->
                if (requestCode != START_DOCUMENT_ACTIVITY && requestCode != START_DOCUMENT_FB_ACTIVITY
                    && requestCode != REQUEST_CODE_SCAN_IMAGES && requestCode != REQUEST_CODE_SCAN_PDF
                    && requestCode != REQUEST_CODE_SCAN_URI) {
                    return@ActivityResultListener false
                }
                var handled = false
                if (requestCode == START_DOCUMENT_ACTIVITY) {
                    when (resultCode) {
                        Activity.RESULT_OK -> {
                            // check for errors
                            val error = data?.extras?.getString("error")
                            if (error != null) {
                                pendingResult?.error("ERROR", "error - $error", null)
                            } else {
                                // get an array with scanned document file paths
                                val scanningResult: GmsDocumentScanningResult =
                                    data?.extras?.getParcelable("extra_scanning_result")
                                        ?: return@ActivityResultListener false

                                val successResponse = scanningResult.pages?.map {
                                    it.imageUri.toString().removePrefix("file://")
                                }?.toList()
                                // trigger the success event handler with an array of cropped images
                                pendingResult?.success(successResponse)
                            }
                            handled = true
                        }

                        Activity.RESULT_CANCELED -> {
                            // user closed camera
                            pendingResult?.success(emptyList<String>())
                            handled = true
                        }
                    }
                } else if (requestCode == REQUEST_CODE_SCAN_IMAGES) {
                    when (resultCode) {
                        Activity.RESULT_OK -> {
                            val scanningResult: GmsDocumentScanningResult =
                                GmsDocumentScanningResult.fromActivityResultIntent(data)
                                    ?: return@ActivityResultListener false

                            val images = scanningResult.pages?.map {
                                it.imageUri.toString().removePrefix("file://")
                            }?.toList()

                            pendingResult?.success(
                                mapOf(
                                    "images" to (images ?: emptyList<String>()),
                                    "pageCount" to (images?.size ?: 0)
                                )
                            )
                            handled = true
                        }
                        Activity.RESULT_CANCELED -> {
                            pendingResult?.success(null)
                            handled = true
                        }
                    }
                } else if (requestCode == REQUEST_CODE_SCAN_PDF) {
                    when (resultCode) {
                        Activity.RESULT_OK -> {
                            val scanningResult: GmsDocumentScanningResult =
                                GmsDocumentScanningResult.fromActivityResultIntent(data)
                                    ?: return@ActivityResultListener false

                            val pdf = scanningResult.pdf
                            val pdfUri = pdf?.uri?.toString()?.removePrefix("file://")
                            val pageCount = pdf?.pageCount ?: 0

                            pendingResult?.success(
                                mapOf(
                                    "pdfUri" to pdfUri,
                                    "pageCount" to pageCount
                                )
                            )
                            handled = true
                        }
                        Activity.RESULT_CANCELED -> {
                            pendingResult?.success(null)
                            handled = true
                        }
                    }
                } else if (requestCode == REQUEST_CODE_SCAN_URI) {
                    when (resultCode) {
                        Activity.RESULT_OK -> {
                            val scanningResult: GmsDocumentScanningResult =
                                GmsDocumentScanningResult.fromActivityResultIntent(data)
                                    ?: return@ActivityResultListener false

                            val pageUris = scanningResult.pages?.map {
                                it.imageUri.toString()
                            }?.toList()

                            pendingResult?.success(
                                mapOf(
                                    "uris" to (pageUris ?: emptyList<String>()),
                                    "pageCount" to (pageUris?.size ?: 0)
                                )
                            )
                            handled = true
                        }
                        Activity.RESULT_CANCELED -> {
                            pendingResult?.success(null)
                            handled = true
                        }
                    }
                } else {
                    when (resultCode) {
                        Activity.RESULT_OK -> {
                            // check for errors
                            val error = data?.extras?.getString("error")
                            if (error != null) {
                                pendingResult?.error("ERROR", "error - $error", null)
                            } else {
                                // get an array with scanned document file paths
                                val croppedImageResults =
                                    data?.getStringArrayListExtra("croppedImageResults")?.toList()
                                        ?: let {
                                            pendingResult?.error("ERROR", "No cropped images returned", null)
                                            return@ActivityResultListener true
                                        }

                                // return a list of file paths
                                // removing file uri prefix as Flutter file will have problems with it
                                val successResponse = croppedImageResults.map {
                                    it.removePrefix("file://")
                                }.toList()
                                // trigger the success event handler with an array of cropped images
                                pendingResult?.success(successResponse)
                            }
                            handled = true
                        }

                        Activity.RESULT_CANCELED -> {
                            // user closed camera
                            pendingResult?.success(emptyList<String>())
                            handled = true
                        }
                    }
                }

                if (handled) {
                    // Clear the pending result to avoid reuse
                    pendingResult = null
                }
                return@ActivityResultListener handled
            }
        } else {
            binding.removeActivityResultListener(this.delegate!!)
        }

        binding.addActivityResultListener(delegate!!)
    }


    /**
     * create intent to launch document scanner and set custom options
     */
    private fun createDocumentScanIntent(noOfPages: Int): Intent {
        val documentScanIntent = Intent(activity, DocumentScannerActivity::class.java)

        documentScanIntent.putExtra(
            DocumentScannerExtra.EXTRA_MAX_NUM_DOCUMENTS,
            noOfPages
        )

        return documentScanIntent
    }


    /**
     * add document scanner result handler and launch the document scanner
     */
    private fun startScan(noOfPages: Int, isGalleryImportAllowed: Boolean) {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(isGalleryImportAllowed)
            .setPageLimit(noOfPages)
            .setResultFormats(RESULT_FORMAT_JPEG)
            .setScannerMode(SCANNER_MODE_FULL)
            .build()
        launchScanner(options, START_DOCUMENT_ACTIVITY, noOfPages)
    }

    /**
     * Scan and return images only
     */
    private fun startScanImages(noOfPages: Int, isGalleryImportAllowed: Boolean) {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(isGalleryImportAllowed)
            .setPageLimit(noOfPages)
            .setResultFormats(RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(SCANNER_MODE_FULL)
            .build()
        launchScanner(options, REQUEST_CODE_SCAN_IMAGES, noOfPages)
    }

    /**
     * Scan and return PDF only
     */
    private fun startScanPdf(noOfPages: Int, isGalleryImportAllowed: Boolean) {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(isGalleryImportAllowed)
            .setPageLimit(noOfPages)
            .setResultFormats(RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(SCANNER_MODE_FULL)
            .build()
        launchScanner(options, REQUEST_CODE_SCAN_PDF, noOfPages)
    }

    /**
     * Scan and return page URIs
     */
    private fun startScanUri(noOfPages: Int, isGalleryImportAllowed: Boolean) {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(isGalleryImportAllowed)
            .setPageLimit(noOfPages)
            .setResultFormats(RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(SCANNER_MODE_FULL)
            .build()
        launchScanner(options, REQUEST_CODE_SCAN_URI, noOfPages)
    }

    /**
     * Helper method to launch scanner with specific options and request code
     */
    private fun launchScanner(options: GmsDocumentScannerOptions, requestCode: Int, noOfPages: Int) {
        val scanner = GmsDocumentScanning.getClient(options)
        scanner.getStartScanIntent(activity).addOnSuccessListener {
            try {
                activity.startIntentSenderForResult(it, requestCode, null, 0, 0, 0)
            } catch (e: IntentSender.SendIntentException) {
                pendingResult?.error("ERROR", "Failed to start document scanner", null)
            }
        }.addOnFailureListener {
            if (it is MlKitException) {
                val intent = createDocumentScanIntent(noOfPages)
                try {
                    ActivityCompat.startActivityForResult(
                        this.activity,
                        intent,
                        START_DOCUMENT_FB_ACTIVITY,
                        null
                    )
                } catch (e: ActivityNotFoundException) {
                    pendingResult?.error("ERROR", "FAILED TO START ACTIVITY", null)
                }
            } else {
                pendingResult?.error("ERROR", "Failed to start document scanner Intent", null)
            }
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {

    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        addActivityResultListener(binding)
    }

    override fun onDetachedFromActivity() {
        removeActivityResultListener()
    }

    private fun removeActivityResultListener() {
        this.delegate?.let { this.binding?.removeActivityResultListener(it) }
    }
}
