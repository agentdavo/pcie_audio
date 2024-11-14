package audio

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

class DMAEngine(config: AudioConfig, pcieConfig: PCIeConfig) extends Component {
  val io = new Bundle {
    // AXI Master interface for PCIe
    val axi = master(Axi4(
      config = Axi4Config(
        addressWidth = 64,
        dataWidth = 128,  // 128-bit width for better throughput
        idWidth = 8,
        useStrb = true,
        useLast = true,
        useResp = true,
        useLock = false,
        useCache = true,
        useQos = false,
        useProt = true
      )
    ))
    
    // Control/Status interface
    val control = new Bundle {
      // Playback control
      val pbEnable = in Bool()
      val pbDescBaseAddr = in UInt(64 bits)
      val pbDescCount = in UInt(8 bits)
      val pbComplete = out Bool()
      val pbError = out Bool()
      
      // Capture control
      val capEnable = in Bool()
      val capDescBaseAddr = in UInt(64 bits)
      val capDescCount = in UInt(8 bits)
      val capComplete = out Bool()
      val capError = out Bool()
      
      // Status
      val pbBytesProcessed = out UInt(32 bits)
      val capBytesProcessed = out UInt(32 bits)
      val pbDescActive = out UInt(8 bits)
      val capDescActive = out UInt(8 bits)
    }
    
    // Audio data interfaces
    val audioIn = slave Stream(Vec(Bits(config.i2sDataWidth bits), config.channelCount))
    val audioOut = master Stream(Vec(Bits(config.i2sDataWidth bits), config.channelCount))
  }
  
  // DMA FIFOs for buffering
  val pbFifo = StreamFifo(
    dataType = Vec(Bits(config.i2sDataWidth bits), config.channelCount),
    depth = config.fifoDepth
  )
  
  val capFifo = StreamFifo(
    dataType = Vec(Bits(config.i2sDataWidth bits), config.channelCount),
    depth = config.fifoDepth
  )
  
  // Descriptor caches
  val pbDescCache = new Area {
    val descriptors = Array.fill(config.dmaDescriptorCount)(Reg(DMADescriptor()))
    val currentIdx = Reg(UInt(log2Up(config.dmaDescriptorCount) bits)) init(0)
    val active = Reg(UInt(8 bits)) init(0)
    val bytesProcessed = Reg(UInt(32 bits)) init(0)
  }
  
  val capDescCache = new Area {
    val descriptors = Array.fill(config.dmaDescriptorCount)(Reg(DMADescriptor()))
    val currentIdx = Reg(UInt(log2Up(config.dmaDescriptorCount) bits)) init(0)
    val active = Reg(UInt(8 bits)) init(0)
    val bytesProcessed = Reg(UInt(32 bits)) init(0)
  }
  
  // Playback DMA state machine
  val pbDmaFsm = new Area {
    val state = Reg(UInt(3 bits)) init(0)
    val burstCounter = Reg(UInt(log2Up(config.maxBurstSize/16) bits)) init(0)
    val burstActive = Reg(Bool) init(False)
    
    // State machine definitions
    val IDLE = 0
    val FETCH_DESC = 1
    val READ_DATA = 2
    val WRITE_FIFO = 3
    val UPDATE_DESC = 4
    val COMPLETE = 5
    
    switch(state) {
      is(IDLE) {
        when(io.control.pbEnable && pbFifo.io.availability >= (config.maxBurstSize/(config.i2sDataWidth/8))) {
          state := FETCH_DESC
        }
      }
      
      is(FETCH_DESC) {
        when(!pbDescCache.descriptors(pbDescCache.currentIdx).complete) {
          // Setup AXI read
          io.axi.ar.valid := True
          io.axi.ar.addr := pbDescCache.descriptors(pbDescCache.currentIdx).address
          io.axi.ar.len := (config.maxBurstSize/16 - 1)
          io.axi.ar.size := 4  // 16 bytes
          io.axi.ar.burst := 1 // INCR
          io.axi.ar.cache := B"0011" // Non-allocating, cacheable
          io.axi.ar.prot := B"000"
          
          when(io.axi.ar.ready) {
            state := READ_DATA
            burstCounter := 0
            burstActive := True
          }
        } otherwise {
          state := COMPLETE
        }
      }
      
      is(READ_DATA) {
        when(io.axi.r.valid && pbFifo.io.push.ready) {
          // Convert AXI data to audio samples
          val samples = Vec(Bits(config.i2sDataWidth bits), config.channelCount)
          for(i <- 0 until config.channelCount) {
            samples(i) := io.axi.r.data(i * config.i2sDataWidth until (i + 1) * config.i2sDataWidth)
          }
          
          pbFifo.io.push.valid := True
          pbFifo.io.push.payload := samples
          
          burstCounter := burstCounter + 1
          
          when(io.axi.r.last || burstCounter === (config.maxBurstSize/16 - 1)) {
            state := UPDATE_DESC
            burstActive := False
          }
        }
      }
      
      is(UPDATE_DESC) {
        pbDescCache.descriptors(pbDescCache.currentIdx).complete := True
        pbDescCache.bytesProcessed := pbDescCache.bytesProcessed + config.maxBurstSize
        
        when(pbDescCache.descriptors(pbDescCache.currentIdx).interrupt) {
          io.control.pbComplete := True
        }
        
        when(pbDescCache.descriptors(pbDescCache.currentIdx).lastInChain) {
          pbDescCache.currentIdx := 0
          state := COMPLETE
        } otherwise {
          pbDescCache.currentIdx := pbDescCache.currentIdx + 1
          state := IDLE
        }
      }
      
      is(COMPLETE) {
        io.control.pbComplete := True
        when(!io.control.pbEnable) {
          state := IDLE
        }
      }
    }
  }
  
