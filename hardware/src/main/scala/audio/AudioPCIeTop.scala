package audio

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

class AudioPCIeTop(audioConfig: AudioConfig) extends Component {
  val io = new Bundle {
    // PCIe interface
    val pcie = new Bundle {
      val tx = master(new Bundle {
        val data = Bits(128 bits)
        val valid = Bool()
        val ready = in Bool()
        val last = Bool()
        val keepBits = Bits(16 bits)
      })
      
      val rx = slave(new Bundle {
        val data = Bits(128 bits)
        val valid = in Bool()
        val ready = Bool()
        val last = in Bool()
        val keepBits = Bits(16 bits)
      })
      
      val cfg = new Bundle {
        val addr = in UInt(12 bits)
        val write = in Bool()
        val writeData = in Bits(32 bits)
        val read = in Bool()
        val readData = out Bits(32 bits)
      }
    }
    
    // Audio interface
    val audio = new Bundle {
      val mclk44k1 = in Bool()
      val mclk48k = in Bool()
      val i2s = master(new Bundle {
        val sck = Bool()
        val ws = Bool()
        val sd = Vec(Bool(), audioConfig.channelCount)
      })
    }
    
    // Interrupt
    val interrupt = out Bool()
  }
  
  // Clock domains
  val pcieClock = ClockDomain.current
  
  val audioClock = ClockDomain(
    clock = Mux(
      RegNext(audioReg.control.sampleRateFamily === SampleRateFamily.SF_44K1),
      io.audio.mclk44k1,
      io.audio.mclk48k
    ),
    reset = pcieClock.reset
  )
  
  // Register bank
  val audioReg = RegisterBank()
  
  // PCIe configuration
  val pcieConfig = new PCIeConfigHandler
  pcieConfig.io.cfg <> io.pcie.cfg
  
  // DMA engine
  val dmaEngine = new DMAEngine(
    audioConfig,
    PCIeConfig(
      maxReadRequestSize = 512,
      maxPayloadSize = 256,
      completionTimeout = 0xA,
      relaxedOrdering = true,
      extendedTags = true,
      maxTags = 32
    )
  )
  
  // Audio processor
  val audioProcessor = new AudioProcessor(audioConfig)
  
  // Clock domain crossing
  val clockCrossing = new AudioCDC(audioConfig)
  
  // Register interface
  val regInterface = new Area {
    val axiLite = Axi4(
      config = Axi4Config(
        addressWidth = 12,
        dataWidth = 32,
        idWidth = 1
      )
    )
    
    val bridge = Axi4SlaveFactory(axiLite)
    
    // Map registers
    bridge.readAndWrite(audioReg.control.format, 0x000)
    bridge.readAndWrite(audioReg.control.sampleRateFamily, 0x004)
    bridge.readAndWrite(audioReg.control.sampleRateMulti, 0x008)
    bridge.readAndWrite(audioReg.control.dsdMode, 0x00C)
    bridge.readAndWrite(audioReg.control.clockSource, 0x010)
    bridge.readAndWrite(audioReg.control.masterMode, 0x014)
    bridge.readAndWrite(audioReg.control.playbackEnable, 0x018)
    bridge.readAndWrite(audioReg.control.captureEnable, 0x01C)
    bridge.readAndWrite(audioReg.control.reset, 0x020)
    
    // Advanced control registers
    bridge.readAndWrite(audioReg.control.mclkFrequency, 0x030)
    bridge.readAndWrite(audioReg.control.targetSampleRate, 0x034)
    bridge.readAndWrite(audioReg.control.pbBufferThreshold, 0x038)
    bridge.readAndWrite(audioReg.control.capBufferThreshold, 0x03C)
    bridge.readAndWrite(audioReg.control.i2sFormat.bitDepth, 0x040)
    bridge.readAndWrite(audioReg.control.i2sFormat.alignment, 0x044)
    bridge.readAndWrite(audioReg.control.i2sFormat.tdm, 0x048)
    bridge.readAndWrite(audioReg.control.i2sFormat.tdmSlots, 0x04C)
    
    // Map all DMA registers
    bridge.readAndWrite(audioReg.dma.pbDescBaseAddr, 0x100)
    bridge.readAndWrite(audioReg.dma.pbDescCount, 0x108)
    bridge.read(audioReg.dma.pbCurrentDesc, 0x10C)
    bridge.readAndWrite(audioReg.dma.pbBufferSize, 0x110)
    bridge.readAndWrite(audioReg.dma.pbInterruptEnable, 0x114)
    
    bridge.readAndWrite(audioReg.dma.capDescBaseAddr, 0x200)
    bridge.readAndWrite(audioReg.dma.capDescCount, 0x208)
    bridge.read(audioReg.dma.capCurrentDesc, 0x20C)
    bridge.readAndWrite(audioReg.dma.capBufferSize, 0x210)
    bridge.readAndWrite(audioReg.dma.capInterruptEnable, 0x214)
    
    // Map status registers
    bridge.read(audioReg.status.locked, 0x300)
    bridge.read(audioReg.status.actualRate, 0x304)
    bridge.read(audioReg.status.clockSource, 0x308)
    bridge.read(audioReg.status.pbUnderrun, 0x30C)
    bridge.read(audioReg.status.capOverrun, 0x310)
    bridge.read(audioReg.status.dmaError, 0x314)
    
    // Extended status registers
    bridge.read(audioReg.status.clockStatus.mclkFrequency, 0x400)
    bridge.read(audioReg.status.clockStatus.bclkFrequency, 0x404)
    bridge.read(audioReg.status.clockStatus.actualSampleRate, 0x408)
    bridge.read(audioReg.status.clockStatus.mclkValid, 0x40C)
    bridge.read(audioReg.status.clockStatus.bclkValid, 0x410)
  }
  
