package com.spop.poverlay.util

import android.os.Build
import kotlin.math.pow
import kotlin.math.sqrt

private const val PelotonBrand = "Peloton"

val IsRunningOnPeloton = Build.BRAND == PelotonBrand

/**
 * I'm not sure if this is the best way to determine if the device is a Peloton Bike+.
 * I'm assuming that the model name is the same for all Peloton Bike+ devices based on what I'm able
 * to find online.
 */
val IsBikePlus = Build.MODEL == "PLTN-TTR01"

fun calculateSpeedFromPelotonV1Power(power: Float) =
    if (power < 0.1f) {
        0f
    } else {
        //https://ihaque.org/posts/2020/12/25/pelomon-part-ib-computing-speed/
        val pwrSqrt = sqrt(power)
        if (power < 26f) {
            0.057f - (0.172f * pwrSqrt) + (0.759f * pwrSqrt.pow(2)) - (0.079f * pwrSqrt.pow(3))
        } else {
            -1.635f + (2.325f * pwrSqrt) - (0.064f * pwrSqrt.pow(2)) + (0.001f * pwrSqrt.pow(3))
        }
    }

// Coefficients for the backpedal power estimate (see estimateForwardPower).
// watts = cadence * (base + resistanceGain * normalizedResistance), e.g. 60 rpm @ 30% ~= 53 W.
private const val BackpedalPowerBaseFactor = 0.4f
private const val BackpedalPowerResistanceGain = 1.6f
private const val BackpedalPowerMaxWatts = 300f

// A backward spin reports real cadence but essentially no measured power; forward pedaling always
// produces power, so this pair of thresholds only ever matches backpedaling.
const val BackpedalPowerEpsilonWatts = 1f
const val BackpedalCadenceEpsilonRpm = 1f

/**
 * Returns the power (W) to surface for a raw sample of [power], [cadence] and [resistance],
 * substituting an [estimateForwardPower] value when the backpedal signature (real cadence, ~0
 * measured power) is present and passing the real reading through otherwise.
 */
fun compensateBackpedalPower(power: Float, cadence: Float, resistance: Float): Float =
    if (power < BackpedalPowerEpsilonWatts && cadence > BackpedalCadenceEpsilonRpm) {
        estimateForwardPower(cadence, resistance)
    } else {
        power
    }

/**
 * Estimates the power (W) an equivalent *forward* pedaling effort would produce for a given
 * [cadence] (rpm) and normalized [resistance] (0-100%).
 *
 * The Peloton measures real power from crank torque via a load cell, so pedaling backward reports
 * ~0 W even though the legs are moving (and speed/graph collapse to 0 with it, since both derive
 * from power). This synthesizes a plausible easy-spin wattage so recovery backpedaling looks like
 * forward pedaling on the gauge, graph, speed, and recorded output.
 *
 * There is no official cadence/resistance -> power curve (power is measured, not modeled), so this
 * is a deliberately simple linear-in-cadence, linear-in-resistance approximation tuned to land in a
 * believable easy/moderate spin range.
 */
fun estimateForwardPower(cadence: Float, resistance: Float): Float {
    if (cadence <= 0f) return 0f
    val normalizedResistance = (resistance / 100f).coerceIn(0f, 1f)
    val watts = cadence * (BackpedalPowerBaseFactor + BackpedalPowerResistanceGain * normalizedResistance)
    return watts.coerceIn(0f, BackpedalPowerMaxWatts)
}