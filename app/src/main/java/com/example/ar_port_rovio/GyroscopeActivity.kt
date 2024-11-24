package com.example.ar_port_rovio

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.usb.UsbManager
import android.media.ImageReader
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

import java.nio.ByteBuffer


class GyroscopeActivity : Activity(), SensorEventListener {

    //Sensor management
    private lateinit var sensorManager: SensorManager
    private var gyroscope: Sensor? = null
    private lateinit var gyroscopeData: TextView
    private var accelerometer: Sensor? = null
    private lateinit var accData: TextView

    // Variables to control the frequency (50Hz = 20ms delay)
    private var lastGyroUpdateTime: Long = 0
    private val updateGyroInterval: Long = 1000 // 33 milliseconds (30Hz)

    private var lastAccelUpdateTime: Long = 0
    private val updateAccelInterval: Long = 1000 // 33 milliseconds (30Hz)

    //Camera management
    private lateinit var textureView: TextureView
    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var captureRequestBuilder: CaptureRequest.Builder
    private lateinit var cameraManager: CameraManager
    private lateinit var imageReader: ImageReader
    private lateinit var surface: Surface
    private val burstImagesCount = 1 // 30 photos per second
    private var imageCounter = 0

    //Bluetooth
    private lateinit var btComm: BluetoothCommunication
    var macAddress: String = "00:19:15:5C:86:1F"

    //Webserver
    private lateinit var server: Server

    // ImageReader listener to handle images when they are available
    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireNextImage()
        saveImage(image)
        image.close()
    }



    //Serial communication
    private var port: UsbSerialPort? = null
    private val WRITE_WAIT_MILLIS = 30 //30ms

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val ipAddress = Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)

        Log.i("IP_ADDRESS", ipAddress)

        //Server
//        embeddedServer(Netty, 8080) {
//            install(ContentNegotiation) {
//                gson {}
//            }
//            routing {
//                get("/") {
//                    call.respond(mapOf("message" to "Hello world"))
//                }
//            }
//        }.start(wait = false)

        server = Server()
        server.setup(9000)
        server.start()

        //Bluetooth
        //btComm = BluetoothCommunication(this)

        //Sensor
        // Initialize the TextView to display gyroscope data
        gyroscopeData = findViewById(R.id.gyroscopeData)
        // Initialize the TextView to display acc data
        accData = findViewById(R.id.accData)

        // Initialize SensorManager and Gyroscope sensor
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        Log.i("Gyroscope", gyroscope.toString())

        // Check if the gyroscope sensor is available
        if (gyroscope == null) {
            // Handle the case where the gyroscope is not available
            Toast.makeText(this, "Gyroscope sensor not available on this device", Toast.LENGTH_LONG)
                .show()
        } else {
            // Register the listener only if the sensor is available
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST)
        }

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Check if the accelerometer sensor is available
        if (accelerometer == null) {
            // Handle the case where the gyroscope is not available
            Toast.makeText(this, "Accelerometer sensor not available on this device", Toast.LENGTH_LONG)
                .show()
        } else {
            // Register the listener only if the sensor is available
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST)
        }

        //Camera
        textureView = findViewById(R.id.textureView)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Set SurfaceTextureListener to wait until the TextureView is ready
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                // Now that the SurfaceTexture is available, open the camera
                if (ContextCompat.checkSelfPermission(
                        this@GyroscopeActivity,
                        Manifest.permission.CAMERA
                    )
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    openCamera()
                } else {
                    ActivityCompat.requestPermissions(
                        this@GyroscopeActivity,
                        arrayOf(Manifest.permission.CAMERA),
                        101
                    )
                }
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return false
            }
        }

        //Serial communication
        Log.i("PORT: ", "SETUP")
        // Find all available drivers from attached devices.
        val manager = getSystemService(USB_SERVICE) as UsbManager

        //LG K42 custom usb driver
