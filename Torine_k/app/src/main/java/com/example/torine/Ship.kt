package no.fibula.torine




data class Ship(
    val name: String,
    val pname: String,
    val latitude: Double,
    val longitude: Double,
    val trueHeading: Double,
    val speedOverGround: Double,
    val delaySeconds: Long          // from server "delay"; 0 if missing or negative
)