  // Capture DMA state machine
  val capDmaFsm = new Area {
    val state = Reg(UInt(3 bits)) init(0)
    val burstCounter = Reg(UInt(log2Up(config.maxBurstSize/16) bits)) init(0)
    val burstActive = Reg(Bool) init(False)
    
    switch(state) {
      is(IDLE) {
        when(io.control.capEnable && capFifo.io.occupancy >= (config.maxBurstSize/(config.i2sDataWidth/8))) {
          state := FETCH_DESC
        }
      }
      
      is(FETCH_DESC) {
        when(!capDescCache.descriptors(capDescCache.currentIdx).complete) {
          // Setup AXI write
          io.axi.aw.valid := True
          io.axi.aw.addr := capDescCache.descriptors(capDescCache.currentIdx).address
          io.axi.aw.len := (config.maxBurstSize/16 - 1)
          io.axi.aw.size := 4  // 16 bytes
          io.axi.aw.burst := 1 // INCR
          io.axi.aw.cache := B"0011"
          io.axi.aw.prot := B"000"
          
          when(io.axi.aw.ready) {
            state := READ_DATA
            burstCounter := 0
            burstActive := True
          }
        } otherwise {
          state := COMPLETE
        }
      }
      
      is(READ_DATA) {
        when(capFifo.io.pop.valid) {
          val samples = capFifo.io.pop.payload
          val axiData = Bits(128 bits)
          
          // Pack audio samples into AXI data
          for(i <- 0 until config.channelCount) {
            axiData(i * config.i2sDataWidth until (i + 1) * config.i2sDataWidth) := samples(i)
          }
          
          io.axi.w.valid := True
          io.axi.w.data := axiData
          io.axi.w.strb := ((1 << (config.channelCount * (config.i2sDataWidth/8))) - 1)
          
          burstCounter := burstCounter + 1
          io.axi.w.last := burstCounter === (config.maxBurstSize/16 - 1)
          
          when(io.axi.w.last) {
            state := UPDATE_DESC
            burstActive := False
          }
        }
      }
      
      is(UPDATE_DESC) {
        capDescCache.descriptors(capDescCache.currentIdx).complete := True
        capDescCache.bytesProcessed := capDescCache.bytesProcessed + config.maxBurstSize
        
        when(capDescCache.descriptors(capDescCache.currentIdx).interrupt) {
          io.control.capComplete := True
        }
        
        when(capDescCache.descriptors(capDescCache.currentIdx).lastInChain) {
          capDescCache.currentIdx := 0
          state := COMPLETE
        } otherwise {
          capDescCache.currentIdx := capDescCache.currentIdx + 1
          state := IDLE
        }
      }
      
      is(COMPLETE) {
        io.control.capComplete := True
        when(!io.control.capEnable) {
          state := IDLE
        }
      }
    }
  }
  
  // Connect status outputs
  io.control.pbBytesProcessed := pbDescCache.bytesProcessed
  io.control.capBytesProcessed := capDescCache.bytesProcessed
  io.control.pbDescActive := pbDescCache.active
  io.control.capDescActive := capDescCache.active
  
  // Connect audio streams
  io.audioOut << pbFifo.io.pop
  capFifo.io.push << io.audioIn
}