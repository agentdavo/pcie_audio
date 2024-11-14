package audio

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import scala.collection.mutable.ArrayBuffer
import org.scalatest.funsuite.AnyFunSuite

class AudioPCIeTest extends AnyFunSuite {
  
  def testConfig = AudioConfig(
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

  class TestEnvironment(dut: AudioPCIeTop) {
    val audioData = ArrayBuffer[BigInt]()
    var dmaTransfers = 0
    var underruns = 0
    var overruns = 0
    
    // Register access helpers
    def writeReg(address: BigInt, data: BigInt): Unit = {
      dut.clockDomain.waitSampling()
      dut.io.pcie.cfg.write #= true
      dut.io.pcie.cfg.addr #= address
      dut.io.pcie.cfg.writeData #= data
      dut.clockDomain.waitSampling()
      dut.io.pcie.cfg.write #= false
    }
    
    def readReg(address: BigInt): BigInt = {
      dut.clockDomain.waitSampling()
      dut.io.pcie.cfg.read #= true
      dut.io.pcie.cfg.addr #= address
      dut.clockDomain.waitSampling()
      dut.io.pcie.cfg.read #= false
      dut.io.pcie.cfg.readData.toBigInt
    }
    
    // PCIe DMA helpers
    def writeDMADescriptor(baseAddr: BigInt, isPlayback: Boolean): Unit = {
      val descBase = if(isPlayback) 0x100 else 0x200
      writeReg(descBase, baseAddr)
      writeReg(descBase + 0x8, 32) // descriptor count
      
      // Initialize descriptors in memory
      for(i <- 0 until 32) {
        val descAddr = baseAddr + (i * 16)
        val bufAddr = 0x1000 + (i * 4096)
        writeDMAmem(descAddr + 0, bufAddr)           // buffer address
        writeDMAmem(descAddr + 8, 4096)             // buffer size
        writeDMAmem(descAddr + 12, if(i == 31) 1 else 0) // last descriptor flag
      }
    }
    
    def writeDMAmem(address: BigInt, data: BigInt): Unit = {
      dut.clockDomain.waitSampling()
      dut.io.pcie.tx.valid #= true
      dut.io.pcie.tx.data #= data
      dut.io.pcie.tx.keepBits #= ((1 << 16) - 1)
      while(!dut.io.pcie.tx.ready.toBoolean) {
        dut.clockDomain.waitSampling()
      }
      dut.clockDomain.waitSampling()
      dut.io.pcie.tx.valid #= false
    }
    
    // Audio clock generation
    def generateClocks(): Unit = fork {
      // 44.1kHz family clock
      val period44k = (100000000 / 11289600) // Simulate 11.2896MHz MCLK
      while(true) {
        dut.io.audio.mclk44k1 #= true
        sleep(period44k / 2)
        dut.io.audio.mclk44k1 #= false
        sleep(period44k / 2)
      }
    }
    
    fork {
      // 48kHz family clock
      val period48k = (100000000 / 12288000) // Simulate 12.288MHz MCLK
      while(true) {
        dut.io.audio.mclk48k #= true
        sleep(period48k / 2)
        dut.io.audio.mclk48k #= false
        sleep(period48k / 2)
      }
    }
    
    // Monitoring
    def monitorAudio(): Unit = fork {
      while(true) {
        dut.clockDomain.waitSampling()
        if(dut.io.audio.i2s.ws.toBoolean) {
          for(i <- 0 until testConfig.channelCount) {
            if(dut.io.audio.i2s.sd(i).toBoolean) {
              audioData += 1
            } else {
              audioData += 0
            }
          }
        }
      }
    }
    
    def monitorDMA(): Unit = fork {
      while(true) {
        dut.clockDomain.waitSampling()
        // Count DMA transfers
        if(dut.io.pcie.tx.valid.toBoolean && dut.io.pcie.tx.ready.toBoolean) {
          dmaTransfers += 1
        }
        // Monitor errors
        if(readReg(0x30C) != 0) { // pbUnderrun
          underruns += 1
        }
        if(readReg(0x310) != 0) { // capOverrun
          overruns += 1
        }
      }
    }
  }
  
  test("Basic I2S Playback") {
    SimConfig.withWave.compile(new AudioPCIeTop(testConfig)).doSim { dut =>
      val env = new TestEnvironment(dut)
      
      // Initialize clocks
      dut.clockDomain.forkStimulus(10)
      env.generateClocks()
      
      // Configure for I2S mode
      env.writeReg(0x000, 0) // I2S mode
      env.writeReg(0x004, 0) // 44.1kHz family
      env.writeReg(0x014, 1) // Master mode
      
      // Setup DMA
      env.writeDMADescriptor(0x10000000, true)
      
      // Start monitoring
      env.monitorAudio()
      env.monitorDMA()
      
      // Enable playback
      env.writeReg(0x018, 1)
      
      // Run simulation
      dut.clockDomain.waitSampling(10000)
      
      // Verify results
      assert(env.dmaTransfers > 0, "No DMA transfers occurred")
      assert(env.underruns == 0, "Playback underrun detected")
      assert(env.audioData.nonEmpty, "No audio data generated")
    }
  }
  
  test("DSD Mode Operation") {
    SimConfig.withWave.compile(new AudioPCIeTop(testConfig)).doSim { dut =>
      val env = new TestEnvironment(dut)
      
      dut.clockDomain.forkStimulus(10)
      env.generateClocks()
      
      // Configure for DSD mode
      env.writeReg(0x000, 1) // DSD mode
      env.writeReg(0x00C, 0) // DSD64
      env.writeReg(0x014, 1) // Master mode
      
      // Setup DMA
      env.writeDMADescriptor(0x20000000, true)
      
      // Start monitoring
      env.monitorDMA()
      
      // Enable playback
      env.writeReg(0x018, 1)
      
      dut.clockDomain.waitSampling(10000)
      
      assert(env.dmaTransfers > 0, "No DSD DMA transfers occurred")
      assert(env.underruns == 0, "DSD playback underrun detected")
    }
  }
  
  test("Clock Domain Switching") {
    SimConfig.withWave.compile(new AudioPCIeTop(testConfig)).doSim { dut =>
      val env = new TestEnvironment(dut)
      
      dut.clockDomain.forkStimulus(10)
      env.generateClocks()
      
      // Start with 44.1kHz
      env.writeReg(0x004, 0)
      dut.clockDomain.waitSampling(1000)
      
      // Switch to 48kHz
      env.writeReg(0x004, 1)
      dut.clockDomain.waitSampling(1000)
      
      // Verify clock lock
      val clockLocked = env.readReg(0x300)
      assert(clockLocked == 1, "Clock failed to lock after switching")
    }
  }
  
  test("Full Duplex Operation") {
    SimConfig.withWave.compile(new AudioPCIeTop(testConfig)).doSim { dut =>
      val env = new TestEnvironment(dut)
      
      dut.clockDomain.forkStimulus(10)
      env.generateClocks()
      
      // Configure I2S mode
      env.writeReg(0x000, 0)
      env.writeReg(0x004, 0)
      env.writeReg(0x014, 1)
      
      // Setup both playback and capture DMA
      env.writeDMADescriptor(0x30000000, true)
      env.writeDMADescriptor(0x40000000, false)
      
      // Start monitoring
      env.monitorDMA()
      
      // Enable both directions
      env.writeReg(0x018, 1)
      env.writeReg(0x01C, 1)
      
      dut.clockDomain.waitSampling(20000)
      
      assert(env.dmaTransfers > 0, "No full duplex transfers occurred")
      assert(env.underruns == 0, "Playback underrun in full duplex")
      assert(env.overruns == 0, "Capture overrun in full duplex")
    }
  }
  
  test("Error Recovery") {
    SimConfig.withWave.compile(new AudioPCIeTop(testConfig)).doSim { dut =>
      val env = new TestEnvironment(dut)
      
      dut.clockDomain.forkStimulus(10)
      env.generateClocks()
      
      // Start playback without DMA setup to force underrun
      env.writeReg(0x000, 0)
      env.writeReg(0x018, 1)
      
      dut.clockDomain.waitSampling(1000)
      
      // Verify underrun detected
      val underrun = env.readReg(0x30C)
      assert(underrun == 1, "Underrun not detected")
      
      // Reset and recover
      env.writeReg(0x020, 1)
      dut.clockDomain.waitSampling(10)
      env.writeReg(0x020, 0)
      
      // Setup proper DMA
      env.writeDMADescriptor(0x50000000, true)
      env.writeReg(0x018, 1)
      
      dut.clockDomain.waitSampling(1000)
      
      val recoveryStatus = env.readReg(0x30C)
      assert(recoveryStatus == 0, "Failed to recover from underrun")
    }
  }
  
  test("Performance Metrics") {
    SimConfig.withWave.compile(new AudioPCIeTop(testConfig)).doSim { dut =>
      val env = new TestEnvironment(dut)
      
      dut.clockDomain.forkStimulus(10)
      env.generateClocks()
      
      // Setup high-throughput test
      env.writeReg(0x000, 0)
      env.writeReg(0x004, 0)
      env.writeDMADescriptor(0x60000000, true)
      
      // Enable maximum performance
      env.writeReg(0x038, 512)  // Large buffer threshold
      env.writeReg(0x018, 1)    // Enable playback
      
      val startTime = System.nanoTime()
      dut.clockDomain.waitSampling(50000)
      val endTime = System.nanoTime()
      
      val duration = (endTime - startTime) / 1000000000.0
      val transferRate = env.dmaTransfers.toDouble / duration
      
      println(s"DMA Transfer Rate: $transferRate transfers/second")
      println(s"Underruns: ${env.underruns}")
      
      assert(transferRate > 1000, "DMA transfer rate too low")
      assert(env.underruns == 0, "Performance test caused underruns")
    }
  }
}