//        val customTable = ProbeTable()
//        customTable.addProduct(0x03EC, 0x1770, FtdiSerialDriver::class.java)
//        val prober = UsbSerialProber(customTable)
//        val availableDrivers = prober.findAllDrivers(manager)

        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        Log.i("PORT",availableDrivers.toString())
        if (availableDrivers.isEmpty()) {
            Log.i("PORT","Available Drivers list is empty!")
            return
        }

        // Open a connection to the first available driver.
        val driver = availableDrivers[0]
        Log.i("Available Drivers: ", driver.toString())
        val connection = manager.openDevice(driver.device)
            ?: // add UsbManager.requestPermission(driver.getDevice(), ..) handling here
            return

        port = driver.ports[0] // Most devices have just one port (port 0)
        Log.i("PORT: ", port.toString())
        if (port != null) {
            port?.open(connection)
            port?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        }else
        {
            Toast.makeText(this, "No usb connection", Toast.LENGTH_LONG)
                .show()
        }

    }

    override fun onSensorChanged(event: SensorEvent?) {
        // Ensure event is from the gyroscope
        if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE) {
            val currentTime = System.currentTimeMillis()

            // Check if the time interval (30ms) has passed
            if (currentTime - lastGyroUpdateTime > updateGyroInterval) {
                lastGyroUpdateTime = currentTime

                // Get the rotation values from the gyroscope sensor
                val rotationX = event.values[0]
                val rotationY = event.values[1]
                val rotationZ = event.values[2]

                // Update the UI with gyroscope data
                val data = "X: $rotationX Y: $rotationY Z: $rotationZ\n"
                gyroscopeData.text = data

                //port?.write(data.toByteArray(), WRITE_WAIT_MILLIS);

//                if (btComm.connectToPC(macAddress)) {  // replace with your PC's Bluetooth MAC address
//                    btComm.sendData(data.toByteArray())
//                }

                server.setCoords(listOf(rotationX, rotationY, rotationZ), true)

                Log.i("TYPE_GYROSCOPE", "X: $rotationX\nY: $rotationY\nZ: $rotationZ")
            }
        }

        // Ensure event is from the accelerometer
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val currentTime = System.currentTimeMillis()

            // Check if the time interval (30ms) has passed
            if (currentTime - lastAccelUpdateTime > updateAccelInterval) {
                lastAccelUpdateTime = currentTime

                // Get the acc values from the gyroscope sensor
                val accelerationX = event.values[0]
                val accelerationY = event.values[1]
                val accelerationZ = event.values[2]

                // Update the UI with gyroscope data
                val data = "X: $accelerationX Y: $accelerationY Z: $accelerationZ\n"
                accData.text = data

                //port?.write(data.toByteArray(), WRITE_WAIT_MILLIS);

//                if (btComm.connectToPC(macAddress)) {  // replace with your PC's Bluetooth MAC address
//                    btComm.sendData(data.toByteArray())
//                }

                server.setCoords(listOf(accelerationX, accelerationY, accelerationZ), false)

                Log.i("TYPE_ACCELEROMETER", "X: $accelerationX\nY: $accelerationY\nZ: $accelerationZ")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // You can handle changes in sensor accuracy if needed
    }

    override fun onResume() {
        super.onResume()
        // Re-register the listener when the activity resumes
        gyroscope?.also { gyro ->
            sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    override fun onPause() {
        super.onPause()
        // Unregister the listener when the activity pauses to save battery
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraDevice.close()
        port?.close();
        server.stop()
    }

    private fun openCamera() {
        try {
            val cameraId = cameraManager.cameraIdList[0] // Use the first camera
            val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewSize = map?.getOutputSizes(SurfaceTexture::class.java)?.get(0)

            // Set up ImageReader for handling image capture
            imageReader = ImageReader.newInstance(752, 480, ImageFormat.JPEG, burstImagesCount)
            imageReader.setOnImageAvailableListener(imageAvailableListener, null)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCameraPreviewSession()
                    Log.i("openCamera", "Camera created!")
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraDevice.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Toast.makeText(this@GyroscopeActivity, "Camera error!", Toast.LENGTH_SHORT).show()
                }
            }, null)

        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun createCameraPreviewSession() {
        try {
            // Ensure the SurfaceTexture is available before using it
            val texture = textureView.surfaceTexture
            if (texture == null) {
                throw IllegalStateException("SurfaceTexture is not available")
            }

            texture?.setDefaultBufferSize(752, 480)
            surface = Surface(texture)
            Log.i("CameraPreview", "Surface is: $surface")

            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(surface)
            captureRequestBuilder.addTarget(imageReader.surface)

            cameraDevice.createCaptureSession(
                listOf(surface, imageReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

                        // Set the repeating request with a valid surface target
                        captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null)

                        // Start burst capture
                        captureBurstPhotos()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Toast.makeText(this@GyroscopeActivity, "Configuration failed!", Toast.LENGTH_SHORT).show()
                    }
                },
                null
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun captureBurstPhotos() {
        try {
            val burstCaptureList = ArrayList<CaptureRequest>()

            // Make sure to add the image reader surface as the target for still capture
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureRequestBuilder.addTarget(surface)  // Add ImageReader surface as target
            captureRequestBuilder.addTarget(imageReader.surface)  // Add ImageReader surface as target
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

            // Create burst requests
            for (i in 0 until burstImagesCount) {
                val captureRequest = captureRequestBuilder.build()
                burstCaptureList.add(captureRequest)
            }

            // Trigger the burst capture session
            captureSession.captureBurst(burstCaptureList, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)
                    Log.d("CameraActivity", "Image captured")
                }
            }, null)

        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun saveImage(image: android.media.Image) {
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        server.setImage(bytes)

//        if (btComm.connectToPC(macAddress)) {  // replace with your PC's Bluetooth MAC address
//            btComm.sendData(bytes)
//        }


//        val file = File(Environment.getExternalStorageDirectory().toString() + "/DCIM/IMG_" + UUID.randomUUID().toString() + ".jpg")
//
//        var fos: FileOutputStream? = null
//        try {
//            fos = FileOutputStream(file)
//            fos.write(bytes)
//            Log.d("CameraActivity", "Image saved at: ${file.absolutePath}")
//        } catch (e: IOException) {
//            e.printStackTrace()
//        } finally {
//            fos?.close()
//        }
    }
}
