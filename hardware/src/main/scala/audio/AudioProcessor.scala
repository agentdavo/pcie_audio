package audio

import spinal.core._
import spinal.lib._

class AudioProcessor(config: AudioConfig) extends Component {
  val io = new Bundle {
    // Clock interface
    val clocks = new Bundle {
      // Master clocks
      val mclk44k1 = if(config.useMultipleClocks) Some(in Bool()) else None
      val mclk48k = if(config.useMultipleClocks) Some(in Bool()) else None
      
      // Generated clocks
      val bclk = out Bool()  // Bit clock output
      val lrclk = out Bool() // Word clock output (I2S) / Frame clock (TDM)
      
      // Clock status
      val clockLocked = out Bool()
      val clockSlip = out Bool()
    }
    
    // Audio data interface - separate for clarity
    val i2s = new Bundle {
      val rx = Vec(in Bool(), config.channelCount)
      val tx = Vec(out Bool(), config.channelCount)
    }
    
    val tdm = new Bundle {
      val rx = in Bool()
      val tx = out Bool()
      val frameSync = out Bool()
      val slotNumber = out UInt(log2Up(config.tdmSlots) bits)
    }
    
    val dsd = if(config.supportDsd) new Bundle {
      val rx = Vec(in Bool(), config.dsdChannels)
      val tx = Vec(out Bool(), config.dsdChannels)
      val dsdClk = out Bool()  // DSD bit clock
    } else null
    
    // Stream interfaces for data
    val rxStreams = Vec(master Stream(Bits(config.dataWidth bits)), config.channelCount)
    val txStreams = Vec(slave Stream(Bits(config.dataWidth bits)), config.channelCount)
    
    // Control/Status interface
    val control = new Bundle {
      val format = in(AudioFormat())
      val sampleRate = in UInt(32 bits)
      val clockSource = in UInt(2 bits)  // 00: Auto, 01: 44.1k, 10: 48k
      val masterMode = in Bool()
      val tdmConfig = new Bundle {
        val slotCount = in UInt(8 bits)
        val slotWidth = in UInt(8 bits)
        val frameSync = in Bool()
      }
      val dsdConfig = if(config.supportDsd) new Bundle {
        val dsdRate = in UInt(4 bits)  // 0: DSD64, 1: DSD128, etc.
      } else null
    }
    
    val status = new Bundle {
      val clockLocked = out Bool()
      val actualRate = out UInt(32 bits)
      val syncError = out Bool()
      val bufferLevel = out UInt(log2Up(config.fifoDepth) bits)
    }
  }
  
  // Clock generation and management
  val clockGen = new Area {
    // Master clock selection and monitoring
    val selectedMclk = if(config.useMultipleClocks) {
      io.control.clockSource.mux(
        0 -> (io.control.format.mux(
          AudioFormat.I2S_STANDARD -> io.clocks.mclk44k1.get,
          AudioFormat.TDM -> io.clocks.mclk48k.get,
          default -> io.clocks.mclk44k1.get
        )),
        1 -> io.clocks.mclk44k1.get,
        2 -> io.clocks.mclk48k.get,
        default -> io.clocks.mclk44k1.get
      )
    } else ClockDomain.current.readClockWire
    
    // Clock divider calculations
    val mclkDiv = io.control.format.mux(
      AudioFormat.I2S_STANDARD -> U(config.masterClockMultiples(0)),
      AudioFormat.TDM -> U(config.masterClockMultiples(1)),
      AudioFormat.DSD_64 -> U(16),
      AudioFormat.DSD_128 -> U(8),
      AudioFormat.DSD_256 -> U(4),
      default -> U(config.masterClockMultiples(0))
    )
    
    val bclkDiv = RegNext(mclkDiv >> io.control.sampleRate.mux(
      U(44100) -> U(0),
      U(48000) -> U(0),
      U(88200) -> U(1),
      U(96000) -> U(1),
      U(176400) -> U(2),
      U(192000) -> U(2),
      default -> U(0)
    ))
    
    // Clock generation
    val bclkCounter = Counter(bclkDiv)
    val frameCounter = Counter(config.tdmSlots * config.tdmSlotWidth)
    
    when(io.control.masterMode) {
      io.clocks.bclk := bclkCounter.value < (bclkDiv >> 1)
      
      // Frame/Word clock generation depends on mode
      when(io.control.format === AudioFormat.TDM) {
        io.clocks.lrclk := frameCounter.value === 0
        io.tdm.frameSync := frameCounter.value === 0
        io.tdm.slotNumber := frameCounter.value >> log2Up(config.tdmSlotWidth)
      } otherwise {
        io.clocks.lrclk := frameCounter.value < (frameCounter.end >> 1)
      }
    }
  }
  
