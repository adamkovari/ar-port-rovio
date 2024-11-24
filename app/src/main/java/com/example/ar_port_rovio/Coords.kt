package com.example.ar_port_rovio
import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable
data class Coords(var gyro: List<Float>, var accel: List<Float>) {}