package com.jiangdg.demo

import android.Manifest
import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.ActivityCompat

import androidx.activity.OnBackPressedCallback

import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import com.jiangdg.demo.databinding.FragmentHerLensBinding

import com.jiangdg.bluetooth.BleManager

class HerLensFragment : CameraFragment() {
    private lateinit var binding: FragmentHerLensBinding

    private val debugHandler = Handler(Looper.getMainLooper())

    private val aiHandler = Handler(Looper.getMainLooper())

    private var cameraStateText = "WAITING"

    private var permissionRequested = false

    private enum class Page {
        EXAMINATION,
        CAMERA,
        REVIEW,
        LOADING,
        MAINTENANCE
    }

    private var currentPage = Page.EXAMINATION

    private enum class CaptureType {
        BEFORE,
        AFTER
    }

    private var captureType = CaptureType.BEFORE

    private var currentCapturePath: String? = null

    private var beforeImagePath: String? = null

    private var afterImagePath: String? = null

    private var greenFilterActive = false

    private lateinit var bleManager: BleManager

    private var currentZoom = 1

    private val aiRunnable =
        Runnable {
            if (
                isAdded &&
                currentPage == Page.LOADING
            ) {
                showMaintenancePage()
            }
        }

    override fun getRootView(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): View {

        binding =
            FragmentHerLensBinding.inflate(
                inflater,
                container,
                false
            )

        binding.cameraViewContainer.clipChildren = true
        binding.cameraViewContainer.clipToPadding = true

        setupUi()

        updateExamState()

        showExaminationPage()

        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(
            view,
            savedInstanceState
        )

        bleManager=BleManager(requireContext())

        bleManager.onConnectionChanged={connected->

            activity?.runOnUiThread{

                binding.tvHardwareStatus.text =
                    if(connected)
                        "Hardware: Connected"
                    else
                        "Hardware: Not Connected"

            }
        }

        bleManager.onStateReceived={state->

            activity?.runOnUiThread{

                binding.tvLedStatus.text =
                    if(state.contains("\"led\":true"))
                        "LED: ON"
                    else
                        "LED: OFF"

            }
        }

        bleManager.onEventReceived={event->

            activity?.runOnUiThread{

                when(event){

                    "CAPTURE"->{
                        capturePhoto()
                    }

                    "RETAKE"->{
                        clearReview()
                        showCameraPage()
                    }

                    "ZOOM_IN" -> {
                        applyDigitalZoom(currentZoom+1)
                    }

                    "ZOOM_OUT" -> {
                        applyDigitalZoom(currentZoom-1)
                    }
                }
            }
        }

        bleManager.startScan()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                100
            )

        }

        requireActivity()
            .onBackPressedDispatcher
            .addCallback(
                viewLifecycleOwner,

                object : OnBackPressedCallback(true) {

                    override fun handleOnBackPressed() {

                        when(currentPage){

                            Page.CAMERA -> {
                                showExaminationPage()
                            }

                            Page.REVIEW -> {
                                clearReview()
                                showCameraPage()
                            }

                            Page.LOADING -> {
                                cancelAi()
                                showExaminationPage()
                            }

                            Page.MAINTENANCE -> {
                                showExaminationPage()
                            }

                            Page.EXAMINATION -> {

                                isEnabled = false

                                requireActivity()
                                    .onBackPressedDispatcher
                                    .onBackPressed()
                            }
                        }
                    }
                }
            )
    }

    override fun getCameraView(): IAspectRatio {

        val view =
            AspectRatioTextureView(
                requireContext()
            )

        return view
    }

    override fun getCameraViewContainer(): ViewGroup {

        binding.cameraViewContainer.clipChildren = true
        binding.cameraViewContainer.clipToPadding = true

        return binding.cameraViewContainer
    }

    override fun getCameraRequest(): CameraRequest {

        return CameraRequest.Builder()
            .setPreviewWidth(480)
            .setPreviewHeight(320)

            .setPreviewFormat(
                CameraRequest.PreviewFormat.FORMAT_YUYV
            )

            .setRenderMode(
                CameraRequest.RenderMode.NORMAL
            )

            .setAudioSource(
                CameraRequest.AudioSource.NONE
            )

            .setAspectRatioShow(true)
            .setCaptureRawImage(false)
            .setRawPreviewData(false)
            .create()
    }
    private fun setupUi() {
        setupExamination()
        setupCamera()
        setupReview()
        setupMaintenance()
    }