  // I2S processing
  val i2sProcessor = new Area {
    val enabled = io.control.format.isIn(AudioFormat.I2S_STANDARD, 
                                       AudioFormat.I2S_JUSTIFY_LEFT,
                                       AudioFormat.I2S_JUSTIFY_RIGHT)
    
    // Shift registers for each channel
    val rxShiftRegs = Vec(Reg(Bits(config.dataWidth bits)), config.channelCount)
    val txShiftRegs = Vec(Reg(Bits(config.dataWidth bits)), config.channelCount)
    
    // Bit counters
    val bitCounter = Counter(config.dataWidth)
    val channelCounter = Counter(config.channelCount)
    
    when(enabled) {
      // Handle receiving
      when(clockGen.bclkCounter.willOverflow) {
        for(i <- 0 until config.channelCount) {
          when(channelCounter.value === i) {
            rxShiftRegs(i) := (rxShiftRegs(i) << 1) | io.i2s.rx(i).asBits
          }
        }
        
        bitCounter.increment()
        when(bitCounter.willOverflow) {
          channelCounter.increment()
          // Output complete sample
          for(i <- 0 until config.channelCount) {
            io.rxStreams(i).valid := True
            io.rxStreams(i).payload := rxShiftRegs(i)
          }
        }
      }
      
      // Handle transmitting
      when(bitCounter.willOverflow && channelCounter.willOverflow) {
        // Load new samples
        for(i <- 0 until config.channelCount) {
          when(io.txStreams(i).valid) {
            txShiftRegs(i) := io.txStreams(i).payload
            io.txStreams(i).ready := True
          }
        }
      }
      
      // Output bits
      for(i <- 0 until config.channelCount) {
        io.i2s.tx(i) := txShiftRegs(i)(config.dataWidth - 1 - bitCounter.value)
      }
    }
  }
  
  
 // TDM processing
  val tdmProcessor = new Area {
    val enabled = io.control.format === AudioFormat.TDM
    
    // TDM frame management
    val frameSize = io.control.tdmConfig.slotCount * io.control.tdmConfig.slotWidth
    val slotCounter = Counter(io.control.tdmConfig.slotCount)
    val bitCounter = Counter(io.control.tdmConfig.slotWidth)
    
    // Data buffers for TDM
    val rxBuffer = Vec(Reg(Bits(config.dataWidth bits)), config.tdmSlots)
    val txBuffer = Vec(Reg(Bits(config.dataWidth bits)), config.tdmSlots)
    val rxShiftReg = Reg(Bits(config.tdmSlotWidth bits))
    val txShiftReg = Reg(Bits(config.tdmSlotWidth bits))
    
    when(enabled) {
      // TDM receive logic
      when(clockGen.bclkCounter.willOverflow) {
        rxShiftReg := (rxShiftReg << 1) | io.tdm.rx.asBits
        
        when(bitCounter.willOverflow) {
          // Store completed slot
          rxBuffer(slotCounter.value) := rxShiftReg
          
          when(slotCounter.willOverflow) {
            // Complete frame received
            for(i <- 0 until config.channelCount) {
              io.rxStreams(i).valid := True
              io.rxStreams(i).payload := rxBuffer(i)
            }
          }
          
          slotCounter.increment()
        }
        bitCounter.increment()
      }
      
      // TDM transmit logic
      when(slotCounter.willOverflow && bitCounter.willOverflow) {
        // Load new frame data
        for(i <- 0 until config.channelCount) {
          when(io.txStreams(i).valid) {
            txBuffer(i) := io.txStreams(i).payload
            io.txStreams(i).ready := True
          }
        }
      }
      
      // Output current TDM bit
      io.tdm.tx := txBuffer(slotCounter.value)(bitCounter.value)
    }
  }
  
