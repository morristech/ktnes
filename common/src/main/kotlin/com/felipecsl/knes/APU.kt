package com.felipecsl.knes

internal class APU(
    private val audioSink: AudioSink,
    // Convert samples per second to cpu steps per sample
    private val sampleRate: Double = CPU.FREQUENCY / SAMPLE_RATE.toDouble(),
    private var cycle: Long = 0,
    private var framePeriod: Int = 0, // Byte
    private var frameValue: Int = 0, // Byte
    private var frameIRQ: Boolean = false,
    private val pulseTable: FloatArray = FloatArray(31),
    private val tndTable: FloatArray = FloatArray(203),
    private val filterChain: FilterChain = FilterChain(arrayOf(
        highPassFilter(SAMPLE_RATE, 90F),
        highPassFilter(SAMPLE_RATE, 440F),
        lowPassFilter(SAMPLE_RATE, 14000F)
    ))
) {
  private var pulse1Enabled: Boolean = false
  private var pulse1Channel: Int = 1 // Byte
  private var pulse1LengthEnabled: Boolean = false
  private var pulse1LengthValue: Int = 0 // Byte
  private var pulse1TimerPeriod: Int = 0
  private var pulse1TimerValue: Int = 0
  private var pulse1DutyMode: Int = 0 // Byte
  private var pulse1DutyValue: Int = 0 // Byte
  private var pulse1SweepReload: Boolean = false
  private var pulse1SweepEnabled: Boolean = false
  private var pulse1SweepNegate: Boolean = false
  private var pulse1SweepShift: Int = 0 // Byte
  private var pulse1SweepPeriod: Int = 0 // Byte
  private var pulse1SweepValue: Int = 0 // Byte
  private var pulse1EnvelopeEnabled: Boolean = false
  private var pulse1EnvelopeLoop: Boolean = false
  private var pulse1EnvelopeStart: Boolean = false
  private var pulse1EnvelopePeriod: Int = 0 // Byte
  private var pulse1EnvelopeValue: Int = 0 // Byte
  private var pulse1EnvelopeVolume: Int = 0 // Byte
  private var pulse1ConstantVolume: Int = 0 // Byte

  private var pulse2Enabled: Boolean = false
  private var pulse2Channel: Int = 2 // Byte
  private var pulse2LengthEnabled: Boolean = false
  private var pulse2LengthValue: Int = 0 // Byte
  private var pulse2TimerPeriod: Int = 0
  private var pulse2TimerValue: Int = 0
  private var pulse2DutyMode: Int = 0 // Byte
  private var pulse2DutyValue: Int = 0 // Byte
  private var pulse2SweepReload: Boolean = false
  private var pulse2SweepEnabled: Boolean = false
  private var pulse2SweepNegate: Boolean = false
  private var pulse2SweepShift: Int = 0 // Byte
  private var pulse2SweepPeriod: Int = 0 // Byte
  private var pulse2SweepValue: Int = 0 // Byte
  private var pulse2EnvelopeEnabled: Boolean = false
  private var pulse2EnvelopeLoop: Boolean = false
  private var pulse2EnvelopeStart: Boolean = false
  private var pulse2EnvelopePeriod: Int = 0 // Byte
  private var pulse2EnvelopeValue: Int = 0 // Byte
  private var pulse2EnvelopeVolume: Int = 0 // Byte
  private var pulse2ConstantVolume: Int = 0 // Byte

  private var noiseEnabled: Boolean = false
  private var noiseMode: Boolean = false
  private var noiseShiftRegister: Int = 1
  private var noiseLengthEnabled: Boolean = false
  private var noiseLengthValue: Int = 0  // Byte
  private var noiseTimerPeriod: Int = 0
  private var noiseTimerValue: Int = 0
  private var noiseEnvelopeEnabled: Boolean = false
  private var noiseEnvelopeLoop: Boolean = false
  private var noiseEnvelopeStart: Boolean = false
  private var noiseEnvelopePeriod: Int = 0  // Byte
  private var noiseEnvelopeValue: Int = 0  // Byte
  private var noiseEnvelopeVolume: Int = 0  // Byte
  private var noiseConstantVolume: Int = 0  // Byte

  private var triangleEnabled: Boolean = false
  private var triangleLengthEnabled: Boolean = false
  private var triangleLengthValue: Int = 0 // Byte
  private var triangleTimerPeriod: Int = 0
  private var triangleTimerValue: Int = 0
  private var triangleDutyValue: Int = 0 // Byte
  private var triangleCounterPeriod: Int = 0 // Byte
  private var triangleCounterValue: Int = 0 // Byte
  private var triangleCounterReload: Boolean = false

  private var dmcEnabled: Boolean = false
  private var dmcValue: Int = 0 // Byte
  private var dmcSampleAddress: Int = 0
  private var dmcSampleLength: Int = 0
  private var dmcCurrentAddress: Int = 0
  private var dmcCurrentLength: Int = 0
  private var dmcShiftRegister: Int = 0 // Byte
  private var dmcBitCount: Int = 0 // Byte
  private var dmcTickPeriod: Int = 0 // Byte
  private var dmcTickValue: Int = 0 // Byte
  private var dmcLoop: Boolean = false
  private var dmcIrq: Boolean = false
  lateinit var cpu: CPU

  init {
    for (i in 0 until 31) {
      pulseTable[i] = (95.52 / (8128F / i.toFloat() + 100)).toFloat()
    }
    for (i in 0 until 203) {
      tndTable[i] = (163.67 / (24329F / i.toFloat() + 100)).toFloat()
    }
  }

  fun readRegister(address: Int): Int /* Byte */ {
    return when (address) {
      0x4015 -> {
        // read status
        var result = 0
        if (pulse1LengthValue > 0) {
          result = result or 1
        }
        if (pulse2LengthValue > 0) {
          result = result or 2
        }
        if (triangleLengthValue > 0) {
          result = result or 4
        }
        if (noiseLengthValue > 0) {
          result = result or 8
        }
        if (dmcCurrentLength > 0) {
          result = result or 16
        }
        result
      }
      else -> 0
    }
  }

  private fun dmcRestart() {
    dmcCurrentAddress = dmcSampleAddress
    dmcCurrentLength = dmcSampleLength
  }

  fun step() {
    val cycle1 = cycle
    cycle++
    val cycle2 = cycle
    // step timer
    if (cycle % 2 == 0L) {
      // pulse 1 step timer
      if (pulse1TimerValue == 0) {
        pulse1TimerValue = pulse1TimerPeriod
        pulse1DutyValue = (pulse1DutyValue + 1) % 8
      } else {
        pulse1TimerValue--
      }
      // pulse 2 step timer
      if (pulse2TimerValue == 0) {
        pulse2TimerValue = pulse2TimerPeriod
        pulse2DutyValue = (pulse2DutyValue + 1) % 8
      } else {
        pulse2TimerValue--
      }
      // noise step timer
      if (noiseTimerValue == 0) {
        noiseTimerValue = noiseTimerPeriod
        val shift = if (noiseMode) 6 else 1
        val b1 = noiseShiftRegister and 1
        val b2 = (noiseShiftRegister ushr shift) and 1
        noiseShiftRegister = noiseShiftRegister ushr 1
        noiseShiftRegister = noiseShiftRegister or ((b1 xor b2) shl 14)
      } else {
        noiseTimerValue--
      }
      // dmc step timer
      if (dmcEnabled) {
        // dmc step reader
        if (dmcCurrentLength > 0 && dmcBitCount == 0) {
          cpu.stall += 4
          dmcShiftRegister = cpu.read(dmcCurrentAddress)
          dmcBitCount = 8
          dmcCurrentAddress++
          if (dmcCurrentAddress == 0) {
            dmcCurrentAddress = 0x8000
          }
          dmcCurrentLength--
          if (dmcCurrentLength == 0 && dmcLoop) {
            dmcRestart()
          }
        }
        if (dmcTickValue == 0) {
          dmcTickValue = dmcTickPeriod
          // dmc step shifter
          if (dmcBitCount != 0) {
            if (dmcShiftRegister and 1 == 1) {
              if (dmcValue <= 125) {
                dmcValue += 2 and 0xFF
                dmcValue
              }
            } else {
              if (dmcValue >= 2) {
                dmcValue -= 2 and 0xFF
                dmcValue
              }
            }
            dmcShiftRegister = (dmcShiftRegister ushr 1) and 0xFF
            dmcBitCount -= 1 and 0xFF
          }
        } else {
          dmcTickValue -= 1 and 0xFF
        }
      }
    }
    // triangle step timer
    if (triangleTimerValue == 0) {
      triangleTimerValue = triangleTimerPeriod
      if (triangleLengthValue > 0 && triangleCounterValue > 0) {
        triangleDutyValue = (triangleDutyValue + 1) % 32
      }
    } else {
      triangleTimerValue--
    }
    if ((cycle1.toFloat() / FRAME_COUNTER_RATE).toInt() != (cycle2.toFloat() / FRAME_COUNTER_RATE).toInt()) {
      // step frame counter
      when (framePeriod) {
        4 -> {
          frameValue = (frameValue + 1) % 4
          when (frameValue) {
            0, 2 -> stepEnvelope()
            1 -> {
              stepEnvelope()
              stepSweep()
              stepLength()
            }
            3 -> {
              stepEnvelope()
              stepSweep()
              stepLength()
              // fire irq
              if (frameIRQ) {
                cpu.interrupt = Interrupt.IRQ
              }
            }
          }
        }
        5 -> {
          frameValue = (frameValue + 1) % 5
          when (frameValue) {
            1, 3 -> stepEnvelope()
            0, 2 -> {
              stepEnvelope()
              stepSweep()
              stepLength()
            }
          }
        }
      }
    }
    if ((cycle1.toFloat() / sampleRate).toInt() != (cycle2.toFloat() / sampleRate).toInt()) {
      val output = pulseTable[pulse1Output() + pulse2Output()] +
          tndTable[3 * triangleOutput() + 2 * noiseOutput() + dmcValue]
      audioSink.write(filterChain.step(output))
    }
  }

  private fun pulse1Output(): Int /* Byte */ {
    return if (!pulse1Enabled
        || pulse1LengthValue == 0
        || DUTY_TABLE[pulse1DutyMode][pulse1DutyValue] == 0
        || pulse1TimerPeriod < 8
        || pulse1TimerPeriod > 0x7FF) {
      0
    } else {
      if (pulse1EnvelopeEnabled) pulse1EnvelopeVolume else pulse1ConstantVolume
    }
  }

  private fun pulse2Output(): Int /* Byte */ {
    return if (!pulse2Enabled
        || pulse2LengthValue == 0
        || DUTY_TABLE[pulse2DutyMode][pulse2DutyValue] == 0
        || pulse2TimerPeriod < 8
        || pulse2TimerPeriod > 0x7FF) {
      0
    } else {
      if (pulse2EnvelopeEnabled) pulse2EnvelopeVolume else pulse2ConstantVolume
    }
  }

  private fun noiseOutput(): Int /* Byte */ {
    return if (!noiseEnabled || noiseLengthValue == 0 || noiseShiftRegister and 1 == 1) {
      0
    } else {
      if (noiseEnvelopeEnabled) noiseEnvelopeVolume else noiseConstantVolume
    }
  }

  private fun triangleOutput(): Int /* Byte */ {
    return if (!triangleEnabled || triangleLengthValue == 0 || triangleCounterValue == 0) 0 else
      TRIANGLE_TABLE[triangleDutyValue]
  }

  fun writeRegister(address: Int, value: Int /* Byte */) {
    when (address) {
      0x4000 -> {
        // pulse 1 write control
        pulse1DutyMode = ((value ushr 6) and 3)
        pulse1LengthEnabled = (value ushr 5) and 1 == 0
        pulse1EnvelopeLoop = (value ushr 5) and 1 == 1
        pulse1EnvelopeEnabled = (value ushr 4) and 1 == 0
        pulse1EnvelopePeriod = (value and 15)
        pulse1ConstantVolume = (value and 15)
        pulse1EnvelopeStart = true
      }
      0x4001 -> {
        // pulse 1 write sweep
        pulse1SweepEnabled = (value ushr 7) and 1 == 1
        pulse1SweepPeriod = ((value ushr 4) and 7 + 1) and 0xFF
        pulse1SweepNegate = (value ushr 3) and 1 == 1
        pulse1SweepShift = value and 7
        pulse1SweepReload = true
      }
      0x4002 -> pulse1TimerPeriod = (pulse1TimerPeriod and 0xFF00) or value
      0x4003 -> {
        // pulse 1 write timer high
        pulse1LengthValue = LENGTH_TABLE[value ushr 3] and 0xFF
        pulse1TimerPeriod = (pulse1TimerPeriod and 0x00FF) or ((value and 7) shl 8)
        pulse1EnvelopeStart = true
        pulse1DutyValue = 0
      }
      0x4004 -> {
        // pulse 2 write control
        pulse2DutyMode = ((value ushr 6) and 3)
        pulse2LengthEnabled = (value ushr 5) and 1 == 0
        pulse2EnvelopeLoop = (value ushr 5) and 1 == 1
        pulse2EnvelopeEnabled = (value ushr 4) and 1 == 0
        pulse2EnvelopePeriod = (value and 15)
        pulse2ConstantVolume = (value and 15)
        pulse2EnvelopeStart = true
      }
      0x4005 -> {
        // pulse 2 write sweep
        pulse2SweepEnabled = (value ushr 7) and 1 == 1
        pulse2SweepPeriod = ((value ushr 4) and 7 + 1) and 0xFF
        pulse2SweepNegate = (value ushr 3) and 1 == 1
        pulse2SweepShift = value and 7
        pulse2SweepReload = true
      }
      0x4006 -> pulse2TimerPeriod = (pulse2TimerPeriod and 0xFF00) or value
      0x4007 -> {
        // pulse 2 write timer high
        pulse2LengthValue = LENGTH_TABLE[value ushr 3]
        pulse2TimerPeriod = (pulse2TimerPeriod and 0x00FF) or ((value and 7) shl 8)
        pulse2EnvelopeStart = true
        pulse2DutyValue = 0
      }
      0x4008 -> {
        // triangle write control
        triangleLengthEnabled = (value ushr 7) and 1 == 0
        triangleCounterPeriod = (value and 0x7F)
      }
      0x4009, 0x4010 -> {
        // dmc write control
        dmcIrq = value and 0x80 == 0x80
        dmcLoop = value and 0x40 == 0x40
        dmcTickPeriod = DMC_TABLE[value and 0x0F]
      }
      0x4011 -> {
        // dmc write value
        dmcValue = value and 0x7F
      }
      0x4012 -> {
        // dmc write address
        dmcSampleAddress = 0xC000 or (value shl 6)
      }
      0x4013 -> {
        // dmc write length
        dmcSampleLength = (value shl 4) or 1
      }
      0x400A -> {
        // triangle write timer low
        triangleTimerPeriod = (triangleTimerPeriod and 0xFF00) or value
      }
      0x400B -> {
        // triangle write timer high
        triangleLengthValue = (LENGTH_TABLE[value ushr 3]) and 0xFF
        triangleTimerPeriod = (triangleTimerPeriod and 0x00FF) or ((value and 7) shl 8)
        triangleTimerValue = triangleTimerPeriod
        triangleCounterReload = true
      }
      0x400C -> {
        // noise write control
        noiseLengthEnabled = (value ushr 5) and 1 == 0
        noiseEnvelopeLoop = (value ushr 5) and 1 == 1
        noiseEnvelopeEnabled = (value ushr 4) and 1 == 0
        noiseEnvelopePeriod = (value and 15)
        noiseConstantVolume = (value and 15)
        noiseEnvelopeStart = true
      }
      0x400D, 0x400E -> {
        // noise write period
        noiseMode = value and 0x80 == 0x80
        noiseTimerPeriod = NOISE_TABLE[value and 0x0F]
      }
      0x400F -> {
        // noise write length
        noiseLengthValue = LENGTH_TABLE[value ushr 3]
        noiseEnvelopeStart = true
      }
      0x4015 -> {
        // write control
        pulse1Enabled = value and 1 == 1
        pulse2Enabled = value and 2 == 2
        triangleEnabled = value and 4 == 4
        noiseEnabled = value and 8 == 8
        dmcEnabled = value and 16 == 16
        if (!pulse1Enabled) {
          pulse1LengthValue = 0
        }
        if (!pulse2Enabled) {
          pulse2LengthValue = 0
        }
        if (!triangleEnabled) {
          triangleLengthValue = 0
        }
        if (!noiseEnabled) {
          noiseLengthValue = 0
        }
        if (!dmcEnabled) {
          dmcCurrentLength = 0
        } else {
          if (dmcCurrentLength == 0) {
            dmcRestart()
          }
        }
      }
      0x4017 -> {
        // write frame counter
        framePeriod = 4 + (value ushr 7) and 1
        frameIRQ = (value ushr 6) and 1 == 0
        if (framePeriod == 5) {
          stepEnvelope()
          stepSweep()
          stepLength()
        }
      }
    }
  }

  private inline fun stepLength() {
    // pulse 1
    if (pulse1LengthEnabled && pulse1LengthValue > 0) {
      pulse1LengthValue -= 1 and 0xFF
    }
    // pulse 2
    if (pulse2LengthEnabled && pulse2LengthValue > 0) {
      pulse2LengthValue -= 1 and 0xFF
    }
    // triangle
    if (triangleLengthEnabled && triangleLengthValue > 0) {
      triangleLengthValue -= 1 and 0xFF
    }
    // noise
    if (noiseLengthEnabled && noiseLengthValue > 0) {
      noiseLengthValue -= 1 and 0xFF
    }
  }

  private fun pulse1Sweep() {
    val delta = pulse1TimerPeriod ushr pulse1SweepShift
    if (pulse1SweepNegate) {
      pulse1TimerPeriod -= delta
      if (pulse1Channel == 1) {
        pulse1TimerPeriod--
      }
    } else {
      pulse1TimerPeriod += delta
    }
  }

  private fun pulse2Sweep() {
    val delta = pulse2TimerPeriod ushr pulse2SweepShift
    if (pulse2SweepNegate) {
      pulse2TimerPeriod -= delta
      if (pulse2Channel == 1) {
        pulse2TimerPeriod--
      }
    } else {
      pulse2TimerPeriod += delta
    }
  }

  private fun stepSweep() {
    // pulse 1 step sweep
    when {
      pulse1SweepReload -> {
        if (pulse1SweepEnabled && pulse1SweepValue == 0) {
          pulse1Sweep()
        }
        pulse1SweepValue = pulse1SweepPeriod
        pulse1SweepReload = false
      }
      pulse1SweepValue > 0 -> pulse1SweepValue -= 1 and 0xFF
      else -> {
        if (pulse1SweepEnabled) {
          pulse1Sweep()
        }
        pulse1SweepValue = pulse1SweepPeriod
      }
    }
    // pulse 2 step sweep
    when {
      pulse2SweepReload -> {
        if (pulse2SweepEnabled && pulse2SweepValue == 0) {
          pulse2Sweep()
        }
        pulse2SweepValue = pulse2SweepPeriod
        pulse2SweepReload = false
      }
      pulse2SweepValue > 0 -> pulse2SweepValue -= 1 and 0xFF
      else -> {
        if (pulse2SweepEnabled) {
          pulse2Sweep()
        }
        pulse2SweepValue = pulse2SweepPeriod
      }
    }
  }

  private fun stepEnvelope() {
    // pulse 1 step envelope
    when {
      pulse1EnvelopeStart -> {
        pulse1EnvelopeVolume = 15
        pulse1EnvelopeValue = pulse1EnvelopePeriod
        pulse1EnvelopeStart = false
      }
      pulse1EnvelopeValue > 0 -> pulse1EnvelopeValue -= 1 and 0xFF
      else -> {
        if (pulse1EnvelopeVolume > 0) {
          pulse1EnvelopeVolume -= 1 and 0xFF
        } else if (pulse1EnvelopeLoop) {
          pulse1EnvelopeVolume = 15
        }
        pulse1EnvelopeValue = pulse1EnvelopePeriod
      }
    }
    // pulse 2 step envelope
    when {
      pulse2EnvelopeStart -> {
        pulse2EnvelopeVolume = 15
        pulse2EnvelopeValue = pulse2EnvelopePeriod
        pulse2EnvelopeStart = false
      }
      pulse2EnvelopeValue > 0 -> pulse2EnvelopeValue--
      else -> {
        if (pulse2EnvelopeVolume > 0) {
          pulse2EnvelopeVolume -= 1 and 0xFF
        } else if (pulse2EnvelopeLoop) {
          pulse2EnvelopeVolume = 15
        }
        pulse2EnvelopeValue = pulse2EnvelopePeriod
      }
    }
    // triangle step counter
    if (triangleCounterReload) {
      triangleCounterValue = triangleCounterPeriod
    } else if (triangleCounterValue > 0) {
      triangleCounterValue -= 1 and 0xFF
    }
    if (triangleLengthEnabled) {
      triangleCounterReload = false
    }
    // noise step envelope
    when {
      noiseEnvelopeStart -> {
        noiseEnvelopeVolume = 15
        noiseEnvelopeValue = noiseEnvelopePeriod
        noiseEnvelopeStart = false
      }
      noiseEnvelopeValue > 0 -> noiseEnvelopeValue -=1 and 0xFF
      else -> {
        if (noiseEnvelopeVolume > 0) {
          noiseEnvelopeVolume -= 1 and 0xFF
        } else if (noiseEnvelopeLoop) {
          noiseEnvelopeVolume = 15
        }
        noiseEnvelopeValue = noiseEnvelopePeriod
      }
    }
  }

  companion object {
    internal const val SAMPLE_RATE = 44100F
    private const val FRAME_COUNTER_RATE = CPU.FREQUENCY / 240.0
    private val TRIANGLE_TABLE = intArrayOf( // Byte
        15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
    )
    private val NOISE_TABLE = intArrayOf(
        4, 8, 16, 32, 64, 96, 128, 160, 202, 254, 380, 508, 762, 1016, 2034, 4068
    )
    private val LENGTH_TABLE = intArrayOf( // Byte
        10, 254, 20, 2, 40, 4, 80, 6, 160, 8, 60, 10, 14, 12, 26, 14,
        12, 16, 24, 18, 48, 20, 96, 22, 192, 24, 72, 26, 16, 28, 32, 30
    )
    private val DMC_TABLE = intArrayOf( // Byte
        214, 190, 170, 160, 143, 127, 113, 107, 95, 80, 71, 64, 53, 42, 36, 27
    )
    private val DUTY_TABLE = arrayOf( // Byte
        intArrayOf(0, 1, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 1, 1, 0, 0, 0, 0, 0),
        intArrayOf(0, 1, 1, 1, 1, 0, 0, 0),
        intArrayOf(1, 0, 0, 1, 1, 1, 1, 1)
    )
  }
}