// =============================
// EXAMINATION PAGE
// =============================

    private fun setupExamination() {

        binding.btnBeforeTakePicture
            .setOnClickListener {

                captureType =
                    CaptureType.BEFORE

                binding.tvCameraTitle.text =
                    "Before Acetic Acid"

                showCameraPage()
            }


        binding.btnAfterTakePicture
            .setOnClickListener {

                if(beforeImagePath == null){

                    Toast.makeText(
                        requireContext(),
                        "Take Before photo first",
                        Toast.LENGTH_SHORT
                    ).show()

                    return@setOnClickListener
                }

                captureType =
                    CaptureType.AFTER

                binding.tvCameraTitle.text =
                    "After Acetic Acid"

                showCameraPage()
            }

        binding.btnBeforeRetry
            .setOnClickListener {

                beforeImagePath = null

                binding.ivBeforePreview
                    .setImageDrawable(null)

                updateExamState()

                captureType =
                    CaptureType.BEFORE

                showCameraPage()
            }

        binding.btnAfterRetry
            .setOnClickListener {

                afterImagePath = null

                binding.ivAfterPreview
                    .setImageDrawable(null)

                updateExamState()

                captureType =
                    CaptureType.AFTER


                showCameraPage()
            }

        binding.btnBeforeCrop
            .setOnClickListener {

                Toast.makeText(
                    requireContext(),
                    "Crop coming soon",
                    Toast.LENGTH_SHORT
                ).show()
            }

        binding.btnAfterCrop
            .setOnClickListener {

                Toast.makeText(
                    requireContext(),
                    "Crop coming soon",
                    Toast.LENGTH_SHORT
                ).show()
            }

        binding.btnBeforeGreenFilter
            .setOnClickListener {

                Toast.makeText(
                    requireContext(),
                    "Green filter coming soon",
                    Toast.LENGTH_SHORT
                ).show()
            }

        binding.btnAfterGreenFilter
            .setOnClickListener {

                Toast.makeText(
                    requireContext(),
                    "Green filter coming soon",
                    Toast.LENGTH_SHORT
                ).show()
            }

        binding.btnNext
            .setOnClickListener {

                if(
                    beforeImagePath == null ||
                    afterImagePath == null
                ){

                    Toast.makeText(
                        requireContext(),
                        "Complete both photos first",
                        Toast.LENGTH_SHORT
                    ).show()

                    return@setOnClickListener
                }

                showLoadingPage()

                startAi()
            }
    }