  // DSD processing
  val dsdProcessor = new Area {
    val enabled = io.control.format.isIn(AudioFormat.DSD_64, 
                                       AudioFormat.DSD_128,
                                       AudioFormat.DSD_256)
    
    // DSD specific clocking
    val dsdClockDiv = io.control.format.mux(
      AudioFormat.DSD_64 -> U(1),
      AudioFormat.DSD_128 -> U(2),
      AudioFormat.DSD_256 -> U(4),
      default -> U(1)
    )
    
    val dsdClockCounter = Counter(dsdClockDiv)
    
    // DSD buffers (8 bits per channel for efficient transfers)
    val rxDsdBuffers = Vec(Reg(Bits(8 bits)), config.dsdChannels)
    val txDsdBuffers = Vec(Reg(Bits(8 bits)), config.dsdChannels)
    val bitCounter = Counter(8)
    
    when(enabled && config.supportDsd) {
      // DSD clock generation
      io.dsd.dsdClk := dsdClockCounter.value < (dsdClockDiv >> 1)
      
      when(dsdClockCounter.willOverflow) {
        // DSD receive
        for(i <- 0 until config.dsdChannels) {
          rxDsdBuffers(i)(bitCounter.value) := io.dsd.rx(i)
        }
        
        when(bitCounter.willOverflow) {
          // Output complete DSD byte
          for(i <- 0 until config.dsdChannels) {
            io.rxStreams(i).valid := True
            io.rxStreams(i).payload := rxDsdBuffers(i)
          }
        }
        
        // DSD transmit
        when(bitCounter.willOverflow) {
          // Load new DSD data
          for(i <- 0 until config.dsdChannels) {
            when(io.txStreams(i).valid) {
              txDsdBuffers(i) := io.txStreams(i).payload
              io.txStreams(i).ready := True
            }
          }
        }
        
        // Output DSD bits
        for(i <- 0 until config.dsdChannels) {
          io.dsd.tx(i) := txDsdBuffers(i)(bitCounter.value)
        }
        
        bitCounter.increment()
      }
    }
  }
  
  // Buffer management and synchronization
  val bufferManager = new Area {
    // Buffer level tracking
    val rxBufferCount = CounterUpDown(
      stateCount = config.fifoDepth + 1,
      incWhen = io.rxStreams.map(_.valid).reduce(_ || _),
      decWhen = io.rxStreams.map(_.ready).reduce(_ || _)
    )
    
    val txBufferCount = CounterUpDown(
      stateCount = config.fifoDepth + 1,
      incWhen = io.txStreams.map(_.valid).reduce(_ || _),
      decWhen = io.txStreams.map(_.ready).reduce(_ || _)
    )
    
    // Buffer status reporting
    io.status.bufferLevel := rxBufferCount.value
    
    // Underrun/overrun detection
    val underrun = txBufferCount.value === 0 && 
                  io.txStreams.map(_.ready).reduce(_ || _)
    val overrun = rxBufferCount.value === config.fifoDepth && 
                 io.rxStreams.map(_.valid).reduce(_ || _)
  }
  
