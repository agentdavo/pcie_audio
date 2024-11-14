package audio

import spinal.core._
import spinal.lib._

// Audio format enumeration
object AudioFormat extends SpinalEnum {
  val I2S_STANDARD = newElement()  // Standard I2S
  val I2S_JUSTIFY_LEFT = newElement()  // Left-justified
  val I2S_JUSTIFY_RIGHT = newElement() // Right-justified
  val TDM = newElement()            // TDM mode
  val DSD_64 = newElement()         // DSD64
  val DSD_128 = newElement()        // DSD128
  val DSD_256 = newElement()        // DSD256
}

object SampleRateFamily extends SpinalEnum {
  val SF_44K1, SF_48K = newElement()
}

// Core configuration for audio interface
case class AudioConfig(
  // Basic configuration
  channelCount: Int,          // Total number of channels (8 in, 8 out)
  dataWidth: Int,             // Sample width (up to 32-bit)
  
  // Audio format support
  supportI2s: Boolean = true,
  supportTdm: Boolean = true,
  supportDsd: Boolean = true,
  
  // TDM specific
  tdmSlots: Int = 8,          // Number of TDM slots
  tdmSlotWidth: Int = 32,     // TDM slot width
  
  // DSD specific
  dsdWidth: Int = 1,          // DSD bit width (DSD64 = 1, DSD128 = 2, etc.)
  dsdChannels: Int = 8,       // Number of DSD channels
  
  // Clock configuration
  masterClockMultiples: Seq[Int] = Seq(256, 512), // Supported MCLK ratios
  wordClockMultiples: Seq[Int] = Seq(64, 128),    // Supported WCLK ratios
  
  // Buffer configuration
  bufferSize: Int,            // Size per buffer in bytes
  bufferCount: Int,           // Number of buffers per direction
  fifoDepth: Int,             // FIFO depth in samples
  
  // DMA configuration
  maxBurstSize: Int,          // Maximum PCIe burst size
  dmaDescriptorCount: Int,    // Number of DMA descriptors
  
  // Advanced features
  supportSRC: Boolean = true, // Sample rate conversion support
  supportMix: Boolean = true  // Internal mixing support
)

// PCIe configuration parameters
case class PCIeConfig(
  maxReadRequestSize: Int,
  maxPayloadSize: Int,
  completionTimeout: Int,
  relaxedOrdering: Boolean,
  extendedTags: Boolean,
  maxTags: Int
)

// Register bank definition
case class RegisterBank() extends Bundle {
  // Control registers
  val control = new Bundle {
    val format = AudioFormat()
    val sampleRateFamily = SampleRateFamily()
    val sampleRateMulti = UInt(4 bits)
    val dsdMode = UInt(2 bits)
    val clockSource = UInt(2 bits)
    val masterMode = Bool
    val playbackEnable = Bool
    val captureEnable = Bool
    val reset = Bool
    
    // Advanced control
    val mclkFrequency = UInt(32 bits)
    val targetSampleRate = UInt(32 bits)
    val pbBufferThreshold = UInt(16 bits)
    val capBufferThreshold = UInt(16 bits)
    
    // Audio format control
    val i2sFormat = new Bundle {
      val bitDepth = UInt(8 bits)
      val alignment = UInt(2 bits)  // 0=LEFT, 1=RIGHT, 2=I2S
      val tdm = Bool
      val tdmSlots = UInt(4 bits)
    }
    
    // Clock control
    val clockConfig = new Bundle {
      val mclkDiv = UInt(8 bits)
      val bclkDiv = UInt(8 bits)
      val syncTimeout = UInt(16 bits)
      val autoRateDetect = Bool
    }
  }
  
  // DMA registers
  val dma = new Bundle {
    // Playback
    val pbDescBaseAddr = UInt(64 bits)
    val pbDescCount = UInt(8 bits)
    val pbCurrentDesc = UInt(8 bits)
    val pbBufferSize = UInt(32 bits)
    val pbInterruptEnable = Bool
    val pbThreshold = UInt(16 bits)
    
    // Capture
    val capDescBaseAddr = UInt(64 bits)
    val capDescCount = UInt(8 bits)
    val capCurrentDesc = UInt(8 bits)
    val capBufferSize = UInt(32 bits)
    val capInterruptEnable = Bool
    val capThreshold = UInt(16 bits)
  }
  
  // Status registers
  val status = new Bundle {
    val locked = Bool
    val actualRate = UInt(32 bits)
    val clockSource = UInt(2 bits)
    val pbUnderrun = Bool
    val capOverrun = Bool
    val dmaError = Bool
    val formatError = Bool
    
    // Extended status
    val clockStatus = new Bundle {
      val mclkFrequency = UInt(32 bits)
      val bclkFrequency = UInt(32 bits)
      val actualSampleRate = UInt(32 bits)
      val mclkValid = Bool
      val bclkValid = Bool
    }
    
    val bufferStatus = new Bundle {
      val pbFifoLevel = UInt(16 bits)
      val capFifoLevel = UInt(16 bits)
      val pbUnderrunCount = UInt(16 bits)
      val capOverrunCount = UInt(16 bits)
    }
    
    val dmaStatus = new Bundle {
      val pbDescriptorsActive = UInt(8 bits)
      val capDescriptorsActive = UInt(8 bits)
      val pbBytesProcessed = UInt(32 bits)
      val capBytesProcessed = UInt(32 bits)
    }
  }
}

// DMA descriptor structure
case class DMADescriptor() extends Bundle {
  val address = UInt(64 bits)
  val length = UInt(24 bits)
  val complete = Bool
  val interrupt = Bool
  val lastInChain = Bool
  val next = UInt(64 bits)
}