// =============================
// CAMERA PAGE
// =============================

    private fun setupCamera(){
        binding.btnCameraBack.setOnClickListener {
            showExaminationPage()
        }

        binding.btnCameraTakePicture.setOnClickListener {
            capturePhoto()
        }

        binding.btnZoom1.setOnClickListener {
            applyDigitalZoom(1)
        }

        binding.btnZoom2.setOnClickListener {
            applyDigitalZoom(2)
        }

        binding.btnZoom3.setOnClickListener {
            applyDigitalZoom(3)
        }

        binding.btnGreenFilter.setOnClickListener {

            greenFilterActive = !greenFilterActive

            binding.greenOverlay.visibility =
                if(greenFilterActive)
                    View.VISIBLE
                else
                    View.GONE
        }
    }

    private fun applyDigitalZoom(level:Int){

        currentZoom = level.coerceIn(1,3)

        val zoom = when(currentZoom){
            1 -> 1.35f
            2 -> 1.5f
            3 -> 2f
            else -> 1f
        }

        val preview = binding.cameraViewContainer.getChildAt(0)

        preview?.let {

            it.pivotX = it.width / 2f
            it.pivotY = it.height / 2f

            it.scaleX = zoom
            it.scaleY = zoom

        }


        binding.cameraViewContainer.clipChildren = true
        binding.cameraViewContainer.clipToPadding = true


        binding.btnZoom1.alpha =
            if(level == 1) 1f else 0.5f

        binding.btnZoom2.alpha =
            if(level == 2) 1f else 0.5f

        binding.btnZoom3.alpha =
            if(level == 3) 1f else 0.5f
    }

    private fun capturePhoto(){

        if(!isCameraOpened()){

            Toast.makeText(
                requireContext(),
                "Camera not connected",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        binding.btnCameraTakePicture
            .isEnabled = false

        captureImage(
            object : ICaptureCallBack{

                override fun onBegin(){

                    Log.d(
                        "HERLENS",
                        "capture start"
                    )
                }

                override fun onError(
                    error:String?
                ){
                    resetCaptureButton()

                    Toast.makeText(
                        requireContext(),
                        error ?: "capture failed",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onComplete(
                    path:String?
                ){

                    resetCaptureButton()

                    if(path.isNullOrEmpty())
                        return

                    currentCapturePath =
                        path

                    binding.tvReviewTitle.text =
                        when(captureType){

                            CaptureType.BEFORE ->
                                "Review Before Acetic Acid"


                            CaptureType.AFTER ->
                                "Review After Acetic Acid"
                        }

                    showCapturedImage(
                        binding.ivReviewPhoto,
                        path
                    )

                    binding.tvReviewPlaceholder
                        .visibility =
                        View.GONE

                    showReviewPage()
                }
            }
        )
    }

    private fun resetCaptureButton(){

        binding.btnCameraTakePicture
            .isEnabled = true


        binding.btnCameraTakePicture
            .text =
            "TAKE PICTURE"
    }

// =============================
// REVIEW PAGE
// =============================

    private fun setupReview(){


        binding.btnReviewBack
            .setOnClickListener {

                clearReview()

                showCameraPage()
            }


        binding.btnRetake
            .setOnClickListener {

                clearReview()

                showCameraPage()
            }


        binding.btnCrop
            .setOnClickListener {

                Toast.makeText(
                    requireContext(),
                    "Crop coming soon",
                    Toast.LENGTH_SHORT
                ).show()
            }


        binding.btnUsePhoto
            .setOnClickListener {


                val path =
                    currentCapturePath
                        ?: return@setOnClickListener



                when(captureType){

                    CaptureType.BEFORE -> {

                        beforeImagePath =
                            path


                        showCapturedImage(
                            binding.ivBeforePreview,
                            path
                        )
                    }



                    CaptureType.AFTER -> {

                        afterImagePath =
                            path


                        showCapturedImage(
                            binding.ivAfterPreview,
                            path
                        )
                    }
                }



                clearReview()

                updateExamState()

                showExaminationPage()
            }
    }

    private fun clearReview(){

        currentCapturePath = null

        binding.ivReviewPhoto
            .setImageDrawable(null)

        binding.tvReviewPlaceholder
            .visibility =
            View.VISIBLE
    }

    // =============================
// IMAGE HANDLER
// =============================

    private fun showCapturedImage(
        imageView: ImageView,
        path:String
    ){
        try{
            val bitmap =
                BitmapFactory.decodeFile(path)

            if(bitmap != null){
                imageView
                    .setImageBitmap(bitmap)

            }

        }catch(e:Exception){

            Log.e(
                "HERLENS",
                "image error",
                e
            )
        }
    }

// =============================
// UPDATE EXAMINATION PAGE
// =============================

    private fun updateExamState(){
        val beforeDone =
            beforeImagePath != null

        val afterDone =
            afterImagePath != null

        binding.btnBeforeTakePicture
            .visibility =
            if(beforeDone)
                View.GONE
            else
                View.VISIBLE

        binding.layoutBeforeActions
            .visibility =
            if(beforeDone)
                View.VISIBLE
            else
                View.GONE

        binding.ivBeforePreview
            .visibility =
            if(beforeDone)
                View.VISIBLE
            else
                View.GONE

        binding.btnAfterTakePicture
            .isEnabled =
            beforeDone

        binding.ivAfterPreview
            .visibility =
            if(afterDone)
                View.VISIBLE
            else
                View.GONE

        binding.layoutAfterActions
            .visibility =
            if(afterDone)
                View.VISIBLE
            else
                View.GONE

        binding.layoutAfterEmpty
            .visibility =
            if(afterDone)
                View.GONE
            else
                View.VISIBLE

        binding.btnNext
            .isEnabled =
            beforeDone && afterDone

        binding.btnNext.alpha =
            if(beforeDone && afterDone)
                1f
            else
                0.45f
    }

// =============================
// AI PAGE
// =============================

    private fun startAi(){

        aiHandler.removeCallbacks(
            aiRunnable
        )

        aiHandler.postDelayed(
            aiRunnable,
            2500
        )
    }

    private fun cancelAi(){

        aiHandler.removeCallbacks(
            aiRunnable
        )
    }

    private fun setupMaintenance(){

        binding.btnMaintenanceBack
            .setOnClickListener {

                showExaminationPage()
            }
    }

// =============================
// PAGE NAVIGATION
// =============================

    private fun hidePages(){

        binding.pageExamination
            .visibility =
            View.GONE

        binding.pageCamera
            .visibility =
            View.GONE

        binding.pageReview
            .visibility =
            View.GONE

        binding.pageLoading
            .visibility =
            View.GONE

        binding.pageMaintenance
            .visibility =
            View.GONE
    }

    private fun showExaminationPage(){
        cancelAi()
        hidePages()

        binding.pageExamination
            .visibility =
            View.VISIBLE

        currentPage =
            Page.EXAMINATION
    }

    private fun showCameraPage(){
        hidePages()

        binding.pageCamera
            .visibility =
            View.VISIBLE

        currentPage =
            Page.CAMERA
    }

    private fun showReviewPage(){
        hidePages()

        binding.pageReview
            .visibility =
            View.VISIBLE

        currentPage =
            Page.REVIEW
    }

    private fun showLoadingPage(){
        hidePages()
        binding.pageLoading
            .visibility =
            View.VISIBLE

        currentPage =
            Page.LOADING
    }

    private fun showMaintenancePage(){
        hidePages()
        binding.pageMaintenance
            .visibility =
            View.VISIBLE

        currentPage =
            Page.MAINTENANCE
    }
// =============================
// USB CHECK
// =============================

    private fun isUvcDevice(
        device:UsbDevice
    ):Boolean{
        if(
            device.deviceClass ==
            UsbConstants.USB_CLASS_VIDEO
        ){
            return true
        }

        for(
        i in 0 until device.interfaceCount
        ){
            val usbInterface =
                device.getInterface(i)

            if(
                usbInterface.interfaceClass ==
                UsbConstants.USB_CLASS_VIDEO
            ){

                return true
            }
        }
        return false
    }

    private fun updateUsbStatus(){
        if(
            !::binding.isInitialized ||
            !isAdded
        ){

            return
        }

        val usbManager =
            requireContext()
                .getSystemService(
                    Context.USB_SERVICE
                )
                    as UsbManager

        val devices =
            usbManager
                .deviceList
                .values
                .toList()

        if(devices.isEmpty()){

            permissionRequested = false

            activity?.runOnUiThread {

                binding.tvCameraStatus.text =
                    "Camera Disconnected"

            }

            return
        }

        val device =
            devices.firstOrNull{

                isUvcDevice(it)

            } ?: devices.first()

        if(
            isUvcDevice(device) &&
            !usbManager.hasPermission(device) &&
            !permissionRequested
        ){

            permissionRequested =
                true

            requestPermission(
                device
            )
        }
    }

    private val debugRunnable =
        object:Runnable{
            override fun run(){
                updateUsbStatus()

                debugHandler.postDelayed(
                    this,
                    1000
                )
            }
        }

// =============================
// LIFECYCLE
// =============================

    override fun onResume(){
        super.onResume()

        debugHandler.removeCallbacks(
            debugRunnable
        )

        debugHandler.post(
            debugRunnable
        )
    }

    override fun onPause(){
        debugHandler.removeCallbacks(
            debugRunnable
        )

        super.onPause()
    }

    override fun onDestroyView(){

        if(::bleManager.isInitialized){
            bleManager.stopScan()
        }


        debugHandler.removeCallbacks(
            debugRunnable
        )

        aiHandler.removeCallbacks(
            aiRunnable
        )

        super.onDestroyView()
    }

// =============================
// CAMERA STATE
// =============================

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ){

        Log.d(
            "HERLENS",
            "$code : $msg"
        )

        activity?.runOnUiThread {

            cameraStateText = code.toString()

            binding.tvCameraStatus.text =
                when(code){

                    ICameraStateCallBack.State.OPENED ->
                        "Camera Connected"

                    ICameraStateCallBack.State.CLOSED ->
                        "Camera Disconnected"

                    ICameraStateCallBack.State.ERROR ->
                        "Camera Error"

                    else ->
                        code.toString()
                }
        }
    }

}