  // Clock monitoring and status
  val clockMonitor = new Area {
    val lockCounter = Counter(4096)
    val wasLocked = RegNext(False)
    
    when(clockGen.bclkCounter.willOverflow) {
      lockCounter.increment()
    }
    
    // Clock lock detection
    val locked = lockCounter.willOverflow
    io.status.clockLocked := locked
    
    // Clock slip detection
    io.clocks.clockSlip := wasLocked && !locked
    wasLocked := locked
    
    // Sample rate measurement and reporting
    val sampleRateCounter = new Area {
      val count = Reg(UInt(32 bits)) init(0)
      val reference = Reg(UInt(32 bits)) init(0)
      
      // Simple rate measurement based on frame clock
      when(clockGen.frameCounter.willOverflow) {
        count := count + 1
      }
      
      // Update every second (assuming a reference clock)
      when(reference === 100000000) {  // 100MHz reference
        io.status.actualRate := count
        count := 0
        reference := 0
      } otherwise {
        reference := reference + 1
      }
    }
  }
  
  // Audio data processing
  val dataProcessor = new Area {
    // I2S processing
    val i2sLogic = new Area {
      val enabled = io.format === AudioFormat.I2S
      val bitCounter = Counter(config.i2sDataWidth)
      val channelCounter = Counter(config.channelCount)
      
      // Shift registers for I2S data
      val txShiftRegs = Vec(Reg(Bits(config.i2sDataWidth bits)), config.channelCount)
      val rxShiftRegs = Vec(Reg(Bits(config.i2sDataWidth bits)), config.channelCount)
      
      when(enabled) {
        // Load new data at frame boundaries
        when(bitCounter.willOverflow && channelCounter.willOverflow) {
          when(io.txData.valid) {
            txShiftRegs := io.txData.payload
            io.txData.ready := True
          }
          
          // Output captured data
          io.rxData.valid := True
          io.rxData.payload := rxShiftRegs
        }
        
        // Shift data on clock edges
        when(clockGen.bclkCounter.willOverflow) {
          for(i <- 0 until config.channelCount) {
            when(channelCounter.value === i) {
              // MSB first transmission
              io.i2s.sd(i) := txShiftRegs(i)(config.i2sDataWidth - 1 - bitCounter.value)
              // Capture input data
              rxShiftRegs(i)(bitCounter.value) := io.i2s.sd(i)
            }
          }
          
          bitCounter.increment()
          when(bitCounter.willOverflow) {
            channelCounter.increment()
          }
        }
      }
    }
    
    // DSD processing
    val dsdLogic = new Area {
      val enabled = io.format === AudioFormat.DSD
      val bitCounter = Counter(8)  // DSD processes 8 bits at a time
      
      // DSD data buffers
      val txBuffers = Vec(Reg(Bits(8 bits)), config.channelCount)
      val rxBuffers = Vec(Reg(Bits(8 bits)), config.channelCount)
      
      when(enabled) {
        // Load new DSD data
        when(bitCounter.willOverflow) {
          when(io.txData.valid) {
            for(i <- 0 until config.channelCount) {
              txBuffers(i) := io.txData.payload(i)(7 downto 0)
            }
            io.txData.ready := True
          }
          
          // Output captured DSD data
          io.rxData.valid := True
          for(i <- 0 until config.channelCount) {
            io.rxData.payload(i) := rxBuffers(i).resized
          }
        }
        
        // Process DSD bits
        when(clockGen.bclkCounter.willOverflow) {
          for(i <- 0 until config.channelCount) {
            io.i2s.sd(i) := txBuffers(i)(bitCounter.value)
            rxBuffers(i)(bitCounter.value) := io.i2s.sd(i)
          }
          bitCounter.increment()
        }
      }
    }
  }
  
  // Error detection
  val errorDetection = new Area {
    // Detect clock stability issues
    val clockError = RegNext(!io.clockLocked && RegNext(io.clockLocked)) init(False)
    
    // Detect data underflow/overflow
    val txUnderflow = RegNext(dataProcessor.i2sLogic.enabled && 
                             !io.txData.valid && 
                             dataProcessor.i2sLogic.bitCounter.willOverflow) init(False)
    
    val rxOverflow = RegNext(dataProcessor.i2sLogic.enabled && 
                            io.rxData.valid && 
                            !io.rxData.ready) init(False)
  }
}