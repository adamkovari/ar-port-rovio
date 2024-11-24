package com.example.ar_port_rovio

import android.util.Log
import io.ktor.application.call
import io.ktor.http.HttpStatusCode
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

class Server {

    private lateinit var server: ApplicationEngine
    private var coords: Coords = Coords(listOf(0.0F,0.0F,0.0F), listOf(0.0F,0.0F,0.0F))
    private var image: ByteArray = ByteArray(10)

    @OptIn(ExperimentalSerializationApi::class)
    fun setup(port: Int = 9000) {
        // Set up routes and handlers
        this.server = embeddedServer(Netty, port = port) {

            routing {
                get("/get_image")  {
                    call.respond(HttpStatusCode.OK, image)
                }
                get("/get_imu"){
                    Log.i("Server", Json.encodeToString(coords))
                    call.respond(HttpStatusCode.OK, Json.encodeToString(coords))
                }
            }
        }
    }

    fun setImage(image: ByteArray) {
        this.image = image
    }

    fun setCoords(coords: List<Float>, type: Boolean) {
        if(type){//setGyro
            this.coords.gyro = coords
        }else {
            this.coords.accel = coords
        }
    }

    fun start() {
        server.start(wait = false) // Start the Ktor server
    }

    fun stop() {
        server.stop(0, 0, TimeUnit.MILLISECONDS) // Stop the server
    }
}