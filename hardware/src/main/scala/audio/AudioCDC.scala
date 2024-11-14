package audio

import spinal.core._
import spinal.lib._

class AudioCDC(config: AudioConfig) extends Component {
  val io = new Bundle {
    // PCIe clock domain interface
    val pcie = new Bundle {
      val txData = slave Stream(Vec(Bits(config.i2sDataWidth bits), config.channelCount))
      val rxData = master Stream(Vec(Bits(config.i2sDataWidth bits), config.channelCount))
      
      val control = new Bundle {
        val format = in(AudioFormat())
        val sampleRateFamily = in(SampleRateFamily())
        val sampleRateMulti = in UInt(4 bits)
        val dsdMode = in UInt(2 bits)
        val masterMode = in Bool()
      }
      
      val status = new Bundle {
        val clockLocked = out Bool()
        val actualRate = out UInt(32 bits)
        val bufferLevel = out UInt(16 bits)
        val underrun = out Bool()
        val overrun = out Bool()
      }
    }
    
    // Audio clock domain interface
    val audio = new Bundle {
      val txData = master Stream(Vec(Bits(config.i2sDataWidth bits), config.channelCount))
      val rxData = slave Stream(Vec(Bits(config.i2sDataWidth bits), config.channelCount))
      
      val control = new Bundle {
        val format = out(AudioFormat())
        val sampleRateFamily = out(SampleRateFamily())
        val sampleRateMulti = out UInt(4 bits)
        val dsdMode = out UInt(2 bits)
        val masterMode = out Bool()
      }
      
      val status = new Bundle {
        val clockLocked = in Bool()
        val actualRate = in UInt(32 bits)
      }
    }
  }
  
  // Clock crossing FIFOs for audio data
  val txFifo = StreamFifoCC(
    dataType = Vec(Bits(config.i2sDataWidth bits), config.channelCount),
    depth = config.fifoDepth,
    pushClock = ClockDomain.current,
    popClock = AudioClockDomain
  )
  
  val rxFifo = StreamFifoCC(
    dataType = Vec(Bits(config.i2sDataWidth bits), config.channelCount),
    depth = config.fifoDepth,
    pushClock = AudioClockDomain,
    popClock = ClockDomain.current
  )
  
  // Connect data paths through FIFOs
  txFifo.io.push << io.pcie.txData
  io.audio.txData << txFifo.io.pop
  
  rxFifo.io.push << io.audio.rxData
  io.pcie.rxData << rxFifo.io.pop
  
  // Cross control signals (PCIe -> Audio)
  val controlCrossing = new Area {
    // Use BufferCC for control signals
    io.audio.control.format := BufferCC(
      input = io.pcie.control.format,
      init = AudioFormat.I2S,
      bufferDepth = 2
    )
    
    io.audio.control.sampleRateFamily := BufferCC(
      input = io.pcie.control.sampleRateFamily,
      init = SampleRateFamily.SF_48K,
      bufferDepth = 2
    )
    
    io.audio.control.sampleRateMulti := BufferCC(
      input = io.pcie.control.sampleRateMulti,
      init = U(0),
      bufferDepth = 2
    )
    
    io.audio.control.dsdMode := BufferCC(
      input = io.pcie.control.dsdMode,
      init = U(0),
      bufferDepth = 2
    )
    
    io.audio.control.masterMode := BufferCC(
      input = io.pcie.control.masterMode,
      init = False,
      bufferDepth = 2
    )
  }
  
  // Cross status signals (Audio -> PCIe)
  val statusCrossing = new Area {
    // Use BufferCC for status signals
    io.pcie.status.clockLocked := BufferCC(
      input = io.audio.status.clockLocked,
      init = False,
      bufferDepth = 2
    )
    
    io.pcie.status.actualRate := BufferCC(
      input = io.audio.status.actualRate,
      init = U(0),
      bufferDepth = 2
    )
    
    // FIFO status
    io.pcie.status.bufferLevel := txFifo.io.occupancy.resized
    
    // Error conditions
    io.pcie.status.underrun := txFifo.io.empty && io.audio.txData.ready
    io.pcie.status.overrun := rxFifo.io.full && io.audio.rxData.valid
  }
  
  // Optional - Buffer monitoring and management
  val bufferMonitor = new Area {
    // Track FIFO levels in PCIe domain
    val txLevel = RegNext(txFifo.io.occupancy)
    val rxLevel = RegNext(rxFifo.io.occupancy)
    
    // Thresholds for buffer warnings (configurable)
    val txLowThreshold = U(config.fifoDepth / 4)
    val txHighThreshold = U((config.fifoDepth * 3) / 4)
    val rxLowThreshold = U(config.fifoDepth / 4)
    val rxHighThreshold = U((config.fifoDepth * 3) / 4)
    
    // Warning flags
    val txLow = txLevel < txLowThreshold
    val txHigh = txLevel > txHighThreshold
    val rxLow = rxLevel < rxLowThreshold
    val rxHigh = rxLevel > rxHighThreshold
    
    // Buffer recovery logic
    val recoveryMode = RegInit(False)
    
    when(txLow || rxHigh) {
      recoveryMode := True
    }.elsewhen(txLevel > txLowThreshold && rxLevel < rxHighThreshold) {
      recoveryMode := False
    }
  }
  
  // Debug features (synthesis time removable)
  val debug = new Area {
    // Crossing monitors
    val txCrossingCount = Reg(UInt(32 bits)) init(0)
    val rxCrossingCount = Reg(UInt(32 bits)) init(0)
    
    when(txFifo.io.push.fire) {
      txCrossingCount := txCrossingCount + 1
    }
    
    when(rxFifo.io.pop.fire) {
      rxCrossingCount := rxCrossingCount + 1
    }
    
    // Error counters
    val underrunCount = Reg(UInt(16 bits)) init(0)
    val overrunCount = Reg(UInt(16 bits)) init(0)
    
    when(io.pcie.status.underrun) {
      underrunCount := underrunCount + 1
    }
    
    when(io.pcie.status.overrun) {
      overrunCount := overrunCount + 1
    }
  }
  
  // Generate report of CDC status (useful for verification)
  def generateReport(): String = {
    s"""CDC Status Report:
       |TX FIFO Depth: ${config.fifoDepth}
       |RX FIFO Depth: ${config.fifoDepth}
       |TX Crossings: ${debug.txCrossingCount}
       |RX Crossings: ${debug.rxCrossingCount}
       |Underruns: ${debug.underrunCount}
       |Overruns: ${debug.overrunCount}
       |""".stripMargin
  }
}