  // Interrupt handling
  val interruptControl = new Area {
    val sources = new Bundle {
      val pbComplete = Bool()
      val pbUnderrun = Bool()
      val capComplete = Bool()
      val capOverrun = Bool()
      val clockUnlock = Bool()
      val dmaError = Bool()
    }
    
    // Interrupt status and masking
    val status = Reg(Bits(32 bits)) init(0)
    val mask = Reg(Bits(32 bits)) init(0)
    
    // Map interrupt sources
    sources.pbComplete := dmaEngine.io.control.pbComplete
    sources.pbUnderrun := audioReg.status.pbUnderrun
    sources.capComplete := dmaEngine.io.control.capComplete
    sources.capOverrun := audioReg.status.capOverrun
    sources.clockUnlock := !clockCrossing.io.pcie.status.clockLocked
    sources.dmaError := audioReg.status.dmaError
    
    // Generate interrupt
    io.interrupt := (status & mask).orR
  }
  
  // Reset logic
  val resetLogic = new Area {
    val resetCounter = Counter(16)
    val resetActive = RegInit(True)
    
    when(audioReg.control.reset) {
      resetActive := True
      resetCounter.clear()
    }
    
    when(resetActive) {
      resetCounter.increment()
      when(resetCounter.willOverflow) {
        resetActive := False
      }
    }
  }
  
  // Connect DMA engine to PCIe
  dmaEngine.io.axi <> io.pcie.tx  // Simplified - actual implementation needs AXI-to-PCIe bridge
  
  // Connect audio processor
  audioProcessor.io.format := clockCrossing.io.audio.control.format
  audioProcessor.io.sampleRateFamily := clockCrossing.io.audio.control.sampleRateFamily
  audioProcessor.io.sampleRateMulti := clockCrossing.io.audio.control.sampleRateMulti
  audioProcessor.io.dsdMode := clockCrossing.io.audio.control.dsdMode
  audioProcessor.io.masterMode := clockCrossing.io.audio.control.masterMode
  
  // Connect physical I2S interface
  io.audio.i2s.sck := audioProcessor.io.i2s.sck
  io.audio.i2s.ws := audioProcessor.io.i2s.ws
  io.audio.i2s.sd := audioProcessor.io.i2s.sd
  
  // Connect clock crossing
  clockCrossing.io.pcie.txData <> dmaEngine.io.audioOut
  clockCrossing.io.pcie.rxData <> dmaEngine.io.audioIn
  clockCrossing.io.audio.txData <> audioProcessor.io.txData
  clockCrossing.io.audio.rxData <> audioProcessor.io.rxData
  
  // Status monitoring
  val statusMonitor = new Area {
    // Track key metrics
    val pbUnderrunCount = Reg(UInt(16 bits)) init(0)
    val capOverrunCount = Reg(UInt(16 bits)) init(0)
    val clockUnlockCount = Reg(UInt(16 bits)) init(0)
    val dmaErrorCount = Reg(UInt(16 bits)) init(0)
    
    // Update counters
    when(audioReg.status.pbUnderrun) { pbUnderrunCount := pbUnderrunCount + 1 }
    when(audioReg.status.capOverrun) { capOverrunCount := capOverrunCount + 1 }
    when(!clockCrossing.io.pcie.status.clockLocked) { clockUnlockCount := clockUnlockCount + 1 }
    when(audioReg.status.dmaError) { dmaErrorCount := dmaErrorCount + 1 }
  }
}

// Generate Verilog
object AudioPCIeTopVerilog {
  def main(args: Array[String]): Unit = {
    val config = SpinalConfig(
      defaultConfigForClockDomains = ClockDomainConfig(
        resetKind = SYNC,
        resetActiveLevel = LOW
      ),
      targetDirectory = "rtl/generated"
    )
    
    config.generateVerilog(new AudioPCIeTop(
      AudioConfig(
        channelCount = 8,
        i2sDataWidth = 24,
        dsdBitWidth = 1,
        useMultipleClocks = true,
        supportDsd = true,
        bufferSize = 8192,
        bufferCount = 4,
        maxBurstSize = 512,
        fifoDepth = 1024,
        dmaDescriptorCount = 32
      )
    ))
  }
}