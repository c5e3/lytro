package app.refocus.core.lf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.pow

/** The parts of a picture's `.TXT` metadata needed for decoding and display. */
class LfMetadata private constructor(root: JsonObject) {

    private val frame: JsonObject = root.obj("master").obj("picture").arr("frameArray")[0]
        .jsonObj.obj("frame").obj("metadata")
    private val image = frame.obj("image")
    private val devices = frame.obj("devices")

    val width: Int = image.num("width").toInt()
    val height: Int = image.num("height").toInt()

    private val pixelFormat = image.obj("rawDetails").obj("pixelFormat")
    val black = Rggb(pixelFormat.obj("black"))
    val white = Rggb(pixelFormat.obj("white"))
    val upperLeftPixel: String = image.obj("rawDetails").obj("mosaic").str("upperLeftPixel") ?: "b"

    private val color = image.obj("color")
    val whiteBalance = Rggb(color.obj("whiteBalanceGain"))
    val ccm: FloatArray = color.arr("ccmRgbToSrgbArray").map { it.num().toFloat() }.toFloatArray()
    val gamma: Float = color.numOrNull("gamma")?.toFloat() ?: 0.4167f
    val exposureBias: Double = image.numOrNull("modulationExposureBias") ?: 0.0
    val exposure: Float = 2.0.pow(-exposureBias).toFloat()

    private val mla = devices.obj("mla")
    private val pixelPitch = devices.obj("sensor").num("pixelPitch")
    val lensPitch: Double = mla.num("lensPitch") / pixelPitch
    val rotation: Double = mla.num("rotation")
    val scaleX: Double = mla.obj("scaleFactor").num("x")
    val scaleY: Double = mla.obj("scaleFactor").num("y")
    val sensorOffsetX: Double = mla.obj("sensorOffset").num("x") / pixelPitch
    val sensorOffsetY: Double = mla.obj("sensorOffset").num("y") / pixelPitch

    // ---------------------------------------------------------- display info
    val iso: Int? = devices.objOrNull("sensor")?.numOrNull("iso")?.toInt()
    val shutterSeconds: Double? = devices.objOrNull("shutter")?.numOrNull("frameExposureDuration")
    val fNumber: Double? = devices.objOrNull("lens")?.numOrNull("fNumber")
    val focalLengthMm: Double? = devices.objOrNull("lens")?.numOrNull("focalLength")?.times(1000)
    val zoomStep: Int? = devices.objOrNull("lens")?.numOrNull("zoomStep")?.toInt()
    val ndFilter: Boolean = (devices.objOrNull("ndfilter")?.numOrNull("exposureBias") ?: 0.0) != 0.0
    val make: String? = frame.objOrNull("camera")?.str("make")
    val model: String? = frame.objOrNull("camera")?.str("model")
    val firmware: String? = frame.objOrNull("camera")?.str("firmware")?.substringBefore(',')
    val batteryPercent: Double? = root.objOrNull("debug")?.numOrNull("batteryPercentage")
    val creativeMode: String? = frame.objOrNull("modes")?.str("creative")
    val manualControls: Boolean? = (frame.objOrNull("modes")?.get("manualControls") as? JsonPrimitive)?.booleanOrNull

    class Rggb(o: JsonObject) {
        val r = o.num("r").toFloat()
        val gr = o.num("gr").toFloat()
        val gb = o.num("gb").toFloat()
        val b = o.num("b").toFloat()
        val g get() = (gr + gb) / 2f
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(text: String): LfMetadata = try {
            LfMetadata(json.parseToJsonElement(text.trimEnd('\u0000', ' ', '\n', '\r')) as JsonObject)
        } catch (e: Exception) {
            throw IllegalArgumentException("Unreadable picture metadata: ${e.message}", e)
        }

        fun parse(bytes: ByteArray): LfMetadata = parse(String(bytes, Charsets.UTF_8))
    }
}

private val JsonElement.jsonObj get() = this as JsonObject
private fun JsonObject.obj(k: String): JsonObject = this[k] as? JsonObject ?: error("missing $k")
private fun JsonObject.objOrNull(k: String): JsonObject? = this[k] as? JsonObject
private fun JsonObject.arr(k: String): JsonArray = this[k] as? JsonArray ?: error("missing $k")
private fun JsonObject.num(k: String): Double = numOrNull(k) ?: error("missing $k")
private fun JsonObject.numOrNull(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement.num(): Double = (this as JsonPrimitive).doubleOrNull ?: error("